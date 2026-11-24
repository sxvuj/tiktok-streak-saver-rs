<p align="center">
  <img src="docs/icon.png" alt="Streak Saver RS app icon" width="128" height="128"/>
</p>

<h1 align="center">Streak Saver RS</h1>

<p align="center"><strong>Keep your TikTok streaks alive automatically. A Rust rewrite of Streak Saver for Android.</strong></p>

<p align="center">
  <a href="https://github.com/sxvuj/tiktok-streak-saver-rs/releases/latest"><img alt="Latest release" src="https://img.shields.io/github/v/release/sxvuj/tiktok-streak-saver-rs?label=release"></a>
  <a href="https://github.com/sxvuj/tiktok-streak-saver-rs/actions/workflows/android-release.yml"><img alt="Build" src="https://github.com/sxvuj/tiktok-streak-saver-rs/actions/workflows/android-release.yml/badge.svg"></a>
  <a href="LICENSE"><img alt="License: MIT" src="https://img.shields.io/badge/license-MIT-blue.svg"></a>
</p>

Streak Saver RS sends a short message to the TikTok friends and group chats you choose, on a schedule, so a forgotten day does not break a streak. All scheduling and decision logic lives in a Rust core. The Android app is a thin Kotlin and Jetpack Compose shell around it.

