#!/bin/bash

# Build script with pre-packaged Zoo Code extension
# This script downloads, integrates, and builds the project with Zoo Code pre-installed

set -euo pipefail

# Source common utilities
source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/lib/common.sh"
source "$SCRIPT_DIR/lib/build.sh"

# Script configuration
readonly SCRIPT_NAME="build.sh"
readonly SCRIPT_VERSION="1.0.0"
readonly ZOO_EXTENSION_PUBLISHER="ZooCodeOrganization"
readonly ZOO_EXTENSION_NAME="zoo-code"
# Will be set dynamically to latest version
ZOO_EXTENSION_VERSION=""
ZOO_EXTENSION_URL=""

# Build configuration
BUILD_MODE="${BUILD_MODE:-release}"
DOWNLOAD_DIR=""
EXTENSION_OUTPUT_DIR=""
SKIP_DOWNLOAD=false
SKIP_EXTENSION_HOST_BUILD=false
SKIP_IDEA_BUILD=false
CLEAN_BUILD=false

# Show help for this script
show_help() {
    cat << EOF
$SCRIPT_NAME - Build Zoo Code JetBrains with pre-packaged Zoo Code extension

USAGE:
    $SCRIPT_NAME [OPTIONS]

DESCRIPTION:
    This script automates the entire build process with Zoo Code extension
    pre-packaged and ready to use. It:
    - Downloads the Zoo Code extension from VSCode marketplace
    - Extracts and integrates it into the project
    - Builds all components (VSCode, Extension Host, IDEA plugin)
    - Outputs a complete package with Zoo Code pre-installed

OPTIONS:
    -m, --mode MODE         Build mode: release (default) or debug
    -o, --output DIR        Output directory for final build
    -c, --clean             Clean build (remove all artifacts before building)
    --skip-download         Skip downloading Zoo Code (use existing)
    --skip-host             Skip Extension Host build
    --skip-idea             Skip IDEA plugin build
    -v, --verbose           Enable verbose output
    -n, --dry-run           Show what would be done without executing
    -h, --help              Show this help message

EXAMPLES:
    $SCRIPT_NAME                        # Full build with Zoo Code
    $SCRIPT_NAME --mode debug           # Debug build
    $SCRIPT_NAME --output ./dist        # Custom output directory
    $SCRIPT_NAME --clean                # Clean build from scratch

OUTPUT:
    The script creates a complete build in the output directory with:
    - IDEA plugin (.zip) with Zoo Code pre-integrated
    - Extension Host runtime
    - Debug resources (if debug mode)
    - Ready-to-use configuration

REQUIREMENTS:
    - Node.js 16+ and npm
    - JDK 17+ (for IDEA plugin)
    - Git with submodules initialized
    - Internet connection (for downloading Zoo Code)
    - curl or wget for downloading

EOF
}

# Parse command line arguments
parse_args() {
    while [[ $# -gt 0 ]]; do
        case $1 in
            -m|--mode)
                if [[ -z "${2:-}" ]]; then
                    log_error "Build mode requires a value"
                    exit 3
                fi
                BUILD_MODE="$2"
                shift 2
                ;;
            -o|--output)
                if [[ -z "${2:-}" ]]; then
                    log_error "Output directory requires a value"
                    exit 3
                fi
                EXTENSION_OUTPUT_DIR="$2"
                shift 2
                ;;
            -c|--clean)
                CLEAN_BUILD=true
                shift
                ;;
            --skip-download)
                SKIP_DOWNLOAD=true
                shift
                ;;
            --skip-host)
                SKIP_EXTENSION_HOST_BUILD=true
                shift
                ;;
            --skip-idea)
                SKIP_IDEA_BUILD=true
                shift
                ;;
            -v|--verbose)
                VERBOSE=true
                shift
                ;;
            -n|--dry-run)
                DRY_RUN=true
                shift
                ;;
            -h|--help)
                show_help
                exit 0
                ;;
            -*)
                log_error "Unknown option: $1"
                log_info "Use --help for usage information"
                exit 3
                ;;
            *)
                log_error "Unexpected argument: $1"
                log_info "Use --help for usage information"
                exit 3
                ;;
        esac
    done
    
    # Validate build mode
    if [[ "$BUILD_MODE" != "release" && "$BUILD_MODE" != "debug" ]]; then
        log_error "Invalid build mode: $BUILD_MODE"
        log_info "Valid modes: release, debug"
        exit 3
    fi
}

