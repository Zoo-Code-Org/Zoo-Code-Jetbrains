# Manual Solheim focus smoke test

The source and runner in this directory work without the ignored investigation
fixture. Prerequisites: a JCEF-enabled IntelliJ installation with JBR `javac`
(tested with IU-253.28294.334), `jar`, `unzip`, Xvfb, xdotool, and Openbox.
Supply a complete ZooCode release distribution ZIP. The tested plugin and
embedded extension versions come from that archive, not the harness source.

## Testing Zoo Code 3.96.0

To test the published release, download its JetBrains plugin ZIP and pass it
directly to `PLUGIN_ZIP` on Linux or `-PluginZip` on Windows. This exercises the
released binary, including its bundled extension and runtime. Renaming an older
ZIP or overriding only `pluginVersion` does not produce a 3.96.0 release test.

On a new checkout, run `./scripts/setup.sh` first. To test the current Kotlin
changes with extension 3.96.0, build from the repository root:

```sh
ZOO_EXTENSION_VERSION=3.96.0 ./scripts/build.sh --mode release --clean --verbose
```

The build downloads that extension version and packages this checkout as 3.96.0.
This is a candidate build, distinct from the published release. The build updates
`gradle.properties` and replaces generated build outputs. Run it in a separate
checkout when retaining another build for comparison.

Use the resulting ZIP with the Linux instructions below or the Windows command:

```powershell
./run-windows.ps1 -IdeDir C:/tools/idea -PluginZip C:/build/ZooCode-3.96.0.zip `
  -EvidenceDir C:/temp/focus-3.96.0
```

The credential-free fixture exercises real chat streaming, typing, and editor
opens. Live Solheim testing additionally covers model-driven terminal and diff
operations and needs the provider setup documented below. Gradle tests alone do
not exercise the bundled extension in a running IDE.

The Windows CI workflow pins extension 3.96.0 and builds the checkout;
it has no input for an existing release ZIP. Its IDEA matrix is 2026.1.5.1 and
2026.2.1, so those jobs do not establish compatibility with IDEA 2023.3.8.
Record the exact ZIP, IDE version, and evidence directory when comparing builds.

## Linux live setup

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
PLUGIN_ZIP=/path/to/ZooCode-3.96.0.zip \
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
turns completed, exact draft preservation, textarea and browser-window focus at
every typing sample, streamed partial updates during each typing loop, history
growth, and a changed fixture. Focus is established once before the first typing
loop; the driver never refocuses to repair a failing sample. These assertions
cover [issue #5](https://github.com/Zoo-Code-Org/Zoo-Code-Jetbrains/issues/5).
A sampled focus check cannot detect a blur and recovery between samples; exact
draft comparisons also catch dropped keys when sampled focus stays intact.
No fake input, fake history, or scripted model responses are used.

The profile auto-approves workspace reads and writes. The driver answers a command
request only when the command is one of the three fixture commands, for example
`printf SOLHEIM_FIRST; sleep 5`. Any other command fails the run with
`unexpectedCommand`. The shell still runs as your user, so keep the profile
separate from a real project.
Stop the relay, unset the token, and remove the private key file afterward.
The disposable profile contains the now-expired local token; remove it when
finished with the evidence. Check traces as well as status: a single pass is
insufficient for an intermittent input problem. Hosted Windows Server results
support the fix, but the linked run predates the latest workflow, harness, Kotlin,
and relay changes. Windows 11 remains unverified. See
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

## Windows GitHub Actions regression test (issue #5)

`.github/workflows/windows-chat-focus.yml` builds the plugin from the tested
checkout with Zoo Code extension **3.96.0**, then runs the same Java driver on
`windows-2022` with IntelliJ **2025.3** and **2026.2.1**. It runs for pull requests
that change the harness or relevant implementation, and supports manual dispatch
once the workflow exists on the default branch. It does not publish a plugin.
The IDE archives include JBR/JCEF; their SHA-256 is checked against JetBrains'
published checksum, pinned in the workflow matrix. The run identity records the IDE build, plugin digest, OS,
runner image, and tested commit.

The scenario uses the real bundled extension, React input and history, JCEF,
and native `java.awt.Robot` key events. Only the OpenAI-compatible provider is
simulated by `streaming_fixture.py`, bound to loopback. No API credentials are
needed. Each response emits 100 text deltas over about 25 seconds followed by a
native `attempt_completion` tool call. Two turns must complete, streaming events
and history must grow, and every draft character and sampled focus check must
survive. Windows fixture runs also open 12 fresh Java files, each followed by a diff,
through the production APIs while typing; every file and diff tab must open. Test files are prepared
before typing so filesystem discovery is separate from editor-loading coverage.
The driver never repairs focus during the typing loops.

Before chat testing, a standalone Swing window must receive exact native typing;
the real React textarea must then receive a separate native typing smoke test.
A missing interactive desktop or failed smoke test fails the job. It is not
reported as a pass or a skipped focus regression. Startup browser selection waits
for a loaded, mounted instance after indexing, since startup can retire an earlier
webview.

The `windows-chat-focus-<IDE>-<commit>` artifact contains desktop and chat
screenshots, `run-identity.json`, `evidence.json`, `progress.log`, IDE logs,
provider stream metadata, and `runner-error.txt` on runner failures. A missing
status, IDE crash, timeout, dropped key, or failed focus assertion fails the job.
Sampled focus checks can miss brief transitions between samples; exact text
comparisons still catch dropped keys. Correlated snapshots use JCEF console
and title callbacks; repeating an unanswered observation does not type or focus.
The fixture covers streaming and preserving file and diff opens. Use the live Solheim
battery for terminal creation and real model/tool behavior.

For a local Windows desktop session, start PowerShell (not a Windows service):

```powershell
./run-windows.ps1 -IdeDir C:/tools/idea -PluginZip C:/build/ZooCode.zip `
  -EvidenceDir C:/temp/focus-evidence-1
```