This project is derived from [Jon2G/TiktokStreakSaver](https://github.com/Jon2G/TiktokStreakSaver) (a .NET MAUI app) and rewritten in Rust, under the original's MIT License. See [License and credits](#license-and-credits). It is not affiliated with TikTok or ByteDance.

## Download

1. Open the [Releases page](https://github.com/sxvuj/tiktok-streak-saver-rs/releases).
2. Download `StreakSaver-RS-vX.Y.Z.apk` from the latest release.
3. Allow installs from your browser or file manager when Android asks.
4. Open the APK to install it.

If Google Play Protect warns about the app, see [Play Protect](#play-protect) below.

**Updates:** the app checks this repository's latest release on Home and in Profile ("Check for updates"). When a newer release exists it shows a banner with a button that opens the release page, where you download the new APK and install it over the old one. Updates install over the existing app only when both APKs were signed with the same key, which is why every release is signed with one permanent key.

**Requirements:** Android 7.0 (API 24) or newer. The APK contains native libraries for `arm64-v8a`, `armeabi-v7a` and `x86_64`.

The app id is `com.sxvuj.streaksaver`, which differs from the original app. Both can be installed on the same phone, and installing one never replaces the other.

## What it does

- **Scheduling:** runs every 1 to 23 hours (default 23), or once a day at a fixed time.
- **Offline retry:** if there is no network when a run is due, the run is skipped and retried an hour later.
- **Reboot persistence:** the next run is rescheduled after the phone restarts.
- **Low battery:** an optional early run when the battery gets low, at most once per day.
- **Cookie session:** you sign in to TikTok once in an in-app WebView. The session and user agent are reused for background runs.
- **Foreground service:** a headless WebView opens TikTok messages, finds each enabled chat and sends the message. A notification shows progress.
- **Messages:** your own text, or a built-in pool of 49 short streak phrases that is reshuffled when exhausted. With random messages on, your own text is not used.
- **Chat order:** mixed, friends first or groups first.
- **Safety nets:** bounded retries per friend, and a friend that cannot be found is disabled after three failed runs in a row.
- **Local storage:** settings, friends and run history are saved as JSON files in the app's private storage, written atomically. History is capped at 50 runs.

## The app

Four tabs, built with Material 3 (light and dark):

- **Home:** how many chats were messaged today, a Run now / Stop button with live progress, the real next scheduled run, the last run, the message (your own text, or a Random messages switch with an example phrase), and an update banner. Pull down to refresh.
- **Chats:** your friends and groups in two tabs, with search, a per-chat switch, add, edit and delete, Turn all on / off, and totals. The menu imports and exports the list as JSON.
- **History:** a success ring, run log with failures per chat, "Retry failed", Export log and Clear. Runs that were skipped (offline, logged out) are listed too, with the reason.
- **Profile:** TikTok session (log in, verify, log out), background automation, interval or fixed local time, random messages, chat order, low-battery run, and shortcuts to the battery, exact alarm and notification settings.

### Moving your friends list from the original app

Export the list in the original Streak Saver, then in this app open **Chats > menu > Import list** and pick the file. The importer is tolerant (different key casing, wrapped lists, dates with or without a zone) and reports how many chats were added, how many were already there and how many could not be read. It has not been tested against a real export from the original app yet, so please open an issue with a sample (remove usernames first) if a file is rejected. You can also add chats by hand.

## How it works

```
Alarm fires (or you tap Run now)
   -> AlarmReceiver starts StreakService (foreground)
   -> checks network and the TikTok session (problems are logged in History)
   -> RustCore RunSession plans the run: enabled chats, skipping anyone messaged recently
   -> headless WebView loads tiktok.com/messages
   -> for each chat: inject script, report the result back to RunSession
      (retries, counters and the three-strikes rule are handled in Rust)
   -> RunSession writes the history entry
   -> next alarm is always armed (interval, fixed time, or a 1 hour retry after errors)
```

"Messaged recently" means within the skip window: 20 hours for a fixed daily time, otherwise the interval minus one hour. It is measured in elapsed hours, not calendar days, so a 23 hour interval never skips a whole run by accident.

## Project layout

| Path | Purpose |
|------|---------|
| `crates/streak-saver-core` | Pure Rust engine: models, message pool, scheduler, session rules, automation scripts, storage, update check. No `unsafe`, no `unwrap` in production code. |
| `crates/streak-saver-jni` | Android JNI `cdylib` (`libstreak_saver_jni.so`), linked with 16 KB page alignment for Android 15 and 16. |
| `crates/streak-saver-cli` | Desktop CLI: add friends, list friends, show the next run, simulate a run, show stats, replay parity cases. |
| `android/` | Kotlin and Compose app: `ui/` (screens, `AppModel`), `data/` (`StreakRepo`, models), `service/` (`StreakService`, `Scheduler`, notifications), `receiver/`. Everything goes through `RustCore`. |
| `rust-anything/` | Migration plan and recorded behavior cases used to check parity with the original app. |
| `.github/workflows/android-release.yml` | CI: Rust checks, native build, signed Release APK, GitHub Release. |

## Build from source

### 1. Rust checks

```bash
cargo fmt --all -- --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace
cargo run -p streak-saver-cli -- parity-verify --cases rust-anything/cases.jsonl
```

### 2. Native libraries for Android

```bash
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk

RUSTFLAGS="-C link-arg=-Wl,-z,max-page-size=16384" cargo ndk \
  -t arm64-v8a -t armeabi-v7a -t x86_64 \
  -o android/app/src/main/jniLibs \
  build --release -p streak-saver-jni
```

### 3. Release APK

You need JDK 17, the Android SDK (API 35) and Gradle 8.9.

```bash
cd android
gradle assembleRelease
```

The APK is written to `android/app/build/outputs/apk/release/`. Without a signing key it is unsigned and Android will not install it. To sign it, set these environment variables before running Gradle:

```bash
export KEYSTORE_PATH=/absolute/path/to/release.jks
export KEYSTORE_PASSWORD=...
export KEY_ALIAS=streaksaver
export KEY_PASSWORD=...
```

### CLI examples

```bash
cargo run -p streak-saver-cli -- --help
cargo run -p streak-saver-cli -- --data-dir ./data add-friend --id 1 --username someone
cargo run -p streak-saver-cli -- --data-dir ./data next-run
```

## Releasing (maintainers)

Releases are built by GitHub Actions, not by hand.

**One-time setup: release signing key.** A stable key is what lets users update the app without uninstalling it.

```bash
./scripts/make-keystore.sh
```

The script creates `streaksaver-release.jks` and prints the exact `gh secret set` commands for the four secrets below (`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`). Keep the keystore and password backed up and private; `.jks` files are git-ignored. You can also add the secrets by hand under **Settings > Secrets and variables > Actions**.

**Tag builds refuse to run without these secrets.** Branch and pull request builds fall back to a throwaway key so they still produce a test APK, but that APK cannot update an install signed with a different key, so never hand it out as a release.

**Publishing a version:**

1. Bump `versionName` and `versionCode` in `android/app/build.gradle.kts` and `version` in the root `Cargo.toml`. The workflow fails if they disagree.
2. Add a `## [X.Y.Z] - date` section to `CHANGELOG.md`. It becomes the release notes.
3. Commit, then tag and push:

```bash
git tag vX.Y.Z
git push origin vX.Y.Z
```

The workflow checks that the tag matches the app version, builds and verifies the signed Release APK, and attaches `StreakSaver-RS-vX.Y.Z.apk` to the GitHub Release.

## Differences from the original app

| | Original (Jon2G) | Streak Saver RS |
|---|---|---|
| Language | C# on .NET MAUI | Rust core with a Kotlin and Compose Android shell |
| App id | `com.jon2g.tiktokstreaksaver` | `com.sxvuj.streaksaver` |
| App name | Streak Saver | Streak Saver RS |
| Version | 2.2.0 | starts at 1.0.0 (now 1.1.1) |
| Platforms | Android, experimental iOS | Android |
| Update source | Jon2G releases | this repository's releases |

## Play Protect

Version 1.0.0 was blocked by Google Play Protect on at least one phone ("Harmful app blocked"). The exact reason is not known, because Play Protect does not say. 1.1.0 removes things that make a sideloaded app look riskier than it needs to be: the permission to install other packages, the battery-optimization exemption permission and the file provider are gone. Release builds are now always signed with one permanent key. The app still uses a foreground service, exact alarms and a WebView with JavaScript, because the job needs them, so a warning on a new, unknown APK can still happen. If it does, the options are "Install anyway" on the warning dialog, or turning off scanning for this install. Please report it with your Android version if it keeps happening.

## Disclaimer

This app is for educational purposes. Use it responsibly and in line with TikTok's Terms of Service. Automating an account can lead to restrictions, and the authors are not responsible for any action taken against your account.

## Privacy

There are no analytics, ads or developer-owned servers in this project. The app talks to TikTok through its WebView, and your friends list, settings and history stay in the app's private storage on your device. Auto backup is disabled in the manifest.

## License and credits

Released under the [MIT License](LICENSE). The original work is copyright (c) 2024 jon2g and the Rust rewrite is copyright (c) 2026 sxvuj

- [Jon2G/TiktokStreakSaver](https://github.com/Jon2G/TiktokStreakSaver): the original app this project is based on, including the TikTok automation scripts and the behavior the Rust core reproduces.
- [eulfn/streak-tiktok](https://github.com/eulfn/streak-tiktok): the multi-page redesign and stability work that the upstream app builds on.
- [RusdiEneri](https://github.com/RusdiEneri/TiktokStreakSaver): the login guard and inbox viewport fixes ported upstream.
- [erkamyaman/rust-anything](https://github.com/erkamyaman/rust-anything): the parity-first Android JNI profile used to structure the port.
