# Known Issues

This document records the known issues, limitations, and solutions for the Zoo Code JetBrains project.

The 3.96.0 Marketplace API warnings and JCEF compatibility work are tracked in
[issue #16](https://github.com/Zoo-Code-Org/Zoo-Code-Jetbrains/issues/16).
The additional reupload warnings are tracked in
[issue #18](https://github.com/Zoo-Code-Org/Zoo-Code-Jetbrains/issues/18).

### API migration and supported versions

The current release line targets IntelliJ Platform **261.27258.136 through
263.*** (2026.1.5.1 through the 2026.3 branch), with Java 21. Older IDEs must use
an earlier plugin release. The minimum was raised explicitly to remove the
internal editor-opening dependency without maintaining separate release lines.

Normal-file opens and preserving diff opens now use public `NavigationRequest`
and `NavigationService` APIs with an explicit focus request. A negative offset
preserves the caret and permits virtual diff files. The platform handles
suspending editor loading internally; the plugin does not reference
`FileEditorOpenOptions` or the internal `FileEditorManagerEx.openFile` overload.
There is no reflective internal API fallback. Deliberate diff opens retain the
diff helper path.

The build uses IntelliJ Platform Gradle Plugin 2.x and Java/Kotlin 21 toolchains.
PR and release verification fail on internal API usages as well as binary
compatibility problems. The supported range is bounded to `263.*` pending verification of later IDE branches.

The proxy settings and terminal customizer migrations in #18 remain separate
unfinished work. Raising the baseline makes their modern replacements available;
it does not itself eliminate those deprecations. The old process-based terminal
runner overrides were removed in favor of the startup-options connector hook.

See JetBrains' [public navigation API](https://github.com/JetBrains/intellij-community/blob/idea/261.27258.136/platform/platform-api/src/com/intellij/platform/ide/navigation/NavigationService.kt),
[internal API migration guidance](https://plugins.jetbrains.com/docs/intellij/api-internal.html),
and [multi-version guidance](https://plugins.jetbrains.com/docs/intellij/build-number-ranges.html#targeting-multiple-ide-versions).

Historical verification on 2026-11-09 used the previous 2023.3-based ZIP with
Plugin Verifier 1.410 against IC-233.11799.241 and IC-263.5701.42. Both reported
compatible; the latter still reported 3 internal, 9 scheduled-for-removal, and
4 deprecated usages. Those reports do not validate the current migration.
Current validation is recorded in [the editor API migration report](validation/editor-api-migration.md).
The final candidate reports zero internal usages on 2026.1.5.1, WebStorm
2026.2.1 and the pinned 2026.3 build. Native IDEA typing/file/diff fixtures passed
at both support boundaries. The local WebStorm desktop run is blocked by license
activation; its issue-specific CI regression remains in place. Other API warnings
and the extension-provider fixture failures are still open.

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