Prerequisites are Python 3, a built **release** plugin zip, and an IntelliJ Windows
ZIP distribution with its bundled JBR. Each run needs a fresh evidence directory.
The runner starts/stops its own fixture and IDE and removes the disposable profile.
Keep the desktop unlocked and avoid interacting with it while tests run. Hosted
`windows-2022` is Windows Server x64, not the reporter's Windows 11/WebStorm setup;
a pass is evidence for the tested configuration only. The same runner can be used
on a self-hosted Windows 11 desktop with IntelliJ.

Fixture HTTP integration tests:

```sh
python3 -m unittest -v test_streaming_fixture.py
```

The fixture can also exercise the conversation on Linux using `run-solheim.sh`
with `HARNESS_FIXTURE=true` (the relay token is then unnecessary). Start
`python3 streaming_fixture.py --log /tmp/focus-provider.jsonl` first, then use
the IDE/plugin/window-manager variables from the manual setup above. Set the
fixture flag through this environment variable, not `JAVA_TOOL_OPTIONS`, which
can display an IDE warning balloon covering the input.

## Maintainer-approved live Solheim testing on Windows

The `live-solheim` job in the Windows focus workflow uses the protected GitHub
Environment **solheim** with the Environment secret **SOLHEIM_API_KEY**. Required
reviewers must approve the exact tested commit before it receives the key. Review
the workflow and harness script changes before approving: PR-head scripts receive
the credential in this job. The environment currently allows self-approval and
administrator bypass; approval does not guarantee an independent review. It
runs only when a PR has the **live-solheim** label, or a manual dispatch selects
`live_solheim`. Manual dispatch is available after this workflow is on the
default branch; the label enables testing this PR before merging.

The job first requires the credential-free matrix to pass. It downloads pinned
WebStorm **2026.2.1**, then runs three fresh two-turn conversations with the real
Solheim `qwen3.8-27b` provider. Each profile requires exact native draft typing and
sampled focus preservation during streaming. Fixed Windows PowerShell scripts
run through the real terminal, sleep while the driver types, read the fixture,
and write completion markers. The model must read `src/Main.java` and use
`apply_diff` to change `throwaway` to `solheim_e2e`.

