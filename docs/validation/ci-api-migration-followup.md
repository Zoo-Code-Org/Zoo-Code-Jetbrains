# CI follow-up, 2026-11-10

Investigated PR checks run [42738107249](https://github.com/Zoo-Code-Org/Zoo-Code-Jetbrains/actions/runs/42738107249)
and Windows focus run [42738106925](https://github.com/Zoo-Code-Org/Zoo-Code-Jetbrains/actions/runs/42738106925)
for commit `c3efb8246ac1622c09ce7fed084f019600856037`.

The 263 verifier failed because the Gradle plugin resolved the relative local IDE
path under the Gradle daemon directory. Both local SDK properties now resolve
against the project directory before being passed to the platform plugin.

Both Windows IDEA fixture jobs passed. The live WebStorm job preserved typing,
completed both turns and approved commands, but failed `P_fixtureChanged`.
Its IDE log shows edits using `C:/.../Main.java` and save requests using
`/C:/.../Main.java`; document holders compare paths literally. Gson bypassed URI
normalization during workspace edit deserialization. Text edits now use the same
URI construction as open/save requests. File-operation URI handling is unchanged.
Regression coverage exercises Windows, canonical Windows, Unix and UNC paths and
is included in both Linux and Windows unit-test jobs. The live disk-change
assertion remains intact.

Local validation of the updated working tree:

- 16 focused JVM tests passed (2 workspace edit tests, 14 editor focus tests).
- Workflow actionlint and `git diff --check` passed.
- Release `verifyPlugin` passed with both SDK properties supplied as relative
  paths, against IC-263.7307.112, using Plugin Verifier 1.410.
- Artifact: `jetbrains_plugin/build/distributions/ZooCode-3.96.0.zip`, SHA-256
  `be85fbf96622e1f9500c4b2dc61c2039efa6b6dfbd043bfaf5bf56fcc1baa0fb`.
- The 263 report contains zero internal usages, 7 scheduled-for-removal usages,
  8 deprecated usages and 6 experimental usages.
- The same ZIP passed compatibility verification on IU-261.27258.136 and
  WS-262.9437.145 with zero internal usages; each retains 7 scheduled-for-removal,
  7 deprecated and 6 experimental usages. Reports are under
  `jetbrains_plugin/build/reports/pluginVerifier/ci-fixes-range/`;
  the 263 report is under `jetbrains_plugin/build/reports/pluginVerifier/ci-fixes/`.

Reports from this ZIP supersede the prior candidate's compatibility results;
the native behavior results in `editor-api-migration.md` describe the earlier ZIP.
The updated ZIP has not had a native desktop or live-provider rerun locally.
The WebStorm save fix still needs confirmation in the protected live CI job
after these changes are pushed and that environment is approved.

## Second CI failure: IDE file-event document identity

Run [42922773411](https://github.com/Zoo-Code-Org/Zoo-Code-Jetbrains/actions/runs/42922773411)
at `8f5c71c373375ca669a8c9bff84c0c1962bdf499` passed both Windows IDEA fixture
jobs, and its separate PR checks passed. WebStorm still failed
`P_fixtureChanged`, with both turns and typing/focus checks passing.
The earlier URI fix was incomplete: this run's bulk edits have `/C:/...` paths,
but IDE file events still register `C:/...` via `URI.file`. Logs show both tab
identities, and no `trySaveDocument` request. The extension's `saveChanges`
conditionally saves only when its document is dirty.

`URI.file` now uses the same path-normalizing constructor as `URI.from` and
`URI.parse`, matching the bundled VS Code URI implementation. Open/create and
post-edit refresh operations use `fsPath` when constructing Java `File` objects.
The new identity regression failed before this fix (two document keys instead
of one). A second regression verifies that editing a document registered by an
IDE file event produces a dirty model change for that original document, rather
than a newly added document. All 18 focused JVM tests pass. These tests remain
in both Linux and Windows PR jobs through the existing `WorkspaceEditTest` entry.

The live test and its disk-change assertion are unchanged. An approved WebStorm
CI rerun after pushing is still required to confirm this fix end to end.

New release artifact validation (Plugin Verifier 1.410, Zoo Code 3.96.0):

- SHA-256: `83296b32da186f8bbd04520def44c9529dc8e431b6653d3c74d169f815963036`.
- IC-263.7307.112: compatible, zero internal usages; 7 scheduled-for-removal,
  8 deprecated and 6 experimental usages.
- IU-261.27258.136 and WS-262.9437.145: compatible, zero internal usages;
  each has 7 scheduled-for-removal, 7 deprecated and 6 experimental usages.
- Local reports: `jetbrains_plugin/build/reports/pluginVerifier/ci-uri-complete/`
  and `jetbrains_plugin/build/reports/pluginVerifier/ci-uri-complete-range/`.
- This artifact has not had a new native desktop or live-provider run locally.

## Solheim diff-open cancellation, 2026-11-11

Run [42932385463](https://github.com/Zoo-Code-Org/Zoo-Code-Jetbrains/actions/runs/42932385463)
at `45678ce5cb44dd39c4ed7440135cae707f1b24dc` passed PR checks and both IDEA
desktop jobs. All three WebStorm live conversations passed the save, typing and
focus predicates. The third failed the additional `preservingDiff` coverage
check: `_workbench.diff` was cancelled during a concurrent normal-file open.
This confirms the previous save fix while exposing a separate navigation race.

Bytecode inspection of both IU-261.27258.136 and WS-262.9437.145 shows that
`IdeNavigationService` uses an `OverflowSemaphore` with one permit and
`DROP_OLDEST`: a newer navigation cancels the previous one. Current
[JetBrains navigation source](https://github.com/JetBrains/intellij-community/blob/master/platform/lang-impl/src/com/intellij/platform/ide/navigation/impl/IdeNavigationService.kt)
also coordinates competing navigation, but the supported binaries establish the
behavior relevant here.

`EditorAndDocManager` now shares one suspending `EditorOpenQueue` between normal
file opens and preserving diff opens. Each navigation finishes before the next
starts. Cancellation remains effective; there are no retries or synchronous
fallbacks. Three queue regressions cover overlapping file/diff opens, cancelling
a waiting caller, and cancelling an active caller. All 21 focused JVM tests pass,
as do actionlint and diff whitespace checks. Queue tests run in Linux and Windows
PR jobs. The native streaming fixture now overlaps a file open with every diff
open and requires both production calls to complete.

The WebStorm live job retains all three conversations and its diff-completion,
save and focus assertions. A fresh approved CI run after pushing is required to
confirm the fix on Windows WebStorm; no live provider calls were made locally.

New release artifact: Zoo Code 3.96.0, SHA-256
`4e5ff090d596c5bc0bdc10a2198f010bdafe2a0f80d288bd5552537cbcd22c91`.
Plugin Verifier 1.410 reports zero internal usages on IU-261.27258.136,
WS-262.9437.145 and IC-263.7307.112. The first two retain 7 scheduled-for-removal,
7 deprecated and 6 experimental usages; 263 retains 7, 8 and 6 respectively.
Reports are under `jetbrains_plugin/build/reports/pluginVerifier/diff-open-queue/`
and `jetbrains_plugin/build/reports/pluginVerifier/diff-open-queue-range/`.

Native deterministic fixture on IU-261.27258.136: PASS, all 12 overlapping
file/diff sequences completed and both turns preserved exact typing and focus.
Evidence: `/tmp/zoo-solheim-e2e.v01Ov2xL/evidence/p/evidence.json`.
Native deterministic fixture on IU-263.7307.112: PASS with the same 12 overlapping
sequences, exact typing and preserved focus through both turns. Evidence:
`/tmp/zoo-solheim-e2e.AH65lm1P/evidence/p/evidence.json`. Both runs used bundled
JBR 25.0.4.1. Evidence copies are retained under
`jetbrains_plugin/build/reports/diff-open-queue/native/`.
