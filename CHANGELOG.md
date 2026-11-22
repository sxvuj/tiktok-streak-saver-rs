# Changelog

All notable changes to Streak Saver RS are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/).

## [1.0.0] - 2026-11-22

First release of Streak Saver RS, a Rust rewrite and fork of [Jon2G/TiktokStreakSaver](https://github.com/Jon2G/TiktokStreakSaver).

### Added
- Pure Rust core (`streak-saver-core`) for scheduling, session handling, run planning, message pool and JSON storage.
- Android app (Kotlin and Jetpack Compose) with an Android JNI bridge into the Rust core.
- Foreground service that opens TikTok messages in a headless WebView and sends the streak message to each enabled friend or group.
- Scheduling: run interval of 1 to 23 hours or a fixed daily time, a 1 hour retry when the device is offline, rescheduling after reboot, and an optional early run when the battery gets low (at most once per day).
- Built-in pool of 49 randomized streak phrases.
- Launcher icon: adaptive flame icon with a themed (monochrome) variant for Android 13 and newer.
- Standalone CLI (`streak-saver-cli`) for managing data on a desktop and replaying the parity test cases.
- Release workflow: signed Release APK built by GitHub Actions and attached to the GitHub Release page.

### Changed
- Package name is `com.sxvuj.streaksaver`, so the app installs next to the original app instead of replacing it.
- App name is "Streak Saver RS".
- The built-in update check points at this repository's releases.
- Version numbering starts at 1.0.0 (the upstream app is at 2.2.0).
