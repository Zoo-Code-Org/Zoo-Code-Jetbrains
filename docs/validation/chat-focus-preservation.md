# Chat focus validation summary

Status 2026-10-17. Two plugin-owned focus defects are fixed and test-covered:
terminal creation stealing chat focus, and `preserveFocus` dropped by the diff
RPC decoder. The intermittent first-turn draft loss during real conversations
is **open**: not fixed, and not conclusively attributed. `docs/KNOWN_ISSUES.md`
links this file. Raw evidence paths below are relative to
`jetbrains_plugin/build/ui-harness/evidence/`. Those directories are local,
ignored artifacts kept in the investigation workspace; this file carries the
decisive excerpts for a clean checkout.

## Plugin-owned fix 1: bounded terminal-creation focus restoration

Proven trigger. `TerminalInstance.addToTerminalToolWindow` calls
`TerminalToolWindowManager.newTab`, which inserts and selects the new content
with focus enabled. The platform then moves Swing focus from the chat
`JBCefOsrComponent` to `JBTerminalPanel`, and typed text goes to the terminal.
The bytecode pin from the actual IDE (IU-253.28294.334) is in
`terminal-stack-baseline/terminal-manager-bytecode.txt`:

```text
newTab: iconst_1; iconst_1; invokevirtual createNewTab(..., boolean, boolean)
createNewTab: ToolWindow.activate(runnable, true, true)
selection runnable: ContentManager.setSelectedContent(content, requestFocus)
```

The transition trace in `terminal-stack-baseline/progress.log` records T7
production creation before the fix. The intended post-creation text was
`createpost`; none reached chat.

```text
BATTERY-MARK ts=40771 step=T7-production-preserving-show
FOCUS-TIME ts=40796 prop=focusOwner old=com.intellij.ui.jcef.JBCefOsrComponent new=null
FOCUS-TIME ts=40797 prop=focusOwner old=null new=com.intellij.terminal.JBTerminalPanel
RECORD T7_creationFocusOwner = com.intellij.terminal.JBTerminalPanel
```

Fix. `TerminalCreationFocusGuard` captures the exact Swing chat owner before
terminal initialization. After creation it joins `ToolWindowManager.invokeLater`
and waits once for native focus to settle. It restores the captured component
once, only if focus ended inside the newly created terminal, chat stays visible,
the project is alive, and no user click, key, or Tab traversal intervened.
Focus in an editor or balloon is left alone. Input observation ends at the
callback or 1500 ms after creation. There is no polling loop, permanent
listener, repeated request, or unconditional restoration. Manager shutdown uses
a volatile flag, and refused JCEF registrations are disposed on the EDT outside
the ownership lock. The deliberate-show `ManualFocusGuard` is unchanged except
for documenting this bounded exception.

Ordering evidence. `terminal-inline-callback-diagnostic/idea.log` records the
focus-settled callback before platform activation seeing the chat owner and
returning `restore=false`. `terminal-queued-callback-diagnostic/idea.log`
records the callback after the tool-window queue seeing `JBTerminalPanel` and
returning `restore=true` for T2 and T7, with exact typing probes passing. Later
attempts of that diagnostic died to system OOM
(`terminal-final-oom/`, `terminal-reduced-heap-oom/`); T7 observations after a
renderer kill are invalid, not regressions or passes. Final runs execute the
batteries sequentially and retain intermediate failures.

## Plugin-owned fix 2: diff preserveFocus decoding

The bundled extension passes `{preserveFocus:true}` to `vscode.diff`, but the
extension host wraps the options in a tuple
(`deps/vscode/src/vs/workbench/api/common/extHostApiCommands.ts:459`:
`[ViewColumn.from(...), TextEditorOpenOptions.from(...)]`). The Kotlin decoder
accepted only a map, so the real task logged `Opening diff, preserveFocus=false`
and the focus recorder caught an editor transition at ts=113909 with return to
the chat canvas at ts=113974. `decodeDiffPreserveFocus` now unwraps the tuple's
options member and still accepts direct-map callers. Missing or malformed flags
keep the existing false default. Four unit cases pin true, false, missing
options, and malformed input.

Bundled extension note. The bundled Zoo Code extension 3.84.0 command runner
calls `terminal.show(true)` before `runCommand` (bundle SHA-256
`2fe6fbde2ba7bc67e0c2e3f7eed62246da3cd9554a1ae2b9090faa1a7a0385b7`), so the
changed omitted-argument default does not force terminal focus on that path.
Explicit false or an omitted argument from another caller still requests
terminal focus.

