# Brutalist Home (this fork)

A Home screen for one TV, a Hisense SmartTV 4K FFM (lushan, Google TV 12, build STT2.230929.001.102): thick
black borders, one accent colour, a huge clock, the outside temperature beside it, then Inputs, Wi-Fi and
Settings buttons and a 4-column grid of app names with no artwork. It is drawn in code by
`launcher/app/src/main/java/com/gathin/tvhome/BrutalHome.java`; `MainActivity` hands it the screen when
`brutal_home` is on, and builds nothing of the normal Home then.

This is a fork feature, not upstream material: it adds the `INTERNET` permission, which the reference launcher
never has (AGENTS.md, "No network in the launcher").

## Settings (`launcher/app/src/main/res/values/config.xml`)

| Key | Default | What it does |
|---|---|---|
| `brutal_home` | `true` | The brutalist Home; `false` brings the normal Home back |
| `latitude`, `longitude` | empty | Where the weather is for, e.g. São Paulo `-23.55` and `-46.63`. Empty: no weather, the footer says so |
| `brutal_city` | empty | The city name shown over the weather (a label only) |
| `brutal_accent` | `#FFE600` | Colour of the temperature block and the focused tile |
| `brutal_dark` | `false` | Light text on near-black instead of black on off-white |

`dock_apps` and `hidden_apps` still apply: the dock apps come first in their order, the rest follow by name.

## Weather

From [Open-Meteo](https://open-meteo.com/) (no account, no key): temperature, feels-like, conditions, today's
high and low, and the next three days. Read only while Home is on screen, every 30 minutes, 5 after a failure;
the last answer is kept on the TV so Home never starts empty. Sent: the latitude and longitude, nothing else.

## Remote

OK opens an app, holding OK opens its App info. `[ ENTRADAS ]` lists the TV's inputs; with
`handle_view_inputs` the remote's Input button opens the same list. Home returns to the first tile.

## Build and install

As for the normal launcher (README, PROMPT.md): `TV_HOST=<tv ip> launcher/build-and-install.sh`.
Undo: set `brutal_home` to `false` and install again, or `tools/restore.sh` with the debloat list.
