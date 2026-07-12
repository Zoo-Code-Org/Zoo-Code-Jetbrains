# Zoo Code JetBrains Build Scripts

This directory contains the build and maintenance scripts for the Zoo Code JetBrains project. These scripts follow open source conventions and provide a unified interface for project operations.

## Quick Start

### Linux/macOS
```bash
# Initialize development environment
./scripts/setup.sh

# Build the project
./scripts/build.sh

# Run tests
./scripts/test.sh

# Clean build artifacts
./scripts/clean.sh
```

### Windows
```powershell
# Initialize development environment
.\scripts\run.ps1 setup

# Build the project
.\scripts\run.ps1 build

# Run tests
.\scripts\run.ps1 test

# Clean build artifacts
.\scripts\run.ps1 clean
```

## Script Overview

### Main Scripts

| Script | Purpose | Description |
|--------|---------|-------------|
| `run.sh` | Main entry point (Unix) | Unified interface for all operations |
| `run.ps1` | Main entry point (Windows) | PowerShell wrapper for Windows users |
| `setup.sh` | Environment setup | Initialize development environment |
| `build.sh` | Build system | Build VSCode extension and IDEA plugin |
| `clean.sh` | Cleanup utility | Clean build artifacts and temporary files |
| `test.sh` | Test runner | Run tests and validations |

### Library Scripts

| Script | Purpose | Description |
|--------|---------|-------------|
| `lib/common.sh` | Common utilities | Shared functions for logging, file operations, etc. |
| `lib/build.sh` | Build utilities | Build-specific functions and configurations |

## Usage Examples

### Setup and Build
```bash
# First time setup
./scripts/setup.sh --verbose

# Build in debug mode
./scripts/build.sh --mode debug

# Build specific component
./scripts/build.sh idea

# Build with custom output directory
./scripts/build.sh --output ./dist
```

### Testing
```bash
# Run all tests
./scripts/test.sh

# Run specific test type
./scripts/test.sh lint

# Run tests with coverage
./scripts/test.sh --coverage

# Run tests in watch mode
./scripts/test.sh --watch
```

### Cleaning
```bash
# Clean build artifacts only
./scripts/clean.sh

# Clean everything
./scripts/clean.sh all

# Clean dependencies
./scripts/clean.sh deps

# Force clean without confirmation
./scripts/clean.sh --force all
```

## Build Modes

### Release Mode (Default)
- Optimized builds
- Production-ready artifacts
- Minimal debug information

### Debug Mode
- Development builds
- Debug symbols included
- Additional debug resources
- Source maps enabled

```bash
# Debug build
./scripts/build.sh --mode debug

# Or set environment variable
BUILD_MODE=debug ./scripts/build.sh
```

## Environment Variables

| Variable | Description | Default |
|----------|-------------|---------|
| `BUILD_MODE` | Build mode (release/debug) | `release` |
| `VERBOSE` | Enable verbose output | `false` |
| `DRY_RUN` | Show what would be done | `false` |
| `SKIP_VALIDATION` | Skip environment validation | `false` |
| `VSIX_FILE` | Path to existing VSIX file | - |
| `SKIP_BASE_BUILD` | Skip base extension build | `false` |
| `SKIP_IDEA_BUILD` | Skip IDEA plugin build | `false` |

## Project Structure

```
scripts/
├── lib/                    # Shared library functions
│   ├── common.sh          # Common utilities
│   └── build.sh           # Build-specific functions
├── setup.sh               # Environment setup
├── build.sh               # Build system
├── clean.sh               # Cleanup utility
├── test.sh                # Test runner
├── run.sh                 # Main entry point (Unix)
├── run.ps1                # Main entry point (Windows)
└── README.md              # This file
```

## Requirements

### System Requirements
- **Node.js**: 16.0.0 or later
- **Git**: Any recent version
- **Bash**: 4.0 or later (Linux/macOS)
- **PowerShell**: 5.1 or later (Windows)

### Build Tools
- **npm**: Included with Node.js
- **pnpm**: Optional, used if available
- **Gradle**: Required for IDEA plugin
- **unzip**: Required for VSIX extraction

### Optional Tools
- **shellcheck**: For shell script linting
- **WSL**: For Windows users (recommended)

## Platform Support

### Linux/macOS
Full native support with bash scripts.

### Windows
- **PowerShell wrapper**: `run.ps1` provides Windows-friendly interface
- **WSL support**: Automatically detects and uses WSL if available
- **Git Bash support**: Falls back to Git Bash if WSL not available
- **Native PowerShell**: Limited functionality, bash recommended

## Troubleshooting

### Common Issues

#### "Command not found" errors
```bash
# Make scripts executable
chmod +x scripts/*.sh

# Check PATH
echo $PATH
```

#### Node.js version issues
```bash
# Check Node.js version
node --version

# Update Node.js if needed
# Use nvm, n, or download from nodejs.org
```

#### Git submodule issues
```bash
# Reinitialize submodules
git submodule deinit --all -f
git submodule init
git submodule update --recursive
```

#### Build failures
```bash
# Clean and rebuild
./scripts/clean.sh all
./scripts/setup.sh --force
./scripts/build.sh --verbose
```

### Debug Mode

Enable verbose output for debugging:
```bash
# Verbose mode
./scripts/build.sh --verbose

# Dry run mode (show what would be done)
./scripts/build.sh --dry-run

# Debug environment
VERBOSE=true ./scripts/build.sh
```

### Log Files

Build logs are stored in:
- `logs/` directory (if created)
- `test-results/` directory for test reports
- Console output (use `--verbose` for detailed logs)

## Contributing

### Adding New Scripts
1. Follow the existing naming convention
2. Use the common utility functions from `lib/common.sh`
3. Include proper help documentation
4. Add error handling and logging
5. Test on multiple platforms

### Script Guidelines
- Use `set -euo pipefail` for bash scripts
- Source common utilities: `source "$SCRIPT_DIR/lib/common.sh"`
- Implement `--help`, `--verbose`, and `--dry-run` options
- Use consistent logging functions
- Handle errors gracefully
- Provide meaningful exit codes

### Testing Scripts
```bash
# Test script syntax
bash -n scripts/script-name.sh

# Test with shellcheck (if available)
shellcheck scripts/*.sh

# Test functionality
./scripts/test.sh
```

## License

This project follows the same license as the main Zoo Code JetBrains project.

## Support

For issues with the build scripts:
1. Check this README
2. Run with `--verbose` flag for detailed output
3. Check the troubleshooting section
4. Create an issue with detailed error information

---

*Generated by Zoo Code JetBrains build system v1.0.0*
