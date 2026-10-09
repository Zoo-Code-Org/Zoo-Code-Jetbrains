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

## Windows CI validation (first run recorded, interrupt unresolved)

The first hosted Windows run failed on the ETX interrupt test. The other five
`WindowsConPtyProcessTest` tests passed.

- Run: <https://github.com/Zoo-Code-Org/Zoo-Code-Jetbrains/actions/runs/37875942108>
  (pull request #10, `fix-intelliJ-compatibility`, `windows-verify` job)
- Tested commit: `12141c5b35b2e314bdc4e8366b435feef017be9c`; the pull request
  merge commit recorded by the job, not the branch head
- Identity output: checkout `D:\a\Zoo-Code-Jetbrains\Zoo-Code-Jetbrains`,
  OS `windows-latest`, JDK temurin 17
- Result: 5 of 6 ConPTY tests passed. Unicode execution, exact exit code,
  resize, five repeated fresh startups, and bounded close passed. The test
  `conPty interrupt byte stops the running pipeline and the shell survives`
  failed at the wait for output after the interrupt: after `interrupt-armed`
  and the ETX byte, the shell produced no prompt and no further output
  within 20s.
- Observed shell setup: interactive Windows PowerShell 5.1 under ConPTY with
  PSReadLine active. The failure snapshot shows PSReadLine syntax-highlight
  escape sequences and the default `PS C:\Users\runneradmin\AppData\Local\Temp>`
  prompt.
- Unresolved: whether the ETX byte reaches PowerShell as a console control
  event in this ConPTY harness. The recorded snapshot cannot separate "the
  interrupt was not delivered" from "the interrupt was delivered but the
  follow-up command was discarded", because the test sent the next command
  immediately after the ETX byte.
- Correction on this branch: the test now installs a counted prompt sentinel
  (`zp<N>`) before the interrupt and waits for a prompt generation newer than
  the last pre-interrupt one before it sends the next command. If no prompt
  returns within 20s, the test fails and records that the interrupt did not
  work; the assertion is not weakened and the test is not skipped.
- The PowerShell 5.1 smoke step was skipped in this run because it had no
  execution condition. It now runs whenever the job is not cancelled, so a
  failed test step no longer removes the smoke evidence.
- Artifact: `windows-pr-results-12141c5b35b2e314bdc4e8366b435feef017be9c-windows-latest-jdk17`

The `windows-verify` job in [`.github/workflows/pr.yml`](../.github/workflows/pr.yml)
records this identity on each run and uploads it as an artifact:

- `Checkout: $env:GITHUB_WORKSPACE`
- `Tested commit: $(git rev-parse HEAD)`; a pull request job can test a merge commit,
  so the recorded value is the validated commit, not the branch head
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
| Input execution, Unicode, exact exit codes, five fresh startups | Added, first run pending | Reported for `05dea49` |
| Multi-command execution inside one live process session | Added, first run pending | None |
| Connector reuse in a real IDE session | None; out of CI scope | Reported for `05dea49` |
| Resize | Process-level test added, first run pending | None |
| Ctrl+C / ETX pipeline interrupt | Unresolved: the first hosted run (37875942108) observed no post-interrupt prompt; the new prompt-observation diagnostic separates a missing interrupt from a lost follow-up command on the next run | None |
| Interactive input | Process-level input only | None |
| Workspace-close cleanup | None | None |
| 263 JCEF matching native bundle | None | None; known gap |

## Local validation

No Windows executor exists in the local Linux workspace, so the Windows job stays
pending until the next hosted run. For the interrupt-diagnostic change, the local
Linux checks ran clean: the six filtered test classes from the `verify` job passed
with 29 tests, and actionlint 1.7.7 reported no findings for `pr.yml` and
`release.yml`. The interrupt test itself needs the hosted Windows runner; the ETX
interrupt question stays open until that run.
