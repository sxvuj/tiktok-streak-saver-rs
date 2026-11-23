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

**Requirements:** Android 7.0 (API 24) or newer. The APK contains native libraries for `arm64-v8a`, `armeabi-v7a` and `x86_64`.

The app id is `com.sxvuj.streaksaver`, which differs from the original app. Both can be installed on the same phone, and installing one never replaces the other.

## What it does

- **Scheduling:** runs every 1 to 23 hours (default 23), or once a day at a fixed time.
- **Offline retry:** if there is no network when a run is due, the run is skipped and retried an hour later.
- **Reboot persistence:** the next run is rescheduled after the phone restarts.
- **Low battery:** an optional early run when the battery gets low, at most once per day.
- **Cookie session:** you sign in to TikTok once in an in-app WebView. The session and user agent are reused for background runs.
- **Foreground service:** a headless WebView opens TikTok messages, finds each enabled chat and sends the message. A notification shows progress.
- **Messages:** your own text, or a built-in pool of 49 short streak phrases that is reshuffled when exhausted.
- **Chat order:** mixed, friends first or groups first.
- **Safety nets:** bounded retries per friend, and a friend that cannot be found is disabled after three failed runs in a row.
- **Local storage:** settings, friends and run history are saved as JSON files in the app's private storage, written atomically. History is capped at 50 runs.

## Current status

This is the first release (1.0.0). The Rust core is complete and covered by tests, but the Android screens are intentionally minimal:

- The **Dashboard** shows the next scheduled run and has **TikTok Login** and **Run Now** buttons.
- The **Friends**, **History** and **Profile** tabs show the stored data as raw JSON. Editing friends and settings from inside the app is not built yet.
- Friends and settings can currently be prepared with the bundled CLI (see below).

Contributions to the Android UI are very welcome.

## How it works

```
Alarm fires
   -> AlarmReceiver starts StreakService (foreground)
   -> network check (offline: skip and retry in 1 hour)
   -> headless WebView loads tiktok.com/messages
   -> RustCore builds the run plan and renders the automation script
   -> for each enabled friend: open chat, send message
   -> record results, schedule the next alarm, stop the service
```

## Project layout

| Path | Purpose |
|------|---------|
| `crates/streak-saver-core` | Pure Rust engine: models, message pool, scheduler, session rules, automation scripts, storage, update check. No `unsafe`, no `unwrap` in production code. |
| `crates/streak-saver-jni` | Android JNI `cdylib` (`libstreak_saver_jni.so`), linked with 16 KB page alignment for Android 15 and 16. |
| `crates/streak-saver-cli` | Desktop CLI: add friends, list friends, show the next run, simulate a run, show stats, replay parity cases. |
| `android/` | Kotlin and Compose app (`MainActivity`, `LoginWebViewActivity`, `StreakService`, receivers) that calls the core through `RustCore`. |
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
keytool -genkeypair -v -keystore release.jks -storetype PKCS12 \
  -alias streaksaver -keyalg RSA -keysize 4096 -validity 36500
base64 -w0 release.jks
```

Keep `release.jks` private and backed up. In the repository go to **Settings > Secrets and variables > Actions** and add:

| Secret | Value |
|--------|-------|
| `KEYSTORE_BASE64` | output of the `base64` command above |
| `KEYSTORE_PASSWORD` | the keystore password |
| `KEY_ALIAS` | `streaksaver` |
| `KEY_PASSWORD` | the same password (PKCS12 uses one password for both) |

If these secrets are missing, the workflow still produces an installable APK but signs it with a throwaway key, and prints a warning. Such an APK cannot update an install that was signed with a different key.

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
| Version | 2.2.0 | starts at 1.0.0 |
| Platforms | Android, experimental iOS | Android |
| Update source | Jon2G releases | this repository's releases |

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
