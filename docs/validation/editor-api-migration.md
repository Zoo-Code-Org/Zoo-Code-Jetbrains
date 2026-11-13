# Editor API migration validation

Validated on **2026-11-12**, from staged production sources based on `32fb571`.
**Commit-bound verification remains pending**: Git signing requires a passphrase
unavailable in this session; no unsigned override or commit was made.
The maintainer approved **261.27258.136–263.* / Java 21** as the supported range.
The three reported internal editor API usages are removed. Issue #18 remains
partial: proxy/customizer migrations and other API warnings remain open.

## Supported baseline decision

The minimum moves explicitly from **2023.3 / Java 17** to **2026.1.5.1 / Java 21**.
This is an approved compatibility tradeoff, not a claim that older builds are
technically impossible. Released 233 binaries and source establish:

- The current request-based navigation descriptor is absent; the direct
  `Navigatable` overloads are internal.
- The public data-context overload ultimately uses synchronous
  `OpenFileDescriptor.navigate` on EDT. No typing-preserving prototype is claimed.
- Public synchronous file opening exists, but reintroducing it requires fresh
  typing regression evidence because lost chat keys motivated the async path.
- A separate Java 17 build with legacy terminal hooks is technically feasible,
  but needs its own editor implementation, descriptor, verifier and native tests.
  It has not been implemented or tested. One modern build was chosen instead.

