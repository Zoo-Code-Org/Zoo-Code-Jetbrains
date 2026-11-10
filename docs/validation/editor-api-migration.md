# Public editor API migration

Date: 2026-11-10. Candidate plugin: **3.96.0**.

The minimum IDE is **2026.1.5.1**, build **261.27258.136**; the upper bound is
**263.***. Builds use Java 21, Gradle 9.2.1, Kotlin compiler 2.3.20 with
language/API level 2.2, and IntelliJ Platform Gradle Plugin 2.20.0.

## Change

Normal files and preserving diff files now use public `NavigationRequest` and
suspending `NavigationService.navigate` APIs with explicit focus intent. The
service is obtained with `project.serviceAsync` in an active background
coroutine, and the request factory runs under a read action. Offset -1 leaves
the caret unchanged. Editor handles are obtained on the EDT after navigation,
with a project-disposal guard.

The plugin no longer references `FileEditorOpenOptions`, its focus method, or
the internal `FileEditorManagerEx.openFile` overload. There is no reflective
internal API fallback. Exact minimum-SDK source inspection confirms that the
public service uses suspending editor loading inside the platform, avoiding
the synchronous loading loop that previously discarded queued chat keys.

The first native test caught cancellation during blocking navigation-service
initialization. The asynchronous service lookup fixes that failure; the final
candidate passed the native tests below.

## Artifact and verification

ZIP: `jetbrains_plugin/build/distributions/ZooCode-3.96.0.zip`

SHA-256: `e121a635e78cd379669fe00bee5ae711579c2a5e39bf1ae53992ce62697c7852`

Plugin Verifier **1.410** checked this same ZIP. Reports were retained separately
for each IDE, and the ZIP hash remained unchanged after all checks. Verifier CLI
ran on Temurin 21.0.12 with each native IDE's bundled runtime supplied for analysis.
The IC SDK check also passed through Gradle's publication gate on Java 21.

| IDE/build | Verdict | Internal | Scheduled for removal | Deprecated | Experimental |
| --- | --- | ---: | ---: | ---: | ---: |
| IDEA 2026.1.5.1 / IU-261.27258.136 | Compatible | **0** | 7 | 7 | 6 |
| WebStorm 2026.2.1 / WS-262.9437.145 | Compatible | **0** | 7 | 7 | 6 |
| IDEA 2026.3 SDK / IC-263.7307.112 | Compatible | **0** | 7 | 8 | 6 |
| IDEA 2026.3 native / IU-263.7307.112 | Compatible | **0** | 7 | 8 | 6 |

All four reports contain zero binary compatibility problems. These are not
all-warnings-clean results: proxy settings and the terminal customizer remain
follow-up work under issue #18. The experimental findings concern generated
`ToolWindowFactory` compatibility methods. The newer IDE additionally deprecates
the public navigation overload with a data context; it remains binary compatible.

Local reports: `jetbrains_plugin/build/reports/pluginVerifier/final/<IDE-build>/`.
The [machine-readable record](editor-api-migration-results.json) includes the
artifact identity, report paths and native/JVM results.

## Behavior and build checks

- Final focused JVM suites: **129 tests, 123 passed, 6 skipped, 0 failed**.
- Packaged bytecode scanner and its synthetic self-test: passed; the scanner
  explicitly rejects internal editor-option calls.
- 2026.3 terminal dispatch/output smoke: passed against the pinned SDK's binaries.
- Local HTTP provider/relay fixture suites: **18 passed**; no live provider calls.
- Changed GitHub workflows: actionlint passed. Script integration contract checks
  (`integration --dry-run`) passed.

| Native fixture | Files/diffs opened | First-turn keys | Follow-up keys | Result |
| --- | --- | --- | --- | --- |
| IDEA 2026.1.5.1 | 12 / 12 | 23 / 23 | 11 / 11 | PASS |
| IDEA 2026.3 | 12 / 12 | 25 / 25 | 11 / 11 | PASS |

Both streamed conversations completed with preserved chat focus and exact drafts.
Both native IDEA distributions ran their bundled JBR 25.0.4.1; the platform SDK
and plugin bytecode target Java 21. Native evidence copies are retained under
`jetbrains_plugin/build/reports/editor-api-migration/native/`.

The issue-specific WebStorm 2026.2.1 live regression remains in CI with its
original environment approval. The Windows fixture matrix now includes
2026.1.5.1 and retains 2026.2.1. No hosted Windows or live Solheim run was started.

## Remaining validation limits

The local WebStorm desktop fixture loaded Zoo Code but was blocked by the IDE's
**Manage Licenses** dialog in its isolated profile. It was stopped and is recorded
as **BLOCKED_LICENSE**, not a passing desktop test. An activated installation or
existing legitimate activation file is needed to complete it locally; its static
compatibility check passed.

A broader JVM run earlier in migration failed seven tests: two SDK-dependent
assertions were subsequently adapted and passed in the final focused suite;
five `ExtensionDecouplingTest` cases failed during fixture resource initialization.
Those five remain unresolved. The full all-test suite was not rerun on the final
candidate, and no clean-baseline comparison is claimed for those failures.

No publication or issue closure was performed. This work resolves the prioritized
internal editor API findings, not every acceptance criterion of issues #16/#18.