# Initialize build environment
init_zoo_build_env() {
    log_step "Initializing Zoo Code build environment..."
    
    # Set up directories
    DOWNLOAD_DIR="$PROJECT_ROOT/.zoo-build"
    if [[ -z "$EXTENSION_OUTPUT_DIR" ]]; then
        EXTENSION_OUTPUT_DIR="$PROJECT_ROOT/dist"
    fi
    
    # Make output directory absolute
    EXTENSION_OUTPUT_DIR="$(cd "$PROJECT_ROOT" && mkdir -p "$EXTENSION_OUTPUT_DIR" && cd "$EXTENSION_OUTPUT_DIR" && pwd)"
    
    # Ensure directories exist
    ensure_dir "$DOWNLOAD_DIR"
    ensure_dir "$EXTENSION_OUTPUT_DIR"
    
    # Initialize base build environment
    init_build_env
    
    log_success "Build environment initialized"
    log_info "Download directory: $DOWNLOAD_DIR"
    log_info "Output directory: $EXTENSION_OUTPUT_DIR"
}

# Get latest Zoo Code extension version
get_latest_zoo_version() {
    log_step "Fetching latest Zoo Code extension version..."
    
    local api_url="https://marketplace.visualstudio.com/_apis/public/gallery/extensionquery"
    local query_json='{
        "filters": [{
            "criteria": [
                {"filterType": 7, "value": "ZooCodeOrganization.zoo-code"}
            ]
        }],
        "flags": 439
    }'
    
    # Fetch extension data from marketplace
    local response=""
    if command_exists "curl"; then
        response=$(curl -s -X POST \
            -H "Content-Type: application/json" \
            -H "Accept: application/json;api-version=7.1-preview.1" \
            -d "$query_json" \
            "$api_url" 2>/dev/null) || {
            log_error "Failed to fetch extension information from marketplace"
            exit 2
        }
    elif command_exists "wget"; then
        response=$(wget -q -O - \
            --header="Content-Type: application/json" \
            --header="Accept: application/json;api-version=7.1-preview.1" \
            --post-data="$query_json" \
            "$api_url" 2>/dev/null) || {
            log_error "Failed to fetch extension information from marketplace"
            exit 2
        }
    else
        log_error "No download tool available (curl or wget)"
        exit 4
    fi
    
    # Select the newest stable Marketplace release (never a pre-release build).
    ZOO_EXTENSION_VERSION=$(printf '%s' "$response" | node -e '
        let input = "";
        process.stdin.on("data", chunk => input += chunk);
        process.stdin.on("end", () => {
            const data = JSON.parse(input);
            const versions = data.results?.[0]?.extensions?.[0]?.versions ?? [];
            const stable = versions.find(version =>
                !version.properties?.some(property =>
                    property.key === "Microsoft.VisualStudio.Code.PreRelease" && property.value === "true"
                )
            );
            if (stable) process.stdout.write(stable.version);
        });
    ')

    if [[ -z "$ZOO_EXTENSION_VERSION" ]]; then
        # Fallback to a known working version
        log_warn "Could not fetch latest stable version, using fallback version 3.68.0"
        ZOO_EXTENSION_VERSION="3.68.0"
    fi
    
    # Set the download URL with the version
    ZOO_EXTENSION_URL="https://marketplace.visualstudio.com/_apis/public/gallery/publishers/${ZOO_EXTENSION_PUBLISHER}/vsextensions/${ZOO_EXTENSION_NAME}/${ZOO_EXTENSION_VERSION}/vspackage"
    
    log_success "Found Zoo Code extension version: $ZOO_EXTENSION_VERSION"
    
    # Update gradle.properties with the Zoo Code version
    update_gradle_version
}