Sources: JetBrains' [multiple-version guidance](https://plugins.jetbrains.com/docs/intellij/build-number-ranges.html#targeting-multiple-ide-versions),
[dependency helpers](https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html),
and [released 233 navigation implementation](https://github.com/JetBrains/intellij-community/blob/idea/233.11799.241/platform/lang-impl/src/com/intellij/platform/ide/navigation/impl/IdeNavigationService.kt).
Exact SDK signatures, annotations and binary hashes are retained in the archive.

## Artifact and results

Plugin **3.96.0**: `jetbrains_plugin/build/distributions/ZooCode-3.96.0.zip`.

SHA-256: `d4516add5b27bbbea5e60540d1725328b24b1f17e83e4b9828a23a9e26e5475a`.
Rebuilding after restoring the source mutations retained this exact hash.
Verifier **1.410**, on Temurin **21.0.12**, checked the same ZIP on all targets:

| Target | Verdict | Internal | Scheduled for removal | Deprecated | Experimental | Terminal dispatch/output |
| --- | --- | ---: | ---: | ---: | ---: | --- |
| IU-261.27258.136 / IDEA 2026.1.5.1 | Compatible | 0 | 7 | 7 | 6 | PASS |
| WS-262.9437.145 / WebStorm 2026.2.1 | Compatible | 0 | 7 | 7 | 6 | PASS |
| IC-263.7307.112 / pinned 2026.3 SDK | Compatible | 0 | 7 | 8 | 6 | PASS |

These reports contain warnings. The broad verifier gate rejects internal usages,
compatibility problems and invalid plugins; the targeted bytecode scanner alone
is not evidence of zero internal usages.

- **137 PR-selected JVM tests passed**, zero skips/failures/errors, including
  focus guards, Linux PTY forwarding and shell state. This is not the full suite:
  platform-fixture `ExtensionDecouplingTest` and Windows ConPTY were not selected.
- **18 local HTTP fixture/relay tests passed**, along with the packaged-bytecode
  scanner/self-test, actionlint, integration dry-run and whitespace checks.
- Native IDEA **261 and 263 passed** on bundled JBR **25.0.4.1**: deliberate
  file focus and a typed character, deliberate diff focus, all 12 preserving
  file/diff sequences, and exact typing/focus through both streamed chat turns.
- Reverting all four bulk file-operation `fsPath` conversions or changing the
  distinct navigation-deadline error back to cancellation independently failed
  regression tests. Sources were restored and rebuilt to the same ZIP hash.
  Earlier queue/open/refresh mutation results remain in the historical evidence.

## Review changes

| Findings | Resolution |
| --- | --- |
| 1–3 | Approved baseline decision above; corrected PR scope; WebStorm coverage retained. |
| 4–5 | Native deliberate-focus checks added. Deliberate diff close/show operations now use the shared queue and suspending EDT context. |
| 6, 9 | Terminal dispatch/output proved against 261, 262 and 263 binaries. Both PR/publication workflows verify WebStorm 262 and support both PTY jar layouts. |
| 7–8 | One artifact identity and compressed, auditable evidence; historical candidates are archived separately. |
| 10–11 | Compiled production-routing tests bind file/diff opens to the queue and both File conversions to fsPath; three actual mutations were rejected. URI tests cover drive, UNC, Unix and escaped characters. |
| 12–13 | Non-strict normalization comment corrected. URI identity and Java filesystem conversion remain distinct; pre-existing VFS path uses are unchanged. |
| 14 | Active navigation has a 120-second cooperative deadline. Its expiry raises EditorNavigationTimeoutException; caller cancellation and nested operation timeouts retain cancellation semantics. Blocking IDE code cannot be forcibly interrupted. |
| 15 | Identity/dirty-state tests deserialize production WorkspaceEdit data. File creation/deletion/rename tests cover nullable endpoints, normalization, options and metadata. |
| 16–17 | Stale comments/counts corrected. Unified IDEA dependency replaces legacy IC for 261; the dependency graph resolves `com.jetbrains.intellij.idea:idea:2026.1.5.1`. Full remote-SDK build remains a CI check. |

The desktop overlap is stress coverage; unit tests establish deterministic queue
ordering and compiled routing tests establish that production calls use it.

## Evidence and limits

[Machine-readable results](editor-api-migration-results.json) record the artifact,
source fingerprint, target builds, counts and evidence paths. The
[evidence archive](editor-api-migration-evidence.tar.gz) retains raw verifier
reports, JVM XML, mutation logs, SDK metadata, native predicates and historical
candidate records. To inspect it without repopulating the repository:

```sh
mkdir -p /tmp/zoo-api-evidence
tar -xzf docs/validation/editor-api-migration-evidence.tar.gz -C /tmp/zoo-api-evidence
```

The local package embeds the cached **3.84.0** extension payload and local
extension-host build, with JVM plugin descriptor **3.96.0**. The missing platform
archive was reconstructed from the existing prepared payload. Publication CI must
assemble and verify its own exact ZIP; these results cannot validate a differently
packaged artifact.

Hosted Windows/native WebStorm and protected live-provider checks were not rerun.
Their coverage and approval requirements remain intact. Earlier local WebStorm
activation was blocked by licensing. Fixture conversations do not run model-driven
terminal commands; JVM PTY/dispatch tests are separate evidence, and the protected
live job remains the end-to-end terminal check.

Initial disk-exhausted runs and the initial wrong-PTY-layout smoke were not
counted as passes. Their context and the subsequent fresh results are archived.
The maintainer identified the failing Marketplace check as IDEA **2026.1.5.1**,
**2026-11-09 09:56** (timezone unspecified), Verifier **1.410**, with 3 internal
and 12 removal usages. The original full report/uploaded ZIP hash remain missing.
No publication or issue closure was performed.

## Follow-up spot-check

- All four bulk create/delete/rename File conversions now use `fsPath`; a
  compiled production-routing test covers them, and reverting them fails it.
  The host tests were Linux runs; Windows behavior still needs hosted CI.
- Deadline expiry is a distinct error, while a regression preserves nested
  timeout cancellation. The timeout-as-cancellation mutation was rejected.
- `IU` selects the unified IDEA artifact. The unused Java build dependency was
  removed; the packaged descriptor requires only platform and terminal modules
  (JCEF stays optional). Fresh WebStorm and IC verifier results establish no
  missing binary/API dependency on Ultimate for this ZIP.
- GitHub's live PR title/body already state the 261 / Java 21 baseline. The
  stale-description finding did not match the live PR when checked. The
  existing migration PR retains its associated Windows regression fixes.
- The commit/rebuild checkpoint remains open until signing is resolved. The
  current artifact has complete fresh test/verifier/native evidence, but is
  explicitly not represented as verification of a new commit.
