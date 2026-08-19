#!/bin/bash

# Refresh the JetBrains plugin change notes from the latest Zoo Code release,
# then run the normal build with the same command-line arguments.

set -euo pipefail

readonly SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
readonly PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
readonly PLUGIN_XML="$PROJECT_ROOT/jetbrains_plugin/src/main/resources/META-INF/plugin.xml"
readonly ZOO_CODE_REPOSITORY="Zoo-Code-Org/Zoo-Code"
readonly GITHUB_API="https://api.github.com"

DRY_RUN=false
for argument in "$@"; do
    if [[ "$argument" == "-n" || "$argument" == "--dry-run" ]]; then
        DRY_RUN=true
        break
    fi
done

log() {
    printf '[release] %s\n' "$1" >&2
}

die() {
    printf '[release] ERROR: %s\n' "$1" >&2
    exit 1
}

command -v curl >/dev/null 2>&1 || die "curl is required"
command -v node >/dev/null 2>&1 || die "Node.js is required"
[[ -f "$PLUGIN_XML" ]] || die "Plugin descriptor not found: $PLUGIN_XML"

temporary_directory="$(mktemp -d)"
trap 'rm -rf "$temporary_directory"' EXIT

release_json="$temporary_directory/release.json"
markdown_request="$temporary_directory/markdown-request.json"
release_notes_html="$temporary_directory/release-notes.html"

github_headers=(
    -H "Accept: application/vnd.github+json"
    -H "X-GitHub-Api-Version: 2022-11-28"
    -H "User-Agent: Zoo-Code-JetBrains-release-script"
)

if [[ -n "${GITHUB_TOKEN:-}" ]]; then
    github_headers+=(-H "Authorization: Bearer $GITHUB_TOKEN")
fi

release_endpoint="latest"
if [[ -n "${ZOO_EXTENSION_VERSION:-}" ]]; then
    release_endpoint="tags/v${ZOO_EXTENSION_VERSION#v}"
fi

log "Fetching Zoo Code release $release_endpoint"
curl --fail --silent --show-error --location --retry 3 \
    "${github_headers[@]}" \
    "$GITHUB_API/repos/$ZOO_CODE_REPOSITORY/releases/$release_endpoint" \
    --output "$release_json"

release_name="$(node - "$release_json" <<'NODE'
const fs = require("fs");
const release = JSON.parse(fs.readFileSync(process.argv[2], "utf8"));

if (!release.body || !release.body.trim()) {
    console.error("The latest Zoo Code release does not contain release notes");
    process.exit(1);
}

process.stdout.write(release.name || release.tag_name || "latest release");
NODE
)" || die "Unable to read the latest Zoo Code release"

node - "$release_json" "$ZOO_CODE_REPOSITORY" > "$markdown_request" <<'NODE'
const fs = require("fs");
const release = JSON.parse(fs.readFileSync(process.argv[2], "utf8"));

process.stdout.write(JSON.stringify({
    text: release.body,
    mode: "gfm",
    context: process.argv[3],
}));
NODE

log "Rendering release notes for $release_name"
curl --fail --silent --show-error --location --retry 3 \
    "${github_headers[@]}" \
    -H "Content-Type: application/json" \
    --request POST \
    --data-binary "@$markdown_request" \
    "$GITHUB_API/markdown" \
    --output "$release_notes_html"

[[ -s "$release_notes_html" ]] || die "GitHub returned empty rendered release notes"

if [[ "$DRY_RUN" == "true" ]]; then
    log "Dry run: would update change notes in $PLUGIN_XML"
else
    node - "$PLUGIN_XML" "$release_notes_html" <<'NODE'
const fs = require("fs");

const pluginXmlPath = process.argv[2];
const releaseNotesPath = process.argv[3];
const pluginXml = fs.readFileSync(pluginXmlPath, "utf8");
const releaseNotes = fs.readFileSync(releaseNotesPath, "utf8").trim();
const changeNotesPattern = /(<change-notes><!\[CDATA\[)[\s\S]*?(\]\]><\/change-notes>)/;

if (!changeNotesPattern.test(pluginXml)) {
    console.error(`Could not find the <change-notes> CDATA block in ${pluginXmlPath}`);
    process.exit(1);
}

// Keep the descriptor readable and prevent release text from closing its CDATA block.
const indentedNotes = releaseNotes
    .replaceAll("]]>", "]]&gt;")
    .split("\n")
    .map(line => `        ${line}`)
    .join("\n");
const updatedPluginXml = pluginXml.replace(
    changeNotesPattern,
    `$1\n${indentedNotes}\n    $2`,
);

fs.writeFileSync(pluginXmlPath, updatedPluginXml);
NODE

    log "Updated JetBrains change notes from $release_name"
fi

log "Running the build script"
exec "$SCRIPT_DIR/build.sh" "$@"
