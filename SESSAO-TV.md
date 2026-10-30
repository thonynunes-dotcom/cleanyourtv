# Sessão TV: instalar a Home brutalista na Hisense

Este arquivo leva o contexto de uma sessão anterior para uma sessão nova do Claude Code, que vai rodar no
computador da pessoa, na mesma rede Wi-Fi da TV. Tudo o que foi decidido e construído até aqui está abaixo.

**Para a pessoa:** abra o Claude Code dentro desta pasta (`cleanyourtv-brutal-home`) e cole o bloco da seção
[Prompt para colar](#prompt-para-colar). O resto do arquivo é para o agente.

---

## 1. A TV

| | |
|---|---|
| Modelo | Hisense **SmartTV 4K FFM** |
| Placa | **lushan** (MediaTek MT9676), confirmada pelo build |
| Sistema | **Google TV 12** (Android 12, API 31) |
| Build | **STT2.230929.001.102** (a lista do repositório foi testada no `.092`, a mesma base) |
| Região | Brasil |
| Lista de limpeza de referência | `examples/hisense-smarttv-4k-ffm-lushan/debloat.txt` |
| Launcher original | `com.google.android.apps.tv.launcherx` |
| App de TV ao vivo / HDMI | `com.mediatek.wwtv.tvcenter` |

O nome "SmartTV 4K FFM" é usado por três placas diferentes (huangshan, songshan e lushan). Confirme com
`getprop ro.product.device` = `lushan` antes de qualquer mudança.

## 2. O que foi construído

Branch **`brutal-home`** do fork `thonynunes-dotcom/cleanyourtv`. Esta pasta é esse branch.

**Home brutalista com clima.** Uma tela inicial desenhada em código, que substitui a Home normal do launcher
quando `brutal_home` está ligado. O mockup aprovado está em https://claude.ai/artifact/PdQJMUMfz8y4iFfuzGdkhW

- Moldura preta grossa, uma cor de destaque (amarelo `#FFE600`) e fundo off-white `#EDEBE4`.
- Relógio enorme, saudação e data em português.
- Bloco de clima: temperatura atual num quadrado amarelo, condição, sensação, máxima/mínima e os
  próximos 3 dias.
- Botões `[ ENTRADAS ]` (lista HDMI e antena), `[ WI-FI ]` e `[ CONFIG ]`.
- Grade de 4 colunas só com os nomes dos apps, numerados. Primeiro vêm os `dock_apps` na ordem, depois o
  resto em ordem alfabética.
- **OK** abre o app, **segurar OK** abre as informações do app e **Home** volta ao primeiro app.
- O clima vem da **Open-Meteo**: sem chave, consultado só com a Home na tela, a cada 30 min (5 min depois de
  uma falha), e a última resposta fica guardada.

**Arquivos:**

| Arquivo | O quê |
|---|---|
| `launcher/app/src/main/java/com/gathin/tvhome/BrutalHome.java` | A tela inteira: layout, clima, apps, entradas |
| `launcher/app/src/main/java/com/gathin/tvhome/MainActivity.java` | Entrega a tela e o ciclo de vida para a `BrutalHome` quando `brutal_home` = true |
| `launcher/app/src/main/res/values/config.xml` | Novas chaves `brutal_*` (no final do arquivo) |
| `launcher/app/src/main/res/values/brutal.xml` | Textos da tela em português |
| `launcher/app/src/main/AndroidManifest.xml` | Permissão `INTERNET`, só para o clima |
| `tests/test_theme_keys_config.py` | `FORK_EXEMPT` para as chaves `brutal_*` |
| `BRUTAL_HOME.md` | Documentação do recurso |

**Configuração atual** (`config.xml`):

| Chave | Valor |
|---|---|
| `brutal_home` | `true` |
| `brutal_latitude`, `brutal_longitude` | `-23.46`, `-46.53` (centro de Guarulhos, SP) |
| `brutal_city` | `Guarulhos · SP` |
| `brutal_accent` | `#FFE600` |
| `brutal_dark` | `false` |

O endereço exato da pessoa **não** está no repositório, de propósito, porque o fork é público. Se ela quiser
o ponto do bairro, ela informa na sessão. Ajuste só no arquivo local e **não faça commit nem push disso**.

**Já verificado (na nuvem, sem TV):** `testReleaseUnitTest lintRelease assembleRelease` sem erros. O APK
tem 518.645 bytes, abaixo do limite de 524.288. `python3 -m unittest discover -s tests` passa: 877 testes,
11 pulados.

**Ainda não verificado:** a tela numa TV real. Os tamanhos foram pensados para 1080p com densidade 2.0
(960×540 dp). Espere ajuste fino depois do primeiro print.

## 3. O que fazer nesta sessão

### 3.1 Preparar o computador
- **JDK 17**: não serve um JRE, e não serve um JDK mais novo. O `build-and-install.sh` explica como ele
  procura o Java.
- **Android SDK**: platform 34 e build-tools 34.0.0. Grave o caminho em `launcher/local.properties` como
  `sdk.dir=...` (esse arquivo não vai para o git).
- **Ferramentas Python**:
  `python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt`
  (no Windows o executável fica em `tools\venv\Scripts\python.exe`).
- Se o Maven Central responder **429 (Too Many Requests)**, tente de novo. Se continuar, crie um
  `~/.gradle/init.gradle` **fora do repositório** apontando `settingsEvaluated` para o espelho
  `https://maven-central.storage-download.googleapis.com/maven2/`.

### 3.2 Conectar na TV
Siga a seção "Connect" do `PROMPT.md`. Nessa família de TVs, ligar a Depuração USB costuma abrir a porta
5555 direto. Se a porta não abrir, use a Depuração sem fio com código de pareamento. Avise a pessoa quando
o aviso **"Permitir depuração USB?"** for aparecer na TV.

### 3.3 Backup antes de qualquer mudança
Siga o "Back up first" do `PROMPT.md`: lista de pacotes, pacotes desativados, `getprop` e configurações,
tudo em `backup/`.

### 3.4 Limpeza (opcional, perguntar antes)
Se a pessoa quiser, siga o `PROMPT.md` com `examples/hisense-smarttv-4k-ffm-lushan/debloat.txt`:
- Compare a lista com o `pm list packages` desta TV e mostre o que falta, o que sobra e o que é regional
  (Globoplay, Ginga, apps da Hisense do Brasil) **antes** de decidir.
- Apps de streaming, Assistente, Chromecast e atualização de firmware entram numa única pergunta em forma
  de checklist. Desative só o que a pessoa **não** marcar.
- Desative em lotes pequenos, com print e reinício entre eles.

### 3.5 Instalar o launcher
```bash
TV_HOST=<ip da tv> launcher/build-and-install.sh
```
O script compila, instala, define o launcher como Home e só então mexe no launcher original. Ele também
aplica a proteção do setupwraith. Leia as primeiras linhas do script antes de rodar. Use `FORCE_HOME=1` ou
`FORCE_SETUPWRAITH=1` só na situação que a própria mensagem do script descreve, e só com o "sim" da pessoa.

Pontos específicos da lushan (do cabeçalho da lista):
- O setupwraith precisa sair para a troca de Home, porque a `RecoveryActivity` dele reivindica HOME. Ele
  também é dono do Wi-Fi salvo. Antes, rode `dumpsys wifi | grep -o 'cname=[^ ]*'`. Se a rede ativa
  pertencer a ele, peça para a pessoa esquecer a rede e conectar de novo nas Configurações.
- Para o botão Input do controle abrir a lista de entradas, pode ser preciso `handle_view_inputs` (veja
  `examples/hisense-smarttv-4k-ffm/config.json`) junto com `INPUT_KEY_SERVICE=1` na instalação. Teste
  primeiro sem.

### 3.6 Conferir e ajustar
- Confirme a Home com `dumpsys activity activities | grep mFocusedApp`.
- Tire prints com `tools/tv.py --screenshot shots/01.png` e mostre para a pessoa.
- Navegue com o D-pad a partir de cada borda (cima, baixo, esquerda, direita): botões → grade → botões.
- Peça para a pessoa apertar os botões **reais** do controle (OK, segurar OK, Home, Input). Uma tecla
  injetada não prova que o botão funciona.
- Confira se o clima aparece. O rodapé mostra "ATUALIZADO hh:mm" ou "AGUARDANDO OPEN-METEO".
- Para ajustar o layout, mexa na `BrutalHome.java` (tamanhos em `dp(...)`, texto em `mono(...)` e
  `black(...)`), recompile e reinstale. Faça commit no branch `brutal-home`, sem dados pessoais.

### 3.7 Fechar o ADB no final
Desligue a Depuração USB e a de rede, revogue as autorizações e reinicie a TV. Depois,
`tools/adb-closed.py <ip da tv>` precisa dizer que a porta está fechada.

## 4. Regras que não mudam

Estas regras valem mesmo com acesso total à rede e ao PC. Elas vêm do `AGENTS.md` e do `PROMPT.md`:

- **Desativar, nunca desinstalar.** Sempre `pm disable-user --user 0`, um pacote por comando. Nada de root,
  nada de flash.
- **Nunca desativar** `com.hisense.tv.smartsense`, `com.vt.tv.smartsense.ui` e `com.vt.tv.aiot`: numa placa
  irmã isso jogou a TV no Recovery. Se aparecer "Can't load Android system", escolha **"Try again"**, nunca
  "Factory data reset".
- **Nunca abrir** pacotes de fábrica ou de teste (`mediatek.factorymenu.ui` e parecidos), nem só para olhar.
- **Nunca desativar** os pacotes centrais do Android listados no `AGENTS.md`. Os scripts recusam, e não
  existe como forçar.
- **Não mexer** no app de TV ao vivo, nas entradas HDMI, no pareamento do controle e do Bluetooth, no
  teclado, na Play Store nem no Play Services.
- **Nada pessoal no git:** sem IP, nome do Wi-Fi, e-mail, número de série ou endereço. `backup/`, `shots/`,
  `work/` e `tools/adbkey/` nunca vão para commit.
- **Pare e explique** se a TV recusar algo (o `pm disable-user` falhar em tudo, ou a instalação falhar na
  verificação). Não tente de novo em loop.
- **Para desfazer:** `tools/restore.sh` com a lista e `com.google.android.apps.tv.launcherx`. Para voltar à
  Home normal do launcher, `brutal_home` = `false`, e reinstale.

## Prompt para colar

> Leia `SESSAO-TV.md` e `AGENTS.md` nesta pasta e siga a seção 3, respeitando a seção 4. Minha TV é a Hisense
> SmartTV 4K FFM (lushan), Google TV 12, build STT2.230929.001.102, e está na mesma rede deste computador.
> Prepare o computador, conecte na TV, faça o backup e me pergunte se quero a limpeza. Depois instale a Home
> brutalista, mostre prints e ajuste o que precisar. No fim, feche o ADB.
> Responda em português.
