# Known Issues

This document records the known issues, limitations, and solutions for the Zoo Code JetBrains project.

The 3.96.0 Marketplace API warnings and JCEF compatibility work are tracked in
[issue #16](https://github.com/Zoo-Code-Org/Zoo-Code-Jetbrains/issues/16).
The additional reupload warnings are tracked in
[issue #18](https://github.com/Zoo-Code-Org/Zoo-Code-Jetbrains/issues/18).

### API migration and supported versions

The plugin still targets IntelliJ 2023.3 and Java 17. Issue #18 removes the
obsolete OS/PATH helpers, thread-ID logging, diff processor access, and focus
settling callback. Diff tabs are closed through `FileEditorManager.closeFile`
using the editor's public `filesToRefresh` list.

The following migrations remain constrained by that baseline:

- The preserving editor path uses the suspending internal API to avoid lost
  chat keystrokes. Current upstream source offers an experimental public async
  API with completion; 2023.3 has no `requestOpenFile`, and the pinned 2026.3
  build exposes only a fire-and-forget `void requestOpenFile(VirtualFile)`.
  The ordinary synchronous open is deprecated in current
  IDEs as well. Replacing either requires a tested compatibility strategy;
  switching back to synchronous loading would reintroduce the focus regression.
- `ProxySettings` and `ShellExecOptionsCustomizer` are the upstream replacements
  for `HttpConfigurable` and `LocalTerminalCustomizer`, but are absent from
  2023.3. The legacy terminal runner hooks are also needed by the old SDK.
- 2023.3 exposes only the varargs `FileSaverDescriptor` constructor; newer IDEs
  deprecate that signature in favor of the two-argument constructor and extension
  filters. A source-only constructor change cannot span both signatures.

See JetBrains' [current editor API](https://github.com/JetBrains/intellij-community/blob/master/platform/analysis-api/src/com/intellij/openapi/fileEditor/FileEditorManager.kt),
[proxy migration notices](https://github.com/JetBrains/intellij-community/blob/master/platform/platform-api/src/com/intellij/util/net/HttpConfigurable.java),
[terminal customizer migration](https://github.com/JetBrains/intellij-community/blob/master/plugins/terminal/src/org/jetbrains/plugins/terminal/LocalTerminalCustomizer.java),
and [multi-version guidance](https://plugins.jetbrains.com/docs/intellij/build-number-ranges.html#targeting-multiple-ide-versions).
These findings remain open; no reflection or suppression removes the internal API dependency.

PR and release CI run Plugin Verifier against 2023.3 and the pinned 2026.3 EAP,
and retain separate reports plus the plugin ZIP's SHA-256. The verifier gate
currently fails for binary compatibility problems and invalid plugins; warnings
remain visible in the reports. Native typing, terminal, and diff tests remain
required to validate runtime behavior, including the environment-approved live
Solheim suite. The reupload's exact failing IDE/build and artifact checksum
have not yet been supplied, so those CI targets do not reproduce that report by
assumption.

Local verification on 2026-11-09 used Plugin Verifier 1.410 and the same plugin
ZIP for IC-233.11799.241 and IC-263.5701.42. Both reported compatible; the latter
still reported 3 internal, 9 scheduled-for-removal, and 4 deprecated API usages.
The focused JVM suites passed 116 tests with 6 skips, and the 2026.3 terminal
dispatch smoke check passed. Native UI integration tests were not run locally.

## 1. JCEF Related Issues

The current plugin implementation heavily relies on JCEF capabilities. In environments where JCEF cannot function properly, the plugin may fail to load normally.

### 1.1 Runtime Environment Does Not Support JCEF

#### Problem Description
Android Studio's default startup runtime does not include JCEF capabilities, requiring manual configuration to enable it.

#### Affected Platforms
- Android Studio on all platforms

#### How to Enable JCEF in Android Studio


1. **Download a JCEF-Compatible JetBrains Runtime**

   Download a JetBrains Runtime (JBR) with JCEF support:

   👉 https://github.com/JetBrains/JetBrainsRuntime/releases

   Choose a release with:
   - `jbr_jcef` in the name
   - Correct architecture (osx-aarch64, linux-x64, or windows-x64)

   Example:
   ```
   jbr_jcef-17.0.11-osx-aarch64-b1063.2.tar.gz
   ```

   Unpack it somewhere, e.g.:
   ```
   ~/jbr/jbr_jcef
   ```

2. **Launch Android Studio and Open the Runtime Selector**
   1. Start Android Studio.
   2. Press Ctrl+Shift+A (Windows/Linux) or Cmd+Shift+A (macOS) to open "Find Action".
   3. Search for:
      ```
      Choose Boot Java Runtime for the IDE
      ```
   4. Select it and choose the folder where you extracted the JBR (e.g., ~/jbr/jbr_jcef).

3. **Restart Android Studio**

   After selecting the new runtime, Android Studio will prompt you to restart. Confirm, and it will relaunch with the new runtime.

4. **Verify the Runtime**

   Go to Help → About to confirm the IDE is now running with the new JBR. The version should indicate JCEF support (e.g., JetBrains Runtime jbr-17.0.11+7-b1238.56-jcef).

5. **Revert if Needed**

   You can always use the same "Choose Boot Java Runtime for the IDE" action to switch back to the default runtime if needed.

##### Method 2: Using Edit Custom VM Options
1. Go to **Help > Edit Custom VM Options**
2. Add the following line to the `studio64.exe.vmoptions` or `studio.vmoptions` file:
   ```
   -Didea.browser.enable.jcef=true
   ```
3. Restart Android Studio

##### Method 3: Using Edit Custom Properties
1. Go to **Help > Edit Custom Properties**
2. Add the following line to the `idea.properties` file:
   ```
   idea.browser.enable.jcef=true
   ```
3. Restart Android Studio

##### Method 4: Through Settings
1. Go to **File > Settings** (or **Android Studio > Settings** on macOS)
2. Navigate to **Appearance & Behavior > System Settings**
3. Look for JCEF-related options and enable them if available
4. Restart Android Studio

### 1.2 JCEF Cannot Initialize Properly

#### Problem Description
On Linux ARM platforms, JCEF may fail to initialize native processes properly, leading to plugin startup failures.

#### Affected Platforms
- Linux ARM distributions (including ARM64)

#### Solutions
No solution available at this time.

### 1.3 JCEF Is Not Available on IDE 2026.2 or Newer

#### Problem Description

From IntelliJ Platform 2026.2 (build 262), JetBrains packages JCEF as a separate bundled plugin with the id `com.intellij.modules.jcef`. JCEF is no longer part of the IDE platform. When this bundled plugin is not present, the Zoo Code view cannot render.

#### Behavior

The plugin does not crash. It logs the reason and shows a warning notification. The Zoo Code tool window stays empty. The IDE log shows the state in the startup line `JCEF: CLASSES_MISSING`.

#### Affected Platforms
- IDEs based on IntelliJ Platform build 262 or newer without the bundled JCEF plugin

#### Solutions

1. Open **Settings → Plugins → Installed** and search for **JCEF**.
2. Enable the bundled JCEF plugin if it is disabled.
3. Restart the IDE.

If the bundled JCEF plugin is not in the list, this IDE build does not ship JCEF, and the Zoo Code view cannot run in it. IDEs older than 2026.2 get JCEF from the runtime; see section 1.1.

---

## 2. Node Related Issues

### Problem Description
The plugin requires Node.js command line tools to function properly. Node.js executable must be available in the system PATH.

### Affected Platforms
- All platforms

### Solutions

#### Verify Node.js Installation:
1. Open a terminal or command prompt
2. Run the following command:
   ```bash
   node --version
   ```
3. If Node.js is installed, this will display the version number
4. If not, you'll need to install Node.js

#### Install Node.js:
1. Download Node.js from the official website: https://nodejs.org/
2. Choose the LTS (Long Term Support) version for stability
3. Follow the installation instructions for your operating system
4. Ensure the option to add Node.js to PATH is enabled during installation

#### Verify PATH Configuration:
1. After installation, open a new terminal or command prompt
2. Run:
   ```bash
   echo $PATH  # On Linux/macOS
   ```
   or
   ```bash
   echo %PATH%  # On Windows
   ```
3. Check that the Node.js installation directory is included in the PATH

#### Manual PATH Configuration (if needed):
##### On Linux/macOS:
1. Edit your shell profile file (`.bashrc`, `.zshrc`, etc.)
2. Add the following line (adjust the path as needed):
   ```bash
   export PATH="/usr/local/nodejs/bin:$PATH"
   ```
3. Save the file and restart your terminal

##### On Windows:
1. Open System Properties > Environment Variables
2. Under "System variables", find and select "Path"
3. Click "Edit" and add the Node.js installation directory
4. Click OK to save changes

---

## 3. Focus Behavior

Measured triggers, fixes, and remaining limits are recorded in
[the validation report](validation/chat-focus-preservation.md), with test setup
and evidence-review instructions in the linked harness README.

### 3.1 Terminal Creation Focus Restoration

Production terminal creation activates the new terminal and can move Swing
focus from the chat OSR canvas to `JBTerminalPanel`.
`TerminalCreationFocusGuard` captures the exact chat owner before
initialization and restores that owner once after the platform activation
settles. It restores only when focus ended inside the newly created terminal,
chat remains visible, and no user click, key, or Tab traversal intervened. The
guard expires at the callback or 1500 ms after creation; there is no polling or
permanent listener. Instrumented probes retain pre- and post-creation typing
with the owner back on the chat OSR canvas, and pure decision tests are in the
CI allowlist.

Deliberate Terminal user actions are expected platform behavior, not defects.
The first show of the Terminal tool window, stock new-tab actions outside the
plugin-owned creation path, and stock tab selection request terminal focus by
design. The plugin does not wrap these stock actions. The preserving-show and
deliberate-show harness checks for these paths remain required. macOS, native
Windows, and other IDE builds remain untested.

### 3.2 Corrected Test Fixture Items

Two earlier harness failures were test-setup defects, not current product
issues: an IDEA script-launcher warning balloon covered the chat textarea, and
a harness step clicked the browser retired during webview replacement. The
fixture now uses the native launcher, waits for smart mode, and references the
currently mounted browser. No balloon-restoration or permanent focus code was
added to the plugin. Modal dismissal and visibility-only hide/show still do not
automatically activate chat.

### 3.3 Intermittent First-Turn Draft Character Loss

Open pending reporter or Windows 11 confirmation. The fix passed in hosted CI,
but the linked run predates the latest workflow, harness, Kotlin, and relay
changes, which require fresh CI validation. An earlier Windows live run lost one character during a preserving normal-file
open before the diff began. Preserving file and diff opens now share the
suspending file-editor API to avoid the synchronous loading loop. The expanded
regression opens 12 Java files followed by diffs while typing. The expanded
local regression, both Windows fixture jobs, and three fresh Windows live Solheim
conversations passed. The reporter's Windows 11 configuration remains unverified. See
[the validation report](validation/chat-focus-preservation.md).

## 4. General Troubleshooting Steps

If you encounter any of the above issues, follow these general troubleshooting steps:

1. **Check IDE Logs**: Look for error messages in the IDE logs that might indicate the root cause
2. **Verify System Requirements**: Ensure your system meets all requirements for both the IDE and the plugin
3. **Update Everything**: Make sure your IDE, the plugin, and all dependencies are up to date
4. **Clean Reinstall**: Try uninstalling and reinstalling the plugin
5. **Check for Conflicts**: Disable other plugins that might conflict with JCEF or Node.js

---

## 5. Reporting New Issues

If you encounter issues not documented here, please report them with the following information:

1. **Environment Details**:
   - Operating System and version
   - JetBrains IDE and version
   - Node.js version
   - Plugin version

2. **Error Messages**: Full error messages and stack traces

3. **Steps to Reproduce**: Detailed steps to reproduce the issue

4. **Expected vs Actual Behavior**: What you expected to happen and what actually happened

Report issues through the project's issue tracker on GitHub.
