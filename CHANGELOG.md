# Changelog

## Unreleased

### Terminal on Windows

- The plugin requests ConPTY explicitly through the public pty4j setter. The JetBrains registry key `terminal.use.conpty.on.windows` is no longer read.
- If the ConPTY native library fails to load, the process falls back to winpty and the session continues.
- Other process start failures propagate to the terminal error handling.

### Checkout and release builds without Git LFS

- `jetbrains_plugin/platform.zip` is no longer stored in git, and `.gitattributes` no longer tracks it with Git LFS. Fresh clones, including Windows and WSL checkouts, no longer fail on a missing LFS object.
- Release builds run the existing `genPlatform` task when the archive is missing or invalid and package the generated archive. A stale Git LFS pointer left by an older checkout also triggers regeneration.
- `scripts/setup.sh` no longer requires or installs Git LFS.
- The pull request workflow now also runs when files under `scripts/` change.
