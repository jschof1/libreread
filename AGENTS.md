# LibreRead public source

This repository publishes the configurable LibreRead Android app. Never include preconfigured personal builds or personal access credentials in public artifacts.

Never embed personal service credentials or provider keys in this repository, its APKs, logs or release assets. Public builds default to an empty service URL and device credential. Users configure a compatible HTTPS backend in reader settings. Provider keys belong on their server.

Preserve LibreTube's GPL-3.0 licence, notices and credits. Do not add Google login or playback. Saved articles must open without service configuration. Generation requires a configured service and consent.

Builds run in GitHub Actions with at most two Gradle workers. Do not install SDKs or run Gradle on the MacBook. Release APKs are signed and non-debuggable. The production signing secret is required for release builds. Never print its value.

Several workers may share this checkout. Edit only your assigned files. Do not revert others' edits, stage unrelated work, create nested agents, or create worktrees yourself. Never settle or archive threads. Do not send external messages.

For T3 delegation use explicit advertised targets. Allowed providers are claudeAgent with Claude models, codex with Codex models, and opencode only with opencode-go/ model IDs. Use both Claude and Codex for multiple children, and a provider other than the parent for reviews. Report unavailable providers rather than substituting.
