# Windows validation status

This document records the Windows validation state of the `fix-intelliJ-compatibility`
branch. It separates evidence from automated GitHub runner coverage and evidence from
real IDE sessions. It records only evidence that was reported; it does not store logs
that do not exist in this repository.

## Recorded manual evidence (external Windows agent)

Commit `05dea49` on `fix-intelliJ-compatibility` was validated on an external Windows
machine. The requester reported the following results. The logs live on that agent and
are not locally accessible, so this repository cannot reproduce them.

- The two new Kotlin test classes passed: `ShellIntegrationOutputStateTest` and
  `TerminalShellReadinessTest`, 4 tests in total.
- [`smoke/PowerShellIntegrationCheck.ps1`](../jetbrains_plugin/smoke/PowerShellIntegrationCheck.ps1)
  passed under Windows PowerShell 5.1.
- A real IDE session passed: plugin execution, Unicode output, exact exit codes,
  connector reuse, and five repeated terminal startups.

Status: the Windows requirement is **partially validated** by this manual evidence.
The agent checkout path is not recorded in this repository.

Scope note: the reported "connector reuse" is real IDE session reuse. The CI
coverage is narrower: several commands inside one live process session, and
repeated fresh sessions. The CI job never reuses a terminal session inside the
IDE, so the two reuse claims are not interchangeable.

## Windows CI validation (passed 2026-10-09)

The `windows-verify` job in [`.github/workflows/pr.yml`](../.github/workflows/pr.yml)
runs on every pull request. Hosted run logs and artifacts expire, so this
document records the result and not a run link. The job is the living evidence.

- Date: 2026-10-09
- Branch head: `97070c9` on `fix-intelliJ-compatibility` (pull request #10)
- Runner: `windows-latest`, JDK temurin 17
- Result: 6 of 6 `WindowsConPtyProcessTest` tests passed on the ConPTY
  backend. None were skipped. Unicode execution, exact exit code, resize,
  Ctrl+C/ETX interrupt, five repeated fresh startups, and bounded close all
  passed.
- The PowerShell 5.1 smoke script passed.

History: an earlier run failed the interrupt test. The test sent the next
command right after the ETX byte, and no prompt returned. The test now installs
a counted prompt (`zp<N>`) and waits for a new prompt before the next command.
The interrupt then passed, so the ETX byte does reach the shell in this harness.

Each run records its own identity and uploads it as an artifact:

- `Checkout: $env:GITHUB_WORKSPACE`
- `Tested commit: $(git rev-parse HEAD)`; a pull request job can test a merge
  commit, so this value can differ from the branch head
- `Run: $env:GITHUB_SERVER_URL/$env:GITHUB_REPOSITORY/actions/runs/$env:GITHUB_RUN_ID`

## Automated runner coverage

The `windows-verify` job runs on `windows-latest` with JDK 17:

- `RawOutputTtyConnectorTest`: connector stub tests; the Linux-only real-PTY test
  skips on Windows.
- `TerminalPtyBuilderTest`: the explicit ConPTY selection and builder values.
- `ShellIntegrationOutputStateTest` and `TerminalShellReadinessTest`: marker parsing
  and readiness.
- `WindowsConPtyProcessTest`: real ConPTY process tests that start through the
  production `TerminalPtyBuilder` and assert the live process is the ConPTY
  backend, so a silent winpty fallback fails. Teardown runs through one bounded
  cleanup with a force-destroy watchdog, and cleanup problems are reported as
  suppressed failures without masking the original assertion. Coverage: input
  execution with Unicode, exact exit codes, resize through the connector with
  executed width and height, the Ctrl+C/ETX pipeline interrupt with a documented
  pipeline-interruption scope, five repeated fresh startups, and bounded close
  with separate completion, process exit, and reader termination assertions.
  All of it is process-level: several commands inside one live session and
  repeated fresh sessions. The CI job never reuses a connector the way a real
  IDE session does.
- [`smoke/PowerShellIntegrationCheck.ps1`](../jetbrains_plugin/smoke/PowerShellIntegrationCheck.ps1)
  runs through `powershell.exe`, which is Windows PowerShell 5.1, not `pwsh`.
  The step carries `if: ${{ !cancelled() }}`: it also runs after a failed
  Gradle test step, and only a cancelled job skips it. A failed test step
  still fails the job, and the smoke keeps its own exit-code checks.
- Test XML, HTML reports, logs, and the run identity upload with `if: always()`.

## Real IDE coverage (manual only)

The CI job never starts the IDE. Real IDE execution, interactive rendering,
connector reuse inside one IDE session, and workspace-close behavior need a
human session. The native PTY tests above prove process behavior only; they are
not real IDE workspace-close tests.

## Remaining gaps

| Item | Runner coverage | Real IDE evidence |
| --- | --- | --- |
| Input execution, Unicode, exact exit codes, five fresh startups | Passed 2026-10-09 | Reported for `05dea49` |
| Multi-command execution inside one live process session | Passed 2026-10-09 | None |
| Connector reuse in a real IDE session | None; out of CI scope | Reported for `05dea49` |
| Resize | Passed 2026-10-09 | None |
| Ctrl+C / ETX pipeline interrupt | Passed 2026-10-09: the shell returns to a new prompt after ETX | None |
| Interactive input | Process-level input only | None |
| Workspace-close cleanup | None | None |
| 263 JCEF matching native bundle | None | None; known gap |

## Local validation

No Windows executor exists in the local Linux workspace. Windows results come
from the hosted job only. The Linux job runs the same test classes, and the
Windows-only tests skip there.