## Test status

The CI allowlist command in `.github/workflows/pr.yml` passes with **104 tests,
0 failures, 0 errors, 0 skipped** (local output `review-3-unit-tests.log`).
Historical 86 and 100 counts describe earlier builds. The local relay tests
pass (`python3 -m unittest test_solheim_proxy.py`; twelve cases after the
run-4 relay hardening). Shell syntax and diff whitespace checks pass. The full
platform-fixture suite was not rerun; its previously documented failures are
not claimed fixed.

## Harness results

Live-run environment: harness IDE IU-253.28294.334, Linux Xvfb with Openbox,
heap 768 MB, 4 CPUs, plugin zip SHA-256
`4d9b34ca46c4bbe0aba7637181e697da4f1bcbb96590d87efdf99c72ab3865e4` unless
noted. Conversation runs use the real bundled extension, the real React
textarea, real tool execution, and Solheim `qwen3.8-27b` through the
loopback-only relay (`scripts/ui-harness/solheim_proxy.py`). No simulated
provider responses or injected chat input are used for the conversation battery.

Corrected runs on plugin-owned paths:

- Mode F: PASS. Typing reaches the input with the OSR canvas focused
  (`typedWithCanvasFocusVerdict=true`) and stops when Swing focus moves
  elsewhere (`keysLostWhenFocusMovedVerdict=true`). Evidence: `f/`.
- Mode T: plugin-owned paths pass. T2 production creation, T2R streaming and
  editor replay, T3 visibility-only show, T7 creation and preserving show, and
  T8 deliberate show. Evidence: `t/`. Stock first-show (T1) and stock
  new-tab/selection (T4) still focus the terminal; see the classification
  below.
- Mode G, corrected final run: PASS. G6 manual click recovery, G7 preserving
  editor bridge with exact owner identity and post-bridge typing, and G8
  disposal typing. G1 reload and G5/G6 raw hide-show without manual refocus
  still do not type; recorded as-is. Evidence: `g/`. The earlier invalid run
  that clicked the retired browser is `g-native-stale-browser/`.
- Mode P, final instrumented run: PASS in 103 seconds. Thirty exact first-turn
  samples and five exact follow-up samples; history grew 21 then 29 messages;
  three real commands exited 0; the diff logged `preserveFocus=true`. Evidence:
  `p/`. This 35/35 pass is historical; the latest conversation run below
  supersedes it.

Deliberate stock user actions, classified separately. The first Terminal
tool-window show and stock tab-selection and new-session paths move focus to
the terminal by platform design. The plugin does not wrap these stock actions,
and this report does not classify them as defects. The preserving-show and
deliberate-show harness checks for these paths remain required.

Fixture corrections, not product issues. The IDEA script-launcher warning
balloon covered the textarea in early runs; the fixture now uses the native
launcher. One G prerequisite clicked the browser retired during webview
replacement; the harness now references the mounted browser. The driver also
waits for smart mode and a showing browser before its setup click. These were
test-setup defects. No balloon-restoration or permanent focus code was added to
the plugin.

## Latest real provider run: FAIL

**FAIL-REAL-CONVERSATION**, 123 seconds, on the same executable plugin
artifact. Both real turns completed; history grew, commands ran, the fixture
changed, and the diff opened with `preserveFocus=true`. Draft preservation
failed: the first turn kept **32 of 33** characters, the follow-up kept **6 of
6**. The first mismatch is sample 16 (`snapshot=19`): 15 actual characters
against 16 expected, with the chat still active. Raw evidence:
`review-3-standalone-p/`; earlier startup failures:
`review-3-standalone-startup-{1,2,3}/`.

No browser remount occurred around the loss: the only mount and repeated-mount
logs are at 01:05:59 and 01:06:00, and the diff opened at 01:06:46.305. The
Swing owner stayed on the chat OSR canvas. The affected sample logs only the
key release in the IDE PRE/POST trace:

```text
E2EAWT PRE ts=1791940006381 when=1791940006326 id=402 key=88 char=120 consumed=false owner=com.intellij.ui.jcef.JBCefOsrComponent@5f8aec10
E2EAWT POST ts=1791940006382 when=1791940006326 id=402 key=88 consumed=false source=com.intellij.ui.jcef.JBCefOsrComponent@5f8aec10
RECORD P_expected_16 = xxxxxxxxxxxxxxxx
```