The driver waits for a non-partial completed-task ask before the next turn,
clicks the input once to begin that deliberate user action, and verifies that
native paste entered the exact follow-up prompt before pressing Enter. It fails
instead of submitting a preserved draft as a prompt. All three expected commands
must also have been approved; no focus is repaired inside the typing loops.

PASS additionally requires all three terminal completion markers, provider tool
calls for `execute_command`, `read_file`, `apply_diff`, and `attempt_completion`,
a changed fixture, and IDE log evidence of a completed diff with
`preserveFocus=true`. Failures stop the sequence; they are not retried or dropped.
`live-validation.json` records the extra coverage checks. A live run can fail
because the model did not perform the required tools, even if focus was healthy;
check the artifacts before classifying a failure as the product bug.

The runner creates a random relay token and a temporary key file in a directory
restricted to the current Windows user. It removes the upstream key from the
child-process environment before launching any processes. The key file is
removed after the relay becomes healthy, and the IDE receives only the local
relay token. The relay retains the upstream key in memory and logs metadata,
not request/response content. Only `SOLHEIM_API_KEY` is a persistent GitHub secret;
`SOLHEIM_KEY_FILE`, `SOLHEIM_PROXY_LOG`, and `SOLHEIM_RELAY_TOKEN` are generated
per run. Do not add them as GitHub secrets.

Hosted runs use Windows Server x64. For the original OS, register an interactive
Windows 11 x64 self-hosted runner with labels `Windows`, `X64`, and
`windows11-x64`, then select `live_runner=windows11-x64` on manual dispatch.
The runner checks Windows client build >= 22000 and x64 architecture before
claiming this configuration. Keep the desktop unlocked and run the agent as a
foreground process rather than a service. The private profile and fixture do
not isolate the OS: use a dedicated test machine. The same test is available
locally:

```powershell
# Set SOLHEIM_API_KEY in this process without putting its value in shell history.
./run-windows.ps1 -IdeDir C:/tools/WebStorm-2026.2.1 `
  -PluginZip C:/build/ZooCode.zip -Provider solheim -RequireWindows11 `
  -DeadlineSeconds 780 -EvidenceDir C:/temp/live-focus-1
```

The linked hosted Windows Server run supports the fix. Close #5 only after
the reporter or a Windows 11 run confirms it; a Windows Server run alone does not
verify Windows 11. Later workflow, harness, Kotlin, and relay changes require
fresh CI validation.

## Local WebStorm validation

The Linux runner selects the native launcher and configuration variables from
`product-info.json`; both IntelliJ and WebStorm are supported. Chromium uses
temporary-file backing to accommodate small container shared-memory mounts. To reuse a
legitimately activated local WebStorm license or evaluation in a disposable
profile, set `HARNESS_LICENSE_FILE` to its existing `webstorm.key`. The runner
copies it with mode 0600 into the private profile, never into evidence. This
requires normal license/trial activation; it does not create or alter a license.

```sh
IDE_DIR=/path/to/WebStorm-262.9437.145 \
PLUGIN_ZIP=/path/to/ZooCode.zip \
HARNESS_LICENSE_FILE=/path/to/private/webstorm.key \
HARNESS_WM_BIN=/path/to/openbox \
HARNESS_FIXTURE=true bash run-solheim.sh
```

Start `streaming_fixture.py` first for the command above. Add
`HARNESS_DIFF_STRESS=true` to exercise the same 12-file/12-diff regression as Windows CI.
For live testing, use the private relay setup and omit `HARNESS_FIXTURE=true`.

Startup now waits for modal/licensing windows to close. The generic startup
handler does not choose “Start” actions such as a quick tour. The AI promotion
is cancelled by its DialogWrapper type. Before typing anything, the driver
clicks an unobscured point near the left edge of the input and checks that native
focus arrived; setup clicks are bounded and recorded as `P_initialFocusClicks`.
There are no setup clicks or dialog handlers inside either draft-preservation
loop. Exact draft and focus assertions are unchanged.

The external Linux agreement helper only targets named IDE agreement windows
and stops when the in-IDE driver starts. It no longer clicks arbitrary windows
by size during a conversation.
