# Manual Solheim focus smoke test

The source and runner in this directory work without the ignored investigation
fixture. Prerequisites: a JCEF-enabled IntelliJ installation with JBR `javac`
(tested with IU-253.28294.334), `jar`, `unzip`, Xvfb, xdotool, and Openbox.
Build the ZooCode plugin distribution first. Its Kotlin plugin version is
3.78.0; the embedded Zoo Code extension version is 3.84.0.

Store the Solheim key in a private file outside the repository (mode 0600).
Generate a random per-run local token. Run the `export` line in both shells, with the same value:

```sh
export SOLHEIM_RELAY_TOKEN="$(python3 -c 'import secrets; print(secrets.token_hex(32))')"
SOLHEIM_KEY_FILE=/path/to/private-key-file \
SOLHEIM_PROXY_LOG=/path/to/metadata.jsonl python3 solheim_proxy.py
```

The relay binds only 127.0.0.1:19487 and requires the token as a bearer key.
It rejects Origin headers and unexpected Host headers. Invalid request framing
and malformed JSON receive 400; bodies larger than 8 MiB receive 413. The extension receives
this local token; the upstream key stays outside IDE settings. The relay sends
real requests to https://api.solheim.ai/v1 and logs only metadata and tool names.

In a second shell, export the same `SOLHEIM_RELAY_TOKEN` value. Then run from this directory:

```sh
IDE_DIR=/path/to/intellij \
PLUGIN_ZIP=/path/to/ZooCode-3.78.0.zip \
HARNESS_WM_BIN=/usr/bin/openbox \
HARNESS_HEAP_MB=768 HARNESS_CPU_COUNT=4 DEADLINE_SECONDS=650 \
bash run-solheim.sh
```

The runner compiles `HarnessDriver.java`, installs it and the supplied plugin
zip in a fresh temporary profile, starts Xvfb and the native IDEA launcher,
and prints the evidence directory. It requires PASS for a zero exit status.
An Openbox wrapper may set library/config paths and must accept `--sm-disable`.
The native launcher avoids IDEA's script-launcher warning covering the textarea.

The driver configures `qwen3.8-27b`, submits prompts through the real React
textarea using Robot/clipboard, and types drafts during two model turns.
The model runs commands, reads a file, and applies a diff in the throwaway
workspace. Snapshot replies are correlated by request ID. PASS requires both
turns completed, exact draft preservation, history growth, and a changed fixture.
No fake input, fake history, or scripted model responses are used.

The profile auto-approves workspace reads and writes. The driver answers a command
request only when the command is one of the three fixture commands, for example
`printf SOLHEIM_FIRST; sleep 5`. Any other command fails the run with
`unexpectedCommand`. The shell still runs as your user, so keep the profile
separate from a real project.
Stop the relay, unset the token, and remove the private key file afterward.
The disposable profile contains the now-expired local token; remove it when
finished with the evidence. Check traces as well as status: a pass does not
clear the intermittent first-editor key-delivery gap recorded in
[the validation report](../../../docs/validation/chat-focus-preservation.md).

Relay access tests, without provider calls:

```sh
python3 -m unittest test_solheim_proxy.py
```

## JDK key-delivery probe

`KeyDropProbe.java` is a standalone manual diagnostic. It has no dependency
beyond a headful JDK. A `java.awt.Robot` types keys into a Swing text area. A
`KeyEventDispatcher` counts `KEY_PRESSED`, `KEY_TYPED`, and `KEY_RELEASED`
deliveries. The probe reports keys whose release arrives without a press, odd
press/typed/release combinations, and every focus-owner change.

Run it under a desktop session or Xvfb. A headless JRE package lacks the AWT
natives and exits with `HeadlessException`; use a JDK that contains
`libawt_xawt.so`:

```sh
xvfb-run -a /path/to/headful-jdk/bin/java KeyDropProbe.java control 120 7
xvfb-run -a /path/to/headful-jdk/bin/java KeyDropProbe.java churn 120 7 hidden-frame
```

Arguments: mode (`control` or `churn`), key count, churn interval in keys, and
for churn mode one style: `hidden-frame`, `visible-frame`, `self-refocus`, or
`dispose-without-return`. The `RESULT` line prints the delivery counters, the
release-without-press count, and the focus-owner change count.

Limitations. The probe contains no IDE, plugin, or JCEF code. Its churn styles
move the focus owner, while the recorded IDE loss kept the owner stable. A loss
here is analogous evidence for the release-without-press signature only. It
does not identify the initiating IDE platform call, does not prove a JDK root
cause, and does not clear the intermittent draft loss described in
[the validation report](../../../docs/validation/chat-focus-preservation.md).
Results vary with window manager, keyboard layout, and timing. Compare control
and churn modes on the same host in the same session; a single run decides
nothing.