No matching key press or DOM keydown or input arrived for that sample. The
trace narrows the gap to delivery before the recorded IDE preprocessors. It
does not prove which platform call caused it.

Two earlier real runs on this build each lost one character near the first
file-editor opening (`p-tuple-fix-one-key-loss/`, `p-dom-key-loss/`); DOM
tracing there showed no textarea replacement and no blur. An intermediate
complete run is preserved as FAIL for a stale probe sample
(`p-complete-stale-probe/`), and a partial first attempt as
`p-native-partial-first/`.

## JDK probe: analogous evidence and its limits

`jetbrains_plugin/scripts/ui-harness/KeyDropProbe.java` is a JDK-only probe
with no IDE, plugin, or JCEF code. It types with `java.awt.Robot` and counts
press, typed, and release deliveries. In a local manual run, deliberate focus
churn produced release-without-press **4 times in 120 keys**, while the control
mode produced **0 in 120**. The probe's churn styles change the focus owner;
the IDE trace above kept the owner stable. The probe therefore shows that a
release can arrive without its press under focus churn in a pure JDK and Swing
stack. It does not reproduce the IDE mechanism, does not identify the
initiating IDE platform call, and does not prove a JDK root cause or clear the
product defect. Single runs are nondeterministic; compare control and churn on
the same host in the same session.

## Remaining validation gaps

- The intermittent first-turn draft loss is **open**: no fix, no proven
  initiating call, and later passing runs do not clear it.
- macOS, native Windows, and other IDE builds are untested. The live
  preserving-diff chain is exercised on build 253 only; builds 233/263 are
  untested.
- Live user-click races during the guard window are untested. Controlled guard
  lifecycle tests cover clicks, Tab traversal, expiry, repeated callbacks,
  hidden chat, and disposal before or while queued.
- The full platform-fixture suite was not rerun; its previously documented
  failures are not claimed fixed.

## Reproduction

Build and harness modes T/G/F/P:

```sh
cd jetbrains_plugin
./gradlew buildPlugin -PdebugMode=release -PvscodePlugin=zoo-code --no-configuration-cache
cd build/ui-harness
unzip -oq ../distributions/ZooCode-3.78.0.zip -d plugins-dist
HARNESS_HEAP_MB=768 HARNESS_CPU_COUNT=4 IDE_DIR=$PWD/ide-253 DEADLINE_SECONDS=600 bash bin/run-test.sh t
HARNESS_HEAP_MB=768 HARNESS_CPU_COUNT=4 IDE_DIR=$PWD/ide-253 DEADLINE_SECONDS=600 bash bin/run-test.sh g
HARNESS_HEAP_MB=768 HARNESS_CPU_COUNT=4 IDE_DIR=$PWD/ide-253 DEADLINE_SECONDS=600 bash bin/run-test.sh f
```

The live provider conversation needs the relay and key setup from
`jetbrains_plugin/scripts/ui-harness/README.md`. The JDK probe needs only Xvfb
and a headful JDK:

```sh
cd jetbrains_plugin/scripts/ui-harness
xvfb-run -a /path/to/headful-jdk/bin/java KeyDropProbe.java control 120 7
xvfb-run -a /path/to/headful-jdk/bin/java KeyDropProbe.java churn 120 7 hidden-frame
```

## Evidence index (local, ignored)

Under `jetbrains_plugin/build/ui-harness/evidence/`: `review-3-standalone-p/`
(latest FAIL), `review-3-standalone-startup-{1,2,3}/`, `p/` (historical
instrumented pass), `p-tuple-fix-one-key-loss/`, `p-dom-key-loss/`,
`p-complete-stale-probe/`, `p-native-partial-first/`,
`terminal-stack-baseline/`, `terminal-replay-baseline/`,
`terminal-inline-callback-diagnostic/`,
`terminal-queued-callback-diagnostic/`, `terminal-final-oom/`,
`terminal-reduced-heap-oom/`, `t/`, `g/`, `f/`, `g-native-stale-browser/`,
unit XML in `review-fixes-unit-results/` and
`terminal-creation-unit-results/`, and earlier reports `FOCUS_FIX_REPORT*.md`.
