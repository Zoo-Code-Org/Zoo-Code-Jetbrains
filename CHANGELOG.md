# Changelog

## Unreleased

### Terminal on Windows

- The plugin requests ConPTY explicitly through the public pty4j setter. The JetBrains registry key `terminal.use.conpty.on.windows` is no longer read.
- If the ConPTY native library fails to load, the process falls back to winpty and the session continues.
- Other process start failures propagate to the terminal error handling.
