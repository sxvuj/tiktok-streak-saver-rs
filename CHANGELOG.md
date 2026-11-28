# Changelog

All notable changes to Streak Saver RS are documented here.
The format follows [Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project uses [Semantic Versioning](https://semver.org/).

## [1.2.0] - 2026-11-28

A full redesign in the style of the Fitbit app, editable random messages, and signing and permission clean-up after a Play Protect report.

### Added
- Message editor (Settings > Edit messages, or Today > Edit messages). Switch between one fixed message and a random list. The list can be added to, edited, deleted from, edited as plain text (one message per line) and reset to the 49 built-in phrases. Changes are a draft until Save, with Discard and a prompt before leaving. Messages are trimmed, cut at 280 characters, de-duplicated and capped at 200, and the app says how many were left out.
- The editable list is stored in the Rust core (`random_messages` in settings) and used by background runs, not only the screen. An empty list falls back to your single message.
- SHA-256 checksum file next to every release APK, and the signing certificate fingerprint in the build summary.

### Changed
- New look after the Fitbit (Google Health) app: blue neutral palette with teal and amber accents, thick 270 degree gauge, large rounded cards, pill buttons, scalloped badges and a bottom bar with a pill indicator.
- Google Sans Flex in heavy weights (Black, ExtraBold, Bold) for all text. Licensed under the SIL Open Font License 1.1.
- Tabs are now Today, Friends, History and Settings (were Home, Chats, History and Profile).
- Release APKs are signed with the v1, v2 and v3 schemes and no longer embed the dependency-metadata block.

### Removed
- The `USE_EXACT_ALARM` permission, which is meant for alarm and calendar apps. Exact alarms now need the user-granted `SCHEDULE_EXACT_ALARM` (Settings > Exact alarms). Without it, runs can start a few minutes late.

### Notes
- Play Protect: the cause of the "Harmful app blocked" report on 1.0.0 is not known. See the Play Protect section of the README for what is known, what the changes above do and do not guarantee, and how to appeal.

## [1.1.1] - 2026-11-24

First version published on the GitHub Releases page, so the in-app update check now has something to find.

### Fixed
- The keyboard covered the Message field and the content under it. Android 15 no longer resizes the window for the keyboard, so every tab now makes room for it itself.
- With random messages on, Home still showed an editable text box that was ignored. It now shows a switch and an example phrase (with "Show another") instead, and the box only appears when random messages are off.
- A blank message was silently ignored while the box looked empty. The box now says so, and the last saved message keeps being used until you type a new one.
- The update check reported a failure when the repository had no release yet. It now says you have the latest version, and failures name the reason (for example GitHub rate limiting).

### Changed
- Home has a Random messages switch next to the message, in addition to the one in Profile.

## [1.1.0] - 2026-11-23

The app is now usable on its own: the placeholder screens are replaced and the background service records what it does.

### Added
- Home, Chats, History and Profile screens in Material 3 with light and dark themes.
- Friends and groups: search, per-chat switch, add, edit, delete, Turn all on / off, and import or export of the list as JSON. The importer accepts differently cased keys, wrapped lists and several date formats.
- History: success ring, run log with per-chat failures, Retry failed, Export log and Clear. Skipped runs (offline, logged out, page did not load) are recorded with the reason.
- Live run progress and a Stop button on Home and in the notification.
- Update check on Home and Profile, with a banner when a newer release exists.
- Shortcuts to battery optimization, exact alarm and notification settings.
- `scripts/make-keystore.sh` creates the release keystore and prints the `gh secret set` commands.

### Fixed
- The service never wrote history or per-chat counters. It now runs the tested Rust `RunSession`, so sent counts, failures, last-sent times and the three-strikes auto-disable are saved after every chat.
- The first alarm was never armed after enabling automation, and a run with nothing to send stopped without arming the next one. Every run now ends by arming the next alarm.
- The "already messaged today" check used the UTC calendar day, which skipped a whole run with the 23 hour interval. It now uses an elapsed-time window.
- A fixed daily time was read as UTC. It now uses the phone's local time zone, and is recomputed when the time zone changes.
- The dashboard "next run" was computed on screen as now plus the interval. It now shows the stored alarm time.
- Alarms are restored after an app update as well as after a reboot, and when the app is opened and the alarm is missing.
- The login page is now inset from the status and navigation bars, which Android 15 no longer does automatically.

### Changed
- Removed the `REQUEST_INSTALL_PACKAGES` and `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` permissions and the unused FileProvider.
- Release tags now fail in CI unless the permanent signing secrets are set. Branch and pull request builds still use a throwaway key.
- A run is stopped after 25 minutes, and a chat that does not answer within 90 seconds counts as a failed attempt.

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
