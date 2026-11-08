# Chat focus validation

Updated 2026-11-08. [Issue #5](https://github.com/Zoo-Code-Org/Zoo-Code-Jetbrains/issues/5)
has a file and diff loading fix that passed in hosted CI. The linked run predates
the latest workflow, harness, Kotlin, and relay changes, which require a fresh
Windows run. Reporter or Windows 11 confirmation remains required before closing #5.

## Current finding and fix

The [Windows live run](https://github.com/Zoo-Code-Org/Zoo-Code-Jetbrains/actions/runs/42384965962/job/128442585155)
with the diff-only fix lost one first-turn character: 28/29 survived, followed by
7/7 in the second turn. Both turns completed, all three commands exited 0, the
file edit succeeded, and sampled chat focus stayed intact.

The missing key falls inside `showTextDocument(..., preserveFocus=true)`, before
the diff call begins. Normal file opens still used the synchronous editor API,
whose loading loop can consume input before normal keyboard dispatch. Preserving
file and diff opens now share the suspending API with focus disabled. Options
use the stable JVM no-argument constructor for IDE binary compatibility.

The regression now exercises 12 normal Java file opens, each followed by a diff,
through the production APIs while typing. Every file and diff tab must open.
The broader fix passed locally on WebStorm 2026.2.1 with the CI extension/runtime
held constant: all 12 files and 12 diffs opened, both streamed turns completed,
and all 23 first-turn and 11 follow-up characters survived. Live Solheim also
passed with 21/21 and 5/5 characters, all three commands exiting 0, the file edit
and preserving diff complete, and all nine provider responses HTTP 200
(`https://api.solheim.ai/v1`, `qwen3.8-27b`). The earlier diff-only runs did not
establish that the normal-file path was safe. Reproduction is intermittent, so a single green run
is insufficient evidence to close the issue.

The [latest hosted run](https://github.com/Zoo-Code-Org/Zoo-Code-Jetbrains/actions/runs/42531095791/job/128887638578)
passed the build, both IntelliJ fixture jobs, and the first live conversation.
The second live conversation stopped at `mistake_limit_reached` after repeated
`update_todo_list` calls. All 108 first-turn characters survived and sampled
focus stayed intact, but required commands and the follow-up were incomplete.
This is a failed live validation, not a passing result for the latest code.
The harness now discourages unrelated todo tools and reports this stop promptly;
these adjustments still need Windows validation.

## Existing fixes

- `TerminalCreationFocusGuard` restores the captured chat component once when
  plugin-owned terminal creation takes focus, provided no user interaction or
  unrelated focus change intervenes. The guard is bounded and test-covered.
- Diff RPC decoding preserves `preserveFocus=true` through the extension host's
  tuple-shaped options. This prevents an unintended editor-focus request.

Deliberate user actions that focus the terminal are expected behavior.

## Test coverage

Recorded runs use the bundled Zoo Code extension 3.84.0. The Windows results below
predate the latest workflow, harness, Kotlin, and relay changes; those changes
require fresh CI validation.

| Environment | Scenario | Result |
| --- | --- | --- |
| Windows Server 2022, IntelliJ 2025.3 and 2026.2.1 | Streaming plus 12 file/diff sequences | Passed in [CI](https://github.com/Zoo-Code-Org/Zoo-Code-Jetbrains/actions/runs/42409470007) |
| Windows Server 2022, WebStorm 2026.2.1 | Three fresh live Solheim conversations | All passed in [CI](https://github.com/Zoo-Code-Org/Zoo-Code-Jetbrains/actions/runs/42409470007/job/128516604245) |
| Linux, IntelliJ 2025.3 | Live Solheim commands and diff | Passed; does not clear intermittent failures |
| Linux, WebStorm 2026.2.1 | Streaming plus 12 file/diff sequences | Passed with broader fix |
| Linux, WebStorm 2026.2.1 | Live Solheim commands and diff | Passed with broader fix |

The harness uses native keyboard events in the real React/JCEF input. It requires
exact drafts, sampled input/window focus, streamed history updates, and completed
turns. The follow-up prompt is checked before submission. Live Windows runs also
require terminal completion markers, required provider tool calls, the edited
fixture, and preserving-diff log evidence. Setup failures, timeouts, and missing
coverage fail the test; failed typing samples are never repaired or discarded.

Windows fixture jobs now exercise preserving files followed by diffs through
the production APIs and verify each tab opened. Files are prepared before typing to separate
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

The linked hosted CI run passed both Windows fixture jobs and three fresh live Solheim
conversations, including native typing in both turns, terminal commands, edits,
and preserving diffs. Hosted Windows Server evidence supports the fix. Close #5
only after the reporter or a Windows 11 run confirms it. Use an interactive
Windows 11 x64 runner or a manual reproduction for that configuration.
