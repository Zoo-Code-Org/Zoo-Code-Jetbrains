[![JetBrains](https://img.shields.io/badge/JetBrains-Plugin-000000?style=flat&logo=jetbrains&logoColor=white)](https://github.com/Zoo-Code-Org/Zoo-Code-Jetbrains)
[![X](https://img.shields.io/badge/ZooCode-000000?style=flat&logo=x&logoColor=white)](https://x.com/ZooCodeDev)
[![YouTube](https://img.shields.io/badge/YouTube-FF0000?style=flat&logo=youtube&logoColor=white)](https://youtube.com/@roocodeyt?feature=shared)
[![Join Discord](https://img.shields.io/badge/Join%20Discord-5865F2?style=flat&logo=discord&logoColor=white)](https://discord.gg/VxfP4Vx3gX)
[![Join r/ZooCode](https://img.shields.io/badge/Join%20r%2FZooCode-FF4500?style=flat&logo=reddit&logoColor=white)](https://www.reddit.com/r/ZooCode/)
[![GitHub Issues](https://img.shields.io/badge/GitHub-Issues-181717?style=flat&logo=github&logoColor=white)](https://github.com/Zoo-Code-Org/Zoo-Code-Jetbrains/issues)

_Get help fast → [Join Discord](https://discord.gg/VxfP4Vx3gX) • Prefer async? → [Join r/ZooCode](https://www.reddit.com/r/ZooCode/)_

# Zoo Code for JetBrains

> Your AI-Powered Dev Team, Right in Your JetBrains IDE

Zoo Code for JetBrains brings the [Zoo Code](https://github.com/Zoo-Code-Org/Zoo-Code) AI coding assistant to IntelliJ-based IDEs. It runs the Zoo Code extension through a bundled extension host and integrates it with JetBrains editors, terminals, menus, and tool windows.

## We are Zoo Code

Zoo Code continues development of this project after the Roo team wound down active Roo Code work to focus on [Roomote](https://roomote.dev/). We thank the Roo team for everything they built.

The core Zoo Code team includes developers who previously contributed to Roo and care deeply about this plugin. We will continue to make model updates, fix bugs, release features, and listen closely to the community. Join us on [Discord](https://discord.gg/VxfP4Vx3gX), [Reddit](https://www.reddit.com/r/ZooCode/), or [open a pull request or issue](https://github.com/Zoo-Code-Org/Zoo-Code).

_- Zoo Code Team_

## Roo Code to Zoo Code migration

Migrating from Roo Code? See the [Roo → Zoo migration guide](https://docs.zoocode.dev/roo-to-zoo-migration). If you need help during the transition, ask the community on [Discord](https://discord.gg/VxfP4Vx3gX) or [Reddit](https://www.reddit.com/r/ZooCode/).

## What's New in v3.70.0

- **OpenAI GPT-5.6 family** — Sol, Terra, and Luna are available through the OpenAI Codex and OpenAI Native provider paths.
- **Grok 4.5 support** — use xAI's latest flagship model, with improved reasoning-effort formatting for Grok 4 Mini.
- **Kenari provider support** — use an OpenAI-compatible AI gateway billed in Rupiah, with access to Claude, GPT, DeepSeek, GLM, Kimi, and more.
- Access context compaction and context-window progress from the collapsed task header.
- Fixes for terminal output loss and premature task completion on cold terminals.
- Live vision-capability detection for image attachments with Zoo Gateway and Vercel AI Gateway models.
- Dependency and tooling updates.

## What Can Zoo Code Do For You?

- Generate code from natural-language descriptions and specifications
- Adapt through Code, Architect, Ask, Debug, and Custom modes
- Refactor and debug existing code
- Write and update documentation
- Answer questions about your codebase
- Automate repetitive tasks
- Use Model Context Protocol (MCP) servers

## Modes

Zoo Code adapts to how you work:

- **Code Mode:** everyday coding, edits, and file operations
- **Architect Mode:** plan systems, specifications, and migrations
- **Ask Mode:** get fast answers, explanations, and documentation
- **Debug Mode:** trace issues, add logs, and isolate root causes
- **Custom Modes:** create specialized modes for your team or workflow

Learn more: [Using Modes](https://docs.zoocode.dev/basic-usage/using-modes) • [Custom Modes](https://docs.zoocode.dev/advanced-usage/custom-modes)

## Supported IDEs

Zoo Code supports JetBrains IDEs based on the IntelliJ Platform, including:

- IntelliJ IDEA (Ultimate and Community)
- WebStorm
- PyCharm (Professional and Community)
- PhpStorm
- RubyMine
- CLion
- GoLand
- DataGrip
- Rider
- Android Studio **2026.1.4 or newer** (currently available as a preview)

The plugin targets IntelliJ Platform build **233** (JetBrains 2023.3) or newer. Android Studio requires version **2026.1.4 or newer** because that release includes the JCEF support required by Zoo Code. At present, Android Studio 2026.1.4 is available through the preview channel.

## Requirements

### Using the plugin

- A supported JetBrains IDE based on IntelliJ Platform build 233 or newer
- Android Studio 2026.1.4 or newer when using Android Studio; install a preview build until this version reaches the stable channel
- A JCEF-enabled IDE runtime, which Zoo Code requires to render its interface
- Node.js **20.6.0 or newer** available on your `PATH`

### Building from source

- Node.js 20.6.0 or newer and npm
- JDK 17 or newer
- Git
- Bash
- `curl` or `wget`
- `unzip`

## Installation

### Install a release build

1. Download the latest plugin ZIP from the [GitHub Releases](https://github.com/Zoo-Code-Org/Zoo-Code-Jetbrains/releases) page.
2. Open your JetBrains IDE.
3. Go to **Settings/Preferences → Plugins**.
4. Select the gear icon, then **Install Plugin from Disk...**.
5. Choose the downloaded `.zip` file.
6. Restart the IDE when prompted.
7. Open the **Zoo Code JetBrains** tool window from the right sidebar.

### Build and install from source

```bash
git clone https://github.com/Zoo-Code-Org/Zoo-Code-Jetbrains.git
cd Zoo-Code-Jetbrains

# Initialize submodules and install dependencies
./scripts/setup.sh

# Download the latest Zoo Code extension and build the plugin
./scripts/build.sh
```

The installable plugin is written to the `dist/` directory. Install that ZIP through **Settings/Preferences → Plugins → Install Plugin from Disk...**.

## Build from Source

The setup script handles:

- Git LFS initialization
- Git submodule initialization and updates
- Dependency installation
- Patch application
- Development environment setup

The build script then:

- Downloads the latest Zoo Code extension from the VS Code Marketplace
- Builds the Extension Host runtime
- Builds the JetBrains plugin with Zoo Code integrated
- Writes a ready-to-install plugin to the `dist/` directory

### Build options

```bash
# Release build (default)
./scripts/build.sh

# Debug build with source maps
./scripts/build.sh --mode debug

# Custom output directory
./scripts/build.sh --output ./my-build

# Clean build
./scripts/build.sh --clean

# Use an existing downloaded extension
./scripts/build.sh --skip-download

# Skip individual components
./scripts/build.sh --skip-host
./scripts/build.sh --skip-idea

# Verbose output
./scripts/build.sh --verbose

# Show all options
./scripts/build.sh --help
```

### Build output

After a full build, the output directory contains:

- `ZooCode-*.zip` — installable JetBrains plugin
- `extension_host/` — compiled Extension Host runtime
- `debug-resources/` — debug resources, when using debug mode
- `README.md` — generated installation instructions

For more build-system details, see [BUILD.md](BUILD.md).

## Development

### Build individual components

#### Extension Host

```bash
cd extension_host
npm install
npm run build:extension  # Production build
# or
npm run build            # Development build
```

#### JetBrains plugin

```bash
cd jetbrains_plugin

# Production build with Zoo Code
./gradlew -PdebugMode=release -PvscodePlugin=zoo-code buildPlugin

# Debug build
./gradlew -PdebugMode=idea -PvscodePlugin=zoo-code buildPlugin
```

### Run the plugin in a development IDE

```bash
cd jetbrains_plugin
./gradlew runIde
```

### Run tests

```bash
./scripts/test.sh
```

## Architecture

The integration consists of three main components:

1. **JetBrains Plugin (Kotlin):** integrates editors, terminals, commands, menus, webviews, and tool windows with the IDE.
2. **Extension Host (Node.js):** provides a VS Code-compatible runtime for Zoo Code.
3. **Zoo Code Extension:** supplies the AI assistant experience and is downloaded from the VS Code Marketplace during a full build.

The JetBrains plugin communicates with the Extension Host using RPC over Unix-domain sockets on macOS and Linux, or named pipes on Windows.

## Troubleshooting

### Plugin does not load

- Confirm Node.js 20.6.0 or newer is installed with `node --version`.
- Make sure Node.js is available on the IDE process `PATH`.
- Restart the IDE after installing the plugin.
- Review the JetBrains IDE log for Zoo Code or Extension Host errors.
- See [Known Issues](docs/KNOWN_ISSUES.md).

### Build fails

```bash
# Reinitialize the project
./scripts/setup.sh --force

# Verify prerequisites
node --version
java --version

# Ensure scripts are executable
chmod +x scripts/*.sh scripts/lib/*.sh jetbrains_plugin/gradlew

# Clean and rebuild
./scripts/build.sh --clean --verbose
```

### Network errors downloading Zoo Code

Download the [Zoo Code VSIX](https://marketplace.visualstudio.com/items?itemName=ZooCodeOrganization.zoo-code), extract it to `jetbrains_plugin/plugins/zoo-code/extension/`, and build with:

```bash
./scripts/build.sh --skip-download
```

## Resources

- **[Documentation](https://docs.zoocode.dev):** Install, configure, and master Zoo Code.
- **[Zoo Code repository](https://github.com/Zoo-Code-Org/Zoo-Code):** Follow development of the core extension.
- **[YouTube channel](https://youtube.com/@roocodeyt?feature=shared):** Watch tutorials and feature demonstrations.
- **[Discord server](https://discord.gg/VxfP4Vx3gX):** Get real-time help and join community discussions.
- **[Reddit community](https://www.reddit.com/r/ZooCode/):** Share your experience and see what others are building.
- **[JetBrains integration issues](https://github.com/Zoo-Code-Org/Zoo-Code-Jetbrains/issues):** Report integration-specific bugs.
- **[Zoo Code issues](https://github.com/Zoo-Code-Org/Zoo-Code/issues):** Report issues with the core extension.
- **[Feature requests](https://github.com/Zoo-Code-Org/Zoo-Code/discussions/categories/feature-requests?discussions_q=is%3Aopen+category%3A%22Feature+Requests%22+sort%3Atop):** Propose and discuss ideas.

## Contributing

Community contributions are welcome. Read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request.

## License

[Apache License 2.0 © 2026 Zoo Code Org](LICENSE)

---

**Enjoy Zoo Code!** Whether you keep it on a short leash or let it roam autonomously, we cannot wait to see what you build. If you have questions or feature ideas, visit [Reddit](https://www.reddit.com/r/ZooCode/), join [Discord](https://discord.gg/VxfP4Vx3gX), or open an [issue](https://github.com/Zoo-Code-Org/Zoo-Code-Jetbrains/issues). Happy coding!