# Update gradle.properties with the Zoo Code version
update_gradle_version() {
    local gradle_props="$PROJECT_ROOT/jetbrains_plugin/gradle.properties"
    
    if [[ -f "$gradle_props" ]]; then
        log_info "Updating gradle.properties with version $ZOO_EXTENSION_VERSION..."
        
        # Update the version in gradle.properties
        if [[ "$DRY_RUN" != "true" ]]; then
            # Update the pluginVersion line
            sed -i.tmp "s/^pluginVersion=.*/pluginVersion=${ZOO_EXTENSION_VERSION}/" "$gradle_props"
            rm "${gradle_props}.tmp" 2>/dev/null || true
            
            log_success "Updated plugin version to $ZOO_EXTENSION_VERSION in gradle.properties"
        else
            log_info "[DRY RUN] Would update gradle.properties version to $ZOO_EXTENSION_VERSION"
        fi
    else
        log_warn "gradle.properties not found at: $gradle_props"
    fi
}

# Check required tools
check_requirements() {
    log_step "Checking requirements..."
    
    # Check Node.js
    if ! command_exists "node"; then
        log_error "Node.js is required but not installed"
        exit 4
    fi
    
    # Check npm
    if ! command_exists "npm"; then
        log_error "npm is required but not installed"
        exit 4
    fi
    
    # Check JDK for IDEA plugin
    if [[ "$SKIP_IDEA_BUILD" != "true" ]]; then
        if ! command_exists "java"; then
            log_error "Java is required for IDEA plugin build but not installed"
            exit 4
        fi
    fi
    
    # Check download tool
    if ! command_exists "curl" && ! command_exists "wget"; then
        log_error "Either curl or wget is required for downloading"
        exit 4
    fi
    
    # Check unzip
    if ! command_exists "unzip"; then
        log_error "unzip is required but not installed"
        exit 4
    fi
    
    log_success "All requirements met"
}

# Clean build artifacts
clean_build_artifacts() {
    if [[ "$CLEAN_BUILD" != "true" ]]; then
        return 0
    fi
    
    log_step "Cleaning build artifacts..."
    
    # Clean download directory
    if [[ -d "$DOWNLOAD_DIR" ]]; then
        remove_dir "$DOWNLOAD_DIR"
        ensure_dir "$DOWNLOAD_DIR"
    fi
    
    # Clean output directory
    if [[ -d "$EXTENSION_OUTPUT_DIR" ]]; then
        remove_dir "$EXTENSION_OUTPUT_DIR"
        ensure_dir "$EXTENSION_OUTPUT_DIR"
    fi
    
    # Clean standard build artifacts
    clean_build
    
    log_success "Build artifacts cleaned"
}

# Download Zoo Code extension
download_zoo_extension() {
    if [[ "$SKIP_DOWNLOAD" == "true" ]]; then
        log_info "Skipping Zoo Code download"
        return 0
    fi
    
    log_step "Downloading Zoo Code extension..."
    
    local vsix_file="$DOWNLOAD_DIR/${ZOO_EXTENSION_NAME}-${ZOO_EXTENSION_VERSION}.vsix"
    
    # Check if already downloaded
    if [[ -f "$vsix_file" ]]; then
        log_info "Zoo Code extension already downloaded"
        return 0
    fi
    
    # Download using curl or wget
    if command_exists "curl"; then
        log_info "Downloading from: $ZOO_EXTENSION_URL"
        if [[ "$DRY_RUN" == "true" ]]; then
            log_info "[DRY RUN] Would download Zoo Code extension v$ZOO_EXTENSION_VERSION"
        else
            execute_cmd "curl -L -o '$vsix_file' '$ZOO_EXTENSION_URL'" "download Zoo Code extension"
        fi
    elif command_exists "wget"; then
        log_info "Downloading from: $ZOO_EXTENSION_URL"
        if [[ "$DRY_RUN" == "true" ]]; then
            log_info "[DRY RUN] Would download Zoo Code extension v$ZOO_EXTENSION_VERSION"
        else
            execute_cmd "wget -O '$vsix_file' '$ZOO_EXTENSION_URL'" "download Zoo Code extension"
        fi
    else
        log_error "No download tool available (curl or wget)"
        exit 4
    fi
    
    # Verify download (skip in dry-run mode)
    if [[ "$DRY_RUN" != "true" ]]; then
        if [[ ! -f "$vsix_file" ]]; then
            log_error "Failed to download Zoo Code extension"
            exit 2
        fi
        log_success "Zoo Code extension downloaded: $vsix_file"
    else
        log_info "[DRY RUN] Would verify download of: $vsix_file"
    fi
}

