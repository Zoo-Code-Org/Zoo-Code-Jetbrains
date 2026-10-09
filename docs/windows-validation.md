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

## Windows CI validation (pending first run)

Windows CI validation pending; runner checkout path not yet recorded.

The `windows-verify` job in [`.github/workflows/pr.yml`](../.github/workflows/pr.yml)
records this identity on each run and uploads it as an artifact:

- `Checkout: $env:GITHUB_WORKSPACE`
- `Tested commit: $(git rev-parse HEAD)`; a pull request job can test a merge commit,
  so the recorded value is the validated commit, not the branch head
- `Run: $env:GITHUB_SERVER_URL/$env:GITHUB_REPOSITORY/actions/runs/$env:GITHUB_RUN_ID`

Artifact name: `windows-pr-results-<commit>-windows-latest-jdk17`. After the first
green run, paste the identity output and the run link here.

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
| Ctrl+C / ETX pipeline interrupt | Process-level test added with documented scope, first run pending | None |
| Interactive input | Process-level input only | None |
| Workspace-close cleanup | None | None |
| 263 JCEF matching native bundle | None | None; known gap |

## Local validation

No Windows executor exists in the local Linux workspace, so the Windows job stays
pending until the first hosted run. The Linux focused checks and workflow validation
run locally before every change; see the commit history for the latest results.
