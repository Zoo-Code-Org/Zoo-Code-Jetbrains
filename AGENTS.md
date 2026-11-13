# Repository working rules

## Complete the requested scope

- Treat the linked issue's acceptance criteria and the user's reported findings as the task scope. Account for every item before describing the issue as resolved.
- Do not substitute documentation of limitations, a successful build, passing UI tests, or a Plugin Verifier `Compatible` verdict for the requested API migration.
- If only part of the work is complete, report it as partial. Identify the remaining symbols, evidence, and concrete next steps. Do not close the issue or use closing PR keywords until its acceptance criteria are met.
- Preserve regression coverage tied to specific reported issues, including the product under test. A baseline change does not justify silently replacing WebStorm coverage with IDEA coverage. Flag any supported-range conflict explicitly.
- Investigate practical compatibility implementations and version-specific builds before concluding that the oldest supported SDK prevents a fix. Do not silently raise the minimum IDE or Java version.

## IntelliJ API and publication checks

- Published plugin code must not reference IntelliJ APIs annotated `ApiStatus.Internal` or `IntellijInternalApi`. Do not hide such dependencies through reflection, suppression, verifier exclusions, or copied implementation code that still depends on internal APIs.
- For migrations, consult current JetBrains documentation and source, then confirm API availability, annotations, and JVM signatures in the actual supported SDKs. Upstream master alone does not establish release compatibility.
- Prioritize internal and scheduled-for-removal usages. Preserve the existing chat typing/focus behavior when replacing editor-opening APIs; switching back to synchronous opening requires regression evidence.
- Release verification must fail on internal API usages. If the gate is missing, implement it as part of publication-related API work. Report scheduled-for-removal, deprecated, and experimental findings separately; do not describe a report containing findings as clean.
- Verify the exact distributable ZIP against the declared supported IDE range, including the reported failing target when available. Record plugin version, artifact SHA-256, IDE builds, verifier version, and results. Clearly distinguish saved reports from new runs.
- Run behavior checks appropriate to the migration, including native typing/file/diff/terminal regressions for focus-sensitive changes. Honor explicit environment approval requirements for opt-in live suites.
- If no suitable public API exists, prepare a concrete upstream replacement request and track the unresolved release constraint. Do not send it externally without user authorization. Documentation alone does not complete the migration.
