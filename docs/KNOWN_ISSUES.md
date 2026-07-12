# Known Issues

This document records the known issues, limitations, and solutions for the Zoo Code JetBrains project.

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

## 3. General Troubleshooting Steps

If you encounter any of the above issues, follow these general troubleshooting steps:

1. **Check IDE Logs**: Look for error messages in the IDE logs that might indicate the root cause
2. **Verify System Requirements**: Ensure your system meets all requirements for both the IDE and the plugin
3. **Update Everything**: Make sure your IDE, the plugin, and all dependencies are up to date
4. **Clean Reinstall**: Try uninstalling and reinstalling the plugin
5. **Check for Conflicts**: Disable other plugins that might conflict with JCEF or Node.js

---

## 4. Reporting New Issues

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
