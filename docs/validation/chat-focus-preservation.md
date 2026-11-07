# Chat focus validation

Updated 2026-11-07. [Issue #5](https://github.com/Zoo-Code-Org/Zoo-Code-Jetbrains/issues/5)
remains open pending Windows validation of the diff-loading fix.

## Current finding and fix

A live WebStorm 2026.2.1 run previously lost one draft character near diff
opening (19/20 first-turn characters; 4/4 follow-up). Sampled chat focus stayed
intact, and the trace contained a key release without the corresponding press.
A local regression also reproduced character loss with chat still focused.
A later old-JAR control passed, so reproduction remains intermittent; a single
green run is not sufficient evidence to close the issue.

The synchronous file-editor API waits for loading in a nested event loop that
can consume input before normal keyboard dispatch. Preserving diff opens now
use the suspending API with focus disabled, leaving normal UI dispatch running.
Options use the stable JVM no-argument constructor for IDE binary compatibility.

The rebuilt JetBrains JAR passed the WebStorm regression with the CI extension
and runtime held constant: 12 actual diff tabs opened, both streamed turns
completed, and all 21 first-turn and 11 follow-up draft characters survived.
Live Solheim also passed: 17/17 first-turn and 8/8 follow-up characters,
three terminal commands exiting 0, the requested file edit and preserving diff,
and successful native tool responses from `https://api.solheim.ai/v1` using
`qwen3.8-27b`. All seven provider responses returned HTTP 200.

## Existing fixes

- `TerminalCreationFocusGuard` restores the captured chat component once when
  plugin-owned terminal creation takes focus, provided no user interaction or
  unrelated focus change intervenes. The guard is bounded and test-covered.
- Diff RPC decoding preserves `preserveFocus=true` through the extension host's
  tuple-shaped options. This prevents an unintended editor-focus request.

Deliberate user actions that focus the terminal are expected behavior.

## Test coverage

Recorded runs use the bundled Zoo Code extension 3.84.0.

| Environment | Scenario | Result |
| --- | --- | --- |
| Windows Server x64, IntelliJ 2025.3 and 2026.2.1 | Local streaming-provider fixture | Passed in [CI](https://github.com/Zoo-Code-Org/Zoo-Code-Jetbrains/actions/runs/42154063861) |
| Linux, IntelliJ 2025.3 | Live Solheim commands and diff | Passed; does not clear intermittent failures |
| Linux, WebStorm 2026.2.1 | Streaming fixture plus 12 preserving diff opens | Passed with rebuilt JAR |
| Linux, WebStorm 2026.2.1 | Live Solheim commands and diff | Failed before the fix; passed with rebuilt JAR |

The harness uses native keyboard events in the real React/JCEF input. It requires
exact drafts, sampled input/window focus, streamed history updates, and completed
turns. The follow-up prompt is checked before submission. Live Windows runs also
require terminal completion markers, required provider tool calls, the edited
fixture, and preserving-diff log evidence. Setup failures, timeouts, and missing
coverage fail the test; failed typing samples are never repaired or discarded.

Windows fixture jobs now also exercise preserving diffs through the production
command and verify each tab opened. Files are prepared before typing to separate
filesystem discovery from editor loading. Live Solheim adds terminal and real
model/tool activity; fixture results do not validate those paths.

## Running and reviewing tests

See the [harness README](../../jetbrains_plugin/scripts/ui-harness/README.md) for
local setup, license/trial prerequisites, and commands. The
[Windows workflow](../../.github/workflows/windows-chat-focus.yml) runs fixture
checks on PRs. Live runs require the `live-solheim` label or manual opt-in, plus
approval of the protected `solheim` environment with `SOLHEIM_API_KEY` configured.

Review screenshots, `evidence.json`, focus/key traces, provider metadata, IDE logs,
and `live-validation.json` in workflow artifacts. Confirm the tested commit, IDE
build, OS, and plugin digest, and verify that required tools actually ran before
accepting a pass. Startup dialogs and covered input targets are setup failures,
not evidence of a streaming focus regression.

Windows/WebStorm live validation is still pending. Hosted Windows Server results
do not establish behavior on the reporter's Windows 11 installation; use an
interactive Windows 11 x64 runner or a manual reproduction for that configuration.
Keep #5 open until the input loss is resolved and the required paths are validated.
