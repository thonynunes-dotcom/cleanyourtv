package com.gathin.tvhome;

import android.app.Activity;
import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.media.tv.TvContract;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * brutal_home: a brutalist Home drawn in code instead of activity_main, with the weather from Open-Meteo.
 * MainActivity hands it the whole screen and every lifecycle call when the switch is on; nothing of the
 * normal Home is built then. Thick borders, one accent colour, a huge clock, the temperature beside it,
 * then the inputs, Wi-Fi and Settings buttons and a 4-column grid of app names (no artwork).
 *
 * The weather is read only while Home is on screen, every 30 minutes (5 after a failure), for config.xml's
 * latitude and longitude; the last answer is kept so Home never starts empty.
 */
final class BrutalHome {
    private static final long WEATHER_EVERY_MS = 30 * 60_000L;
    private static final long WEATHER_RETRY_MS = 5 * 60_000L;
    private static final String PREFS = "brutal_home";
    private static final Locale PT = new Locale("pt", "BR");

    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final float density;
    private final int fg;
    private final int bg;
    private final int accent;
    private final String city;
    private final String lat;
    private final String lon;
    private final List<String> dockApps;
    private final Set<String> hiddenApps;
    private final String liveTvPkg;

    private TextView greeting;
    private TextView date;
    private TextView clock;
    private TextView temp;
    private TextView now;
    private TextView feels;
    private TextView range;
    private TextView place;
    private TextView source;
    private TextView appCount;
    private final TextView[] dayName = new TextView[3];
    private final TextView[] dayTemp = new TextView[3];
    private final TextView[] dayText = new TextView[3];
    private RecyclerView grid;
    private final Tiles tiles = new Tiles();
    private boolean resumed;
    private boolean destroyed;
    private Dialog inputsDialog;

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            updateClock();
            long ms = System.currentTimeMillis();
            handler.postDelayed(this, 60_000L - ms % 60_000L + 50);
        }
    };

    private final Runnable weatherTick = this::fetchWeather;

    BrutalHome(Activity activity) {
        this.activity = activity;
        density = activity.getResources().getDisplayMetrics().density;
        boolean dark = activity.getResources().getBoolean(R.bool.brutal_dark);
        fg = dark ? 0xFFEDEBE4 : 0xFF111111;
        bg = dark ? 0xFF111111 : 0xFFEDEBE4;
        accent = parseColor(activity.getString(R.string.brutal_accent), 0xFFFFE600);
        city = activity.getString(R.string.brutal_city).trim();
        lat = activity.getString(R.string.latitude).trim();
        lon = activity.getString(R.string.longitude).trim();
        Config cfg = Config.load(activity);
        dockApps = cfg.dockApps;
        hiddenApps = new HashSet<>(cfg.hiddenApps);
        liveTvPkg = cfg.liveTvPackage;
    }

    // ---- lifecycle, called by MainActivity ---------------------------------------------------------

    void create() {
        Window w = activity.getWindow();
        w.setBackgroundDrawable(new ColorDrawable(bg));
        activity.setContentView(buildRoot());
        showCachedWeather();
        loadApps();
    }

    void resume() {
        resumed = true;
        updateClock();
        handler.removeCallbacks(tick);
        handler.post(tick);
        loadApps();
        long age = System.currentTimeMillis() - prefs().getLong("at", 0);
        handler.removeCallbacks(weatherTick);
        if (age >= WEATHER_EVERY_MS) handler.post(weatherTick);
        else handler.postDelayed(weatherTick, WEATHER_EVERY_MS - age);
    }

    void pause() {
        resumed = false;
        handler.removeCallbacks(tick);
        handler.removeCallbacks(weatherTick);
        if (inputsDialog != null) inputsDialog.dismiss();
    }

    void destroy() {
        destroyed = true;
        handler.removeCallbacksAndMessages(null);
        io.shutdownNow();
    }

    /** Home pressed while Home is in front, or the system input switcher (VIEW_INPUTS). */
    void newIntent(Intent intent) {
        if (intent != null && "com.android.tv.action.VIEW_INPUTS".equals(intent.getAction())) {
            activity.setIntent(new Intent(Intent.ACTION_MAIN));
            showInputs();
            return;
        }
        if (grid != null && tiles.getItemCount() > 0) {
            grid.scrollToPosition(0);
            grid.post(this::focusFirstTile);
        }
    }

    // ---- views ---------------------------------------------------------------------------------------

    private View buildRoot() {
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg);
        root.setPadding(dp(36), dp(26), dp(36), dp(22));

        root.addView(buildHeader(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        barLp.topMargin = dp(18);
        root.addView(buildButtons(), barLp);

        grid = new RecyclerView(activity);
        grid.setLayoutManager(new GridLayoutManager(activity, 4));
        grid.setAdapter(tiles);
        grid.setItemAnimator(null);
        grid.setClipToPadding(false);
        grid.setPadding(0, dp(8), 0, dp(8));
        LinearLayout.LayoutParams gridLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        gridLp.topMargin = dp(10);
        root.addView(grid, gridLp);

        LinearLayout footer = new LinearLayout(activity);
        footer.setOrientation(LinearLayout.HORIZONTAL);
        footer.setPadding(0, dp(7), 0, 0);
        footer.setBackground(topRule());
        source = mono(9, true);
        source.setText(R.string.brutal_weather_none);
        footer.addView(source, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView hint = mono(9, true);
        hint.setText(R.string.brutal_hint);
        footer.addView(hint);
        root.addView(footer, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        return root;
    }

    /** The clock block and the weather block in one frame; the frame's colour shows through the gaps as rules. */
    private View buildHeader() {
        LinearLayout frame = new LinearLayout(activity);
        frame.setOrientation(LinearLayout.HORIZONTAL);
        frame.setBackgroundColor(fg);
        int b = dp(3);
        frame.setPadding(b, b, b, b);

        LinearLayout left = new LinearLayout(activity);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setBackgroundColor(bg);
        left.setPadding(dp(18), dp(12), dp(18), dp(8));
        LinearLayout top = new LinearLayout(activity);
        greeting = mono(11, true);
        date = mono(11, true);
        date.setGravity(Gravity.END);
        top.addView(greeting, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        top.addView(date);
        left.addView(top);
        clock = black(104);
        clock.setIncludeFontPadding(false);
        left.addView(clock);
        LinearLayout.LayoutParams leftLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
        leftLp.rightMargin = b;
        frame.addView(left, leftLp);

        LinearLayout right = new LinearLayout(activity);
        right.setOrientation(LinearLayout.VERTICAL);
        right.setBackgroundColor(fg);

        LinearLayout nowRow = new LinearLayout(activity);
        nowRow.setBackgroundColor(fg);
        temp = black(72);
        temp.setIncludeFontPadding(false);
        temp.setTextColor(0xFF111111);
        temp.setBackgroundColor(accent);
        temp.setGravity(Gravity.CENTER);
        temp.setPadding(dp(14), dp(8), dp(14), dp(8));
        temp.setMinWidth(dp(150));
        temp.setText("--°");
        LinearLayout.LayoutParams tempLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.MATCH_PARENT);
        tempLp.rightMargin = b;
        nowRow.addView(temp, tempLp);
        LinearLayout info = new LinearLayout(activity);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setBackgroundColor(bg);
        info.setPadding(dp(12), dp(8), dp(12), dp(8));
        place = mono(10, true);
        place.setText(city.isEmpty() ? activity.getString(R.string.brutal_city_unset) : city.toUpperCase(PT));
        now = mono(10, true);
        feels = mono(10, true);
        range = mono(10, true);
        for (TextView t : new TextView[] {place, now, feels, range}) {
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            info.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }
        nowRow.addView(info, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        LinearLayout.LayoutParams nowLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        nowLp.bottomMargin = b;
        right.addView(nowRow, nowLp);

        LinearLayout days = new LinearLayout(activity);
        days.setBackgroundColor(fg);
        for (int i = 0; i < 3; i++) {
            LinearLayout cell = new LinearLayout(activity);
            cell.setOrientation(LinearLayout.VERTICAL);
            cell.setBackgroundColor(bg);
            cell.setPadding(dp(10), dp(6), dp(10), dp(6));
            dayName[i] = mono(9, true);
            dayTemp[i] = black(20);
            dayText[i] = mono(8, false);
            dayText[i].setSingleLine(true);
            dayText[i].setEllipsize(TextUtils.TruncateAt.END);
            cell.addView(dayName[i]);
            cell.addView(dayTemp[i]);
            cell.addView(dayText[i]);
            LinearLayout.LayoutParams cellLp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            if (i < 2) cellLp.rightMargin = b;
            days.addView(cell, cellLp);
        }
        right.addView(days, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        frame.addView(right, new LinearLayout.LayoutParams(dp(330), ViewGroup.LayoutParams.MATCH_PARENT));
        return frame;
    }

    private View buildButtons() {
        LinearLayout bar = new LinearLayout(activity);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(button(R.string.brutal_inputs, v -> showInputs()), buttonLp());
        bar.addView(button(R.string.brutal_wifi, v -> openSettings(Settings.ACTION_WIFI_SETTINGS)), buttonLp());
        bar.addView(button(R.string.brutal_settings, v -> openSettings(Settings.ACTION_SETTINGS)), buttonLp());
        View rule = new View(activity);
        rule.setBackgroundColor(fg);
        LinearLayout.LayoutParams ruleLp = new LinearLayout.LayoutParams(0, dp(3), 1f);
        ruleLp.rightMargin = dp(10);
        bar.addView(rule, ruleLp);
        appCount = mono(11, true);
        bar.addView(appCount);
        return bar;
    }

    private LinearLayout.LayoutParams buttonLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
        lp.rightMargin = dp(10);
        return lp;
    }

    private TextView button(int text, View.OnClickListener click) {
        TextView t = mono(11, true);
        if (text != 0) t.setText(text);
        t.setGravity(Gravity.CENTER);
        t.setPadding(dp(14), 0, dp(14), 0);
        t.setFocusable(true);
        t.setClickable(true);
        t.setOnClickListener(click);
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[] {android.R.attr.state_focused}, box(fg, fg, 0));
        states.addState(new int[0], box(Color.TRANSPARENT, fg, 0));
        t.setBackground(states);
        t.setTextColor(new ColorStateList(new int[][] {{android.R.attr.state_focused}, {}}, new int[] {bg, fg}));
        return t;
    }

    // ---- clock and date --------------------------------------------------------------------------------

    private void updateClock() {
        LocalTime t = LocalTime.now();
        clock.setText(t.format(DateTimeFormatter.ofPattern("HH:mm")));
        int h = t.getHour();
        greeting.setText(h < 5 ? R.string.brutal_night : h < 12 ? R.string.brutal_morning
                : h < 18 ? R.string.brutal_afternoon : R.string.brutal_night);
        LocalDate d = LocalDate.now();
        String day = d.format(DateTimeFormatter.ofPattern("EEE", PT)).replace(".", "");
        String rest = d.format(DateTimeFormatter.ofPattern("d MMM yyyy", PT)).replace(".", "");
        date.setText((day + " · " + rest).toUpperCase(PT));
    }

    // ---- weather ---------------------------------------------------------------------------------------

    private SharedPreferences prefs() {
        return activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private void showCachedWeather() {
        String json = prefs().getString("json", null);
        if (json == null) return;
        try {
            showWeather(new JSONObject(json), prefs().getLong("at", 0));
        } catch (Exception ignored) {
        }
    }

    private void fetchWeather() {
        if (!resumed || destroyed) return;
        if (lat.isEmpty() || lon.isEmpty()) {
            source.setText(R.string.brutal_weather_unset);
            return;
        }
        String url = "https://api.open-meteo.com/v1/forecast?latitude=" + Uri.encode(lat)
                + "&longitude=" + Uri.encode(lon)
                + "&current=temperature_2m,apparent_temperature,weather_code"
                + "&daily=weather_code,temperature_2m_max,temperature_2m_min"
                + "&timezone=auto&forecast_days=4";
        io.execute(() -> {
            String body = null;
            try {
                body = get(url);
                new JSONObject(body).getJSONObject("current");   // reject an error page before it is cached
            } catch (Exception e) {
                body = null;
            }
            final String result = body;
            handler.post(() -> {
                if (destroyed) return;
                if (result == null) {
                    if (resumed) handler.postDelayed(weatherTick, WEATHER_RETRY_MS);
                    return;
                }
                long at = System.currentTimeMillis();
                prefs().edit().putString("json", result).putLong("at", at).apply();
                try {
                    showWeather(new JSONObject(result), at);
                } catch (Exception ignored) {
                }
                if (resumed) handler.postDelayed(weatherTick, WEATHER_EVERY_MS);
            });
        });
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        try (InputStream in = c.getInputStream()) {
            if (c.getResponseCode() != 200) throw new IllegalStateException("HTTP " + c.getResponseCode());
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0 && out.size() < 64 * 1024) out.write(buf, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        } finally {
            c.disconnect();
        }
    }

    private void showWeather(JSONObject o, long at) throws Exception {
        JSONObject cur = o.getJSONObject("current");
        temp.setText(deg(cur.getDouble("temperature_2m")));
        now.setText(condition(cur.getInt("weather_code")));
        feels.setText(activity.getString(R.string.brutal_feels, deg(cur.getDouble("apparent_temperature"))));
        JSONObject daily = o.getJSONObject("daily");
        JSONArray days = daily.getJSONArray("time");
        JSONArray codes = daily.getJSONArray("weather_code");
        JSONArray max = daily.getJSONArray("temperature_2m_max");
        JSONArray min = daily.getJSONArray("temperature_2m_min");
        range.setText(activity.getString(R.string.brutal_range, deg(max.getDouble(0)), deg(min.getDouble(0))));
        for (int i = 0; i < 3 && i + 1 < days.length(); i++) {
            LocalDate d = LocalDate.parse(days.getString(i + 1));
            dayName[i].setText(d.format(DateTimeFormatter.ofPattern("EEE", PT)).replace(".", "").toUpperCase(PT));
            dayTemp[i].setText(deg(max.getDouble(i + 1)));
            dayText[i].setText(condition(codes.getInt(i + 1)));
        }
        String time = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault())
                .toLocalTime().format(DateTimeFormatter.ofPattern("HH:mm"));
        source.setText(activity.getString(R.string.brutal_weather_source, time));
    }

    private static String deg(double c) {
        return Math.round(c) + "°";
    }

    /** WMO weather codes, as Open-Meteo reports them, in short Portuguese words. */
    private static String condition(int code) {
        switch (code) {
            case 0: return "CÉU LIMPO";
            case 1: return "POUCAS NUVENS";
            case 2: return "PARC. NUBLADO";
            case 3: return "NUBLADO";
            case 45: case 48: return "NEBLINA";
            case 51: case 53: case 55: return "GAROA";
            case 56: case 57: return "GAROA GELADA";
            case 61: case 63: case 65: return "CHUVA";
            case 66: case 67: return "CHUVA GELADA";
            case 71: case 73: case 75: case 77: case 85: case 86: return "NEVE";
            case 80: case 81: case 82: return "PANCADAS";
            case 95: return "TROVOADA";
            case 96: case 99: return "TROVOADA/GRANIZO";
            default: return "—";
        }
    }

    // ---- apps ------------------------------------------------------------------------------------------

    private static final class App {
        final String pkg;
        final String label;

        App(String pkg, String label) {
            this.pkg = pkg;
            this.label = label;
        }
    }

    /** Every TV app (LEANBACK_LAUNCHER), dock_apps first in their order, the rest by name; hidden_apps left out. */
    private void loadApps() {
        PackageManager pm = activity.getPackageManager();
        Intent query = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        List<ResolveInfo> found = pm.queryIntentActivities(query, 0);
        List<App> rest = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ResolveInfo ri : found) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(activity.getPackageName()) || hiddenApps.contains(pkg) || !seen.add(pkg)) continue;
            rest.add(new App(pkg, String.valueOf(ri.loadLabel(pm)).trim()));
        }
        List<App> apps = new ArrayList<>();
        for (String pkg : dockApps) {
            for (int i = 0; i < rest.size(); i++) {
                if (rest.get(i).pkg.equals(pkg)) {
                    apps.add(rest.remove(i));
                    break;
                }
            }
        }
        rest.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        apps.addAll(rest);
        boolean first = tiles.apps.isEmpty();
        if (!first && sameApps(tiles.apps, apps)) return;   // back from an app: keep the tiles and the focus
        tiles.apps.clear();
        tiles.apps.addAll(apps);
        tiles.notifyDataSetChanged();
        appCount.setText(String.format(Locale.ROOT, "APPS / %02d", apps.size()));
        if (first && !apps.isEmpty()) grid.post(this::focusFirstTile);
    }

    private static boolean sameApps(List<App> a, List<App> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            if (!a.get(i).pkg.equals(b.get(i).pkg) || !a.get(i).label.equals(b.get(i).label)) return false;
        }
        return true;
    }

    private void focusFirstTile() {
        RecyclerView.ViewHolder h = grid.findViewHolderForAdapterPosition(0);
        if (h != null) h.itemView.requestFocus();
    }

    private void launch(String pkg) {
        PackageManager pm = activity.getPackageManager();
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        if (i == null) return;
        try {
            activity.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(activity, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private void appInfo(String pkg) {
        try {
            activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", pkg, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(activity, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private void openSettings(String action) {
        try {
            activity.startActivity(new Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(activity, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    private final class Tiles extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
        final List<App> apps = new ArrayList<>();

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout tile = new LinearLayout(activity);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setFocusable(true);
            tile.setClickable(true);
            tile.setLongClickable(true);
            int s = dp(6);   // the hard shadow's offset, kept free on every tile so focus never moves the grid
            tile.setPadding(dp(13), dp(10), dp(13) + s, dp(10) + s);
            StateListDrawable states = new StateListDrawable();
            states.addState(new int[] {android.R.attr.state_focused}, shadowed(s));
            states.addState(new int[0], inset(box(Color.TRANSPARENT, fg, 0), 0, 0, s, s));
            tile.setBackground(states);
            ColorStateList text = new ColorStateList(new int[][] {{android.R.attr.state_focused}, {}},
                    new int[] {0xFF111111, fg});
            TextView num = mono(10, true);
            num.setTextColor(text);
            num.setDuplicateParentStateEnabled(true);
            TextView name = black(24);
            name.setTextColor(text);
            name.setDuplicateParentStateEnabled(true);
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            tile.addView(num);
            View gap = new View(activity);
            tile.addView(gap, new LinearLayout.LayoutParams(1, 0, 1f));
            tile.addView(name);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(96));
            lp.setMargins(0, dp(4), dp(8), dp(4));
            tile.setLayoutParams(lp);
            return new RecyclerView.ViewHolder(tile) { };
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            App app = apps.get(position);
            LinearLayout tile = (LinearLayout) holder.itemView;
            ((TextView) tile.getChildAt(0)).setText(String.format(Locale.ROOT, "%02d", position + 1));
            ((TextView) tile.getChildAt(2)).setText(app.label.toUpperCase(PT));
            tile.setOnClickListener(v -> launch(app.pkg));
            tile.setOnLongClickListener(v -> {
                appInfo(app.pkg);
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return apps.size();
        }
    }

    // ---- inputs ----------------------------------------------------------------------------------------

    /** HDMI, AV and the tuner from TvInputManager, as a bordered list; OK switches. */
    void showInputs() {
        TvInputManager tim = (TvInputManager) activity.getSystemService(Context.TV_INPUT_SERVICE);
        List<TvInputInfo> inputs = new ArrayList<>();
        if (tim != null) {
            try {
                for (TvInputInfo info : tim.getTvInputList()) {
                    if (!info.isHidden(activity)) inputs.add(info);
                }
            } catch (RuntimeException ignored) {
            }
        }
        if (inputs.isEmpty()) {
            Toast.makeText(activity, R.string.not_available, Toast.LENGTH_SHORT).show();
            return;
        }
        Dialog dialog = new Dialog(activity);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setBackground(box(bg, fg, 0));
        list.setPadding(dp(14), dp(14), dp(14), dp(14));
        TextView title = mono(11, true);
        title.setText(R.string.brutal_inputs);
        title.setPadding(0, 0, 0, dp(10));
        list.addView(title);
        for (TvInputInfo info : inputs) {
            CharSequence label = info.loadCustomLabel(activity);
            if (TextUtils.isEmpty(label)) label = info.loadLabel(activity);
            TextView row = button(0, v -> {
                dialog.dismiss();
                openInput(info);
            });
            row.setText(String.valueOf(label).toUpperCase(PT));
            row.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(300), dp(36));
            lp.bottomMargin = dp(8);
            list.addView(row, lp);
        }
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(list);
        dialog.setContentView(scroll);
        Window w = dialog.getWindow();
        if (w != null) w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        dialog.setOnKeyListener((d, keyCode, event) -> {
            if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) d.dismiss();
            return keyCode == KeyEvent.KEYCODE_BACK;
        });
        dialog.setOnDismissListener(d -> inputsDialog = null);
        inputsDialog = dialog;
        dialog.show();
    }

    private void openInput(TvInputInfo info) {
        if (info.getType() == TvInputInfo.TYPE_TUNER && !liveTvPkg.isEmpty()) {
            launch(liveTvPkg);
            return;
        }
        Uri uri = TvContract.buildChannelUriForPassthroughInput(info.getId());
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "vnd.android.cursor.item/channel")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!liveTvPkg.isEmpty()) {
            try {
                activity.startActivity(new Intent(view).setPackage(liveTvPkg));
                return;
            } catch (ActivityNotFoundException | SecurityException ignored) {
            }
        }
        try {
            activity.startActivity(view);
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(activity, R.string.not_available, Toast.LENGTH_SHORT).show();
        }
    }

    // ---- drawing helpers -----------------------------------------------------------------------------

    private GradientDrawable box(int fill, int stroke, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setStroke(dp(3), stroke);
        d.setCornerRadius(radius);
        return d;
    }

    /** The focused tile: accent block with its border, a solid offset shadow behind it. */
    private Drawable shadowed(int s) {
        GradientDrawable shadow = new GradientDrawable();
        shadow.setColor(fg);
        LayerDrawable layers = new LayerDrawable(new Drawable[] {shadow, box(accent, fg, 0)});
        layers.setLayerInset(0, s, s, 0, 0);
        layers.setLayerInset(1, 0, 0, s, s);
        return layers;
    }

    private static Drawable inset(Drawable d, int l, int t, int r, int b) {
        LayerDrawable layers = new LayerDrawable(new Drawable[] {d});
        layers.setLayerInset(0, l, t, r, b);
        return layers;
    }

    private Drawable topRule() {
        GradientDrawable line = new GradientDrawable();
        line.setColor(fg);
        LayerDrawable layers = new LayerDrawable(new Drawable[] {line});
        layers.setLayerHeight(0, dp(3));
        layers.setLayerGravity(0, Gravity.TOP);
        return layers;
    }

    private TextView mono(int sp, boolean bold) {
        TextView t = new TextView(activity);
        t.setTypeface(Typeface.create(Typeface.MONOSPACE, bold ? Typeface.BOLD : Typeface.NORMAL));
        t.setTextSize(sp);
        t.setTextColor(fg);
        t.setLetterSpacing(0.08f);
        return t;
    }

    private TextView black(int sp) {
        TextView t = new TextView(activity);
        t.setTypeface(Typeface.create("sans-serif-black", Typeface.NORMAL));
        t.setTextSize(sp);
        t.setTextColor(fg);
        t.setLetterSpacing(-0.03f);
        return t;
    }

    private int dp(int v) {
        return Math.round(v * density);
    }

    private static int parseColor(String s, int fallback) {
        try {
            return Color.parseColor(s.trim());
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