# Extract and prepare Zoo Code extension
prepare_zoo_extension() {
    log_step "Preparing Zoo Code extension..."

    if [[ "$DRY_RUN" == "true" ]]; then
        log_info "[DRY RUN] Would extract and prepare Zoo Code extension"
        return 0
    fi
    
    local vsix_file="$DOWNLOAD_DIR/${ZOO_EXTENSION_NAME}-${ZOO_EXTENSION_VERSION}.vsix"
    local extract_dir="$DOWNLOAD_DIR/zoo-code-extracted"
    # IMPORTANT: The prepareSandbox task expects files in plugins/zoo-code/extension
    local target_dir="$IDEA_BUILD_DIR/plugins/zoo-code/extension"
    
    # Clean extraction directory
    remove_dir "$extract_dir"
    ensure_dir "$extract_dir"
    
    # Check if file is gzipped (marketplace sometimes returns gzipped files)
    local file_type=$(file -b "$vsix_file")
    if [[ "$file_type" == *"gzip"* ]]; then
        log_info "Decompressing gzipped VSIX file..."
        local uncompressed_file="${vsix_file}.uncompressed"
        execute_cmd "gunzip -c '$vsix_file' > '$uncompressed_file'" "decompress VSIX"
        vsix_file="$uncompressed_file"
    fi
    
    # Extract VSIX
    log_info "Extracting Zoo Code extension..."
    execute_cmd "unzip -q '$vsix_file' -d '$extract_dir'" "extract Zoo Code extension"
    
    # Prepare target directory (note: we clear the parent, then create extension subdir)
    remove_dir "$IDEA_BUILD_DIR/plugins/zoo-code"
    ensure_dir "$target_dir"
    
    # Copy extension files
    if [[ -d "$extract_dir/extension" ]]; then
        log_info "Copying Zoo Code extension files..."
        cp -r "$extract_dir/extension"/* "$target_dir/" 2>/dev/null || {
            # Fallback if glob fails
            cp -r "$extract_dir/extension/." "$target_dir/"
        }
        log_success "Zoo Code extension files copied"
    else
        log_error "Extension directory not found in VSIX"
        exit 2
    fi
    
    # Modify package.json if needed for compatibility
    local package_json="$target_dir/package.json"
    if [[ -f "$package_json" ]]; then
        log_info "Adjusting package.json for compatibility..."
        if [[ "$DRY_RUN" != "true" ]]; then
            # Remove type field for CommonJS compatibility if needed
            node -e "
                const fs = require('fs');
                const pkgPath = process.argv[1];
                const pkg = JSON.parse(fs.readFileSync(pkgPath, 'utf8'));
                // Ensure compatibility settings
                if (pkg.type === 'module') {
                    delete pkg.type;
                }
                // Add activation events if missing
                if (!pkg.activationEvents || pkg.activationEvents.length === 0) {
                    pkg.activationEvents = ['onStartupFinished'];
                }
                fs.writeFileSync(pkgPath, JSON.stringify(pkg, null, 2));
                console.log('Adjusted package.json for compatibility');
            " "$package_json" || log_warn "Failed to adjust package.json"
        fi
    fi
    
    log_success "Zoo Code extension prepared at: $target_dir"
}

# Build Extension Host
build_extension_host_component() {
    if [[ "$SKIP_EXTENSION_HOST_BUILD" == "true" ]]; then
        log_info "Skipping Extension Host build"
        return 0
    fi
    
    log_step "Building Extension Host..."
    
    cd "$PROJECT_ROOT/extension_host"
    
    # Install dependencies
    log_info "Installing Extension Host dependencies..."
    execute_cmd "npm install" "Extension Host dependency installation"
    
    # Build
    if [[ "$BUILD_MODE" == "debug" ]]; then
        execute_cmd "npm run build" "Extension Host build (debug)"
    else
        execute_cmd "npm run build:extension" "Extension Host build (release)"
    fi
    
    # Generate production dependencies list for IDEA plugin build
    log_info "Generating production dependencies list..."
    execute_cmd "npm ls --prod --depth=10 --parseable > '$IDEA_BUILD_DIR/prodDep.txt'" "production dependencies list"
    
    # Copy to output
    local host_output="$EXTENSION_OUTPUT_DIR/extension_host"
    ensure_dir "$host_output"
    
    copy_files "$PROJECT_ROOT/extension_host/dist" "$host_output/" "Extension Host dist"
    copy_files "$PROJECT_ROOT/extension_host/package.json" "$host_output/" "Extension Host package.json"
    
    # Copy node_modules for runtime
    if [[ -d "$PROJECT_ROOT/extension_host/node_modules" ]]; then
        log_info "Copying Extension Host dependencies..."
        copy_files "$PROJECT_ROOT/extension_host/node_modules" "$host_output/" "Extension Host node_modules"
    fi
    
    log_success "Extension Host built"
}

# Build IDEA plugin with Zoo Code
build_idea_with_zoo() {
    if [[ "$SKIP_IDEA_BUILD" == "true" ]]; then
        log_info "Skipping IDEA plugin build"
        return 0
    fi
    
    log_step "Building IDEA plugin with Zoo Code..."
    
    cd "$IDEA_BUILD_DIR"
    
    # Ensure Zoo Code is in place
    if [[ ! -d "$IDEA_BUILD_DIR/plugins/zoo-code/extension" ]]; then
        log_error "Zoo Code extension not found in plugins/zoo-code/extension directory"
        exit 2
    fi
    
    # Use gradlew if available
    local gradle_cmd="gradle"
    if [[ -f "./gradlew" ]]; then
        gradle_cmd="./gradlew"
        chmod +x "./gradlew"
    fi
    
    # Set build mode
    local debug_mode="release"
    if [[ "$BUILD_MODE" == "debug" ]]; then
        debug_mode="idea"
    fi
    
    # Clean build directory to ensure fresh build with new version
    log_info "Cleaning build directory for fresh build..."
    execute_cmd "$gradle_cmd clean" "clean build directory"
    
    # Build plugin
    log_info "Building IDEA plugin in $BUILD_MODE mode with version $ZOO_EXTENSION_VERSION..."
    execute_cmd "$gradle_cmd -PdebugMode=$debug_mode -PvscodePlugin=zoo-code -PpluginVersion=$ZOO_EXTENSION_VERSION buildPlugin --info" "IDEA plugin build"
    
    # Find generated plugin
    local plugin_file
    plugin_file=$(find "$IDEA_BUILD_DIR/build/distributions" \( -name "*.zip" -o -name "*.jar" \) -type f | sort -r | head -n 1)
    
    if [[ -z "$plugin_file" ]]; then
        log_error "IDEA plugin build failed - no output file found"
        exit 2
    fi
    
    # Copy to output directory
    copy_files "$plugin_file" "$EXTENSION_OUTPUT_DIR/" "IDEA plugin"
    
    log_success "IDEA plugin built with Zoo Code: $(basename "$plugin_file")"
    
    # Rename the plugin file to use ZooCode name with correct version
    local old_name=$(basename "$plugin_file")
    local new_plugin_name="ZooCode-${ZOO_EXTENSION_VERSION}.zip"
    local new_plugin_path="$EXTENSION_OUTPUT_DIR/$new_plugin_name"
    
    # Remove any existing file with the new name
    if [[ -f "$new_plugin_path" ]]; then
        log_info "Removing existing $new_plugin_name"
        rm -f "$new_plugin_path"
    fi
    
    # Copy (not move) to preserve original for debugging if needed
    log_info "Creating $new_plugin_name from $old_name"
    cp "$plugin_file" "$new_plugin_path"
    
    # Remove the original file with old naming
    rm -f "$plugin_file"
    
    log_success "Plugin renamed to: $new_plugin_name"
}

# Copy debug resources if needed
copy_debug_resources_with_zoo() {
    if [[ "$BUILD_MODE" != "debug" ]]; then
        return 0
    fi
    
    log_step "Copying debug resources..."
    
    local debug_dir="$EXTENSION_OUTPUT_DIR/debug-resources"
    ensure_dir "$debug_dir"
    
    # Copy Zoo Code debug resources
    local zoo_debug="$debug_dir/zoo-code"
    ensure_dir "$zoo_debug"
    
    if [[ -d "$IDEA_BUILD_DIR/plugins/zoo-code" ]]; then
        copy_files "$IDEA_BUILD_DIR/plugins/zoo-code/*" "$zoo_debug/" "Zoo Code debug resources"
    fi
    
    # Copy Extension Host debug resources
    if [[ -d "$PROJECT_ROOT/extension_host/dist" ]]; then
        local host_debug="$debug_dir/extension_host"
        ensure_dir "$host_debug"
        copy_files "$PROJECT_ROOT/extension_host/dist/*" "$host_debug/" "Extension Host debug resources"
    fi
    
    log_success "Debug resources copied"
}

# Create installation instructions
create_installation_instructions() {
    log_step "Creating installation instructions..."
    
    local readme_file="$EXTENSION_OUTPUT_DIR/README.md"
    
    cat > "$readme_file" << EOF
# Zoo Code JetBrains with Zoo Code - Build Output

This directory contains the complete build of Zoo Code JetBrains with Zoo Code pre-packaged.

## Build Information
- Build Date: $(date)
- Build Mode: $BUILD_MODE
- Zoo Code Version: $ZOO_EXTENSION_VERSION

## Contents

### IDEA Plugin
The IDEA plugin file (\`*.zip\` or \`*.jar\`) includes:
- Complete Zoo Code JetBrains integration
- Pre-packaged Zoo Code extension
- Extension Host runtime
- All required dependencies

### Extension Host
The \`extension_host/\` directory contains:
- Compiled Extension Host runtime
- Node.js dependencies
- Configuration files

EOF

    if [[ "$BUILD_MODE" == "debug" ]]; then
        cat >> "$readme_file" << EOF

### Debug Resources
The \`debug-resources/\` directory contains:
- Uncompressed extension files for debugging
- Source maps
- Development configurations

EOF
    fi

    cat >> "$readme_file" << EOF

## Installation Instructions

### Installing the IDEA Plugin

1. Open your JetBrains IDE (IntelliJ IDEA, WebStorm, etc.)
2. Go to **Settings/Preferences** → **Plugins**
3. Click the gear icon ⚙️ → **Install Plugin from Disk...**
4. Select the plugin file from this directory
5. Restart the IDE when prompted

### Verifying Installation

After installation:
1. Look for the Zoo Code JetBrains toolbar in your IDE
2. Click on the Zoo Code icon to open the assistant
3. The extension should be ready to use immediately

### Configuration

The Zoo Code extension is pre-configured and ready to use. You can customize settings through:
- IDE Settings → Zoo Code JetBrains → Extensions
- The extension's own settings panel

## Troubleshooting

If you encounter issues:

1. **Extension not loading**: 
   - Ensure the IDE was restarted after installation
   - Check the IDE logs for error messages

2. **Missing features**:
   - Verify that all files were extracted properly
   - Check that Node.js is installed on your system

3. **Performance issues**:
   - Try the release build if using debug
   - Ensure sufficient memory is allocated to the IDE

## Support

For issues or questions:
- Check the project documentation
- Report issues on the project repository
- Contact the development team

EOF
    
    log_success "Installation instructions created: $readme_file"
}

# Main build process
main() {
    log_info "Starting Zoo Code JetBrains build with Zoo Code extension"
    log_info "Build mode: $BUILD_MODE"
    
    # Parse arguments
    parse_args "$@"
    
    # Initialize environment
    init_zoo_build_env
    
    # Check requirements
    check_requirements
    
    # Get latest Zoo Code version
    get_latest_zoo_version
    
    log_info "Using Zoo Code extension v$ZOO_EXTENSION_VERSION"
    
    # Clean if requested
    clean_build_artifacts
    
    # Download and prepare Zoo Code
    download_zoo_extension
    prepare_zoo_extension
    
    # Build components
    build_extension_host_component
    build_idea_with_zoo
    
    # Copy debug resources
    copy_debug_resources_with_zoo
    
    # Create documentation
    create_installation_instructions
    
    # Final summary
    log_success "Build completed successfully!"
    log_info "Output directory: $EXTENSION_OUTPUT_DIR"
    log_info ""
    log_info "Build artifacts:"
    ls -lh "$EXTENSION_OUTPUT_DIR" 2>/dev/null || true
    log_info ""
    log_info "To install the plugin, see: $EXTENSION_OUTPUT_DIR/README.md"
    
    return 0
}

# Run main function if script is executed directly
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
    main "$@"
fi
