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
3. Optional: download the matching `.sha256` file and compare it with the APK (`sha256sum -c StreakSaver-RS-vX.Y.Z.apk.sha256`). A mismatch means the download was cut short or altered.
4. Allow installs from your browser or file manager when Android asks.
5. Open the APK to install it.

If Google Play Protect warns about the app, see [Play Protect](#play-protect) below.

**Updates:** the app checks this repository's latest release on Home and in Settings ("Check"). When a newer release exists it shows a banner with a button that opens the release page, where you download the new APK and install it over the old one. Updates install over the existing app only when both APKs were signed with the same key, which is why every release is signed with one permanent key.

**Requirements:** Android 7.0 (API 24) or newer. The APK contains native libraries for `arm64-v8a`, `armeabi-v7a` and `x86_64`.

The app id is `com.sxvuj.streaksaver`, which differs from the original app. Both can be installed on the same phone, and installing one never replaces the other.

## What it does

- **Scheduling:** runs every 1 to 23 hours (default 23), or once a day at a fixed time.
- **Offline retry:** if there is no network when a run is due, the run is skipped and retried an hour later.
- **Reboot persistence:** the next run is rescheduled after the phone restarts.
- **Low battery:** an optional early run when the battery gets low, at most once per day.
- **Cookie session:** you sign in to TikTok once in an in-app WebView. The session and user agent are reused for background runs.
- **Foreground service:** a headless WebView opens TikTok messages, finds each enabled chat and sends the message. A notification shows progress.
- **Messages:** one text of your own, or a random list. The list starts as the 49 built-in phrases and is fully editable: add, edit, delete, edit as plain text (one message per line) or reset to the built-in phrases. A shuffled deck gives every chat a different message and repeats none until the whole list has been used. If you empty the list, your single message is sent instead.
- **Chat order:** mixed, friends first or groups first.
- **Safety nets:** bounded retries per friend, and a friend that cannot be found is disabled after three failed runs in a row.
- **Local storage:** settings, friends and run history are saved as JSON files in the app's private storage, written atomically. History is capped at 50 runs.

## The app

Version 1.2.0 redesigned the app after the look of the Fitbit (Google Health) app: a blue neutral Material 3 palette with teal and amber accents, a thick 270 degree gauge, large rounded cards, pill buttons and a bottom bar with a blue pill indicator. Light and dark themes follow the system.

Version 1.3.0 makes it softer and livelier:

- **Rounded text.** All text is set in Google Sans Flex with its roundness axis (`ROND`) turned all the way up, the same soft rounded terminals Google uses in Google Health and other recent Google apps, in heavy weights (Black and ExtraBold for headings, Bold for labels). `scripts/make-fonts.py` rebuilds the font files.
- **Motion.** Spring-based animations throughout, tuned to the Material 3 Expressive motion scheme: fade-through between tabs, a slide when the message editor opens, staggered entrance of cards, list rows that fade and glide when they are added, removed or filtered, a sliding segmented control, count-up numbers, a gauge that sweeps up and bounces at 100%, squishy press feedback on cards and buttons, a collapsing Add button, and nav icons that switch from outlined to rounded with a little pop.
- **Dynamic color.** Settings > Appearance > Dynamic color takes the app's colors from your wallpaper (Material You / Monet) on Android 12 and newer. It is off by default, which keeps the blue Fitbit palette. Switching it glides every color to the new palette.

Four tabs plus a message editor:

- **Today:** a gauge of how many chats were messaged today, a Run now / Stop button with live progress, tiles for friends, groups and today's success rate, the real next scheduled run, the last run, and the message that will be sent with a shortcut to the editor. Pull down to refresh.
- **Friends:** your friends and groups in two tabs, with search, a per-chat switch, add, edit and delete, Turn all on / off, and totals. The menu imports and exports the list as JSON.
- **History:** an all-time success gauge, run log with failures per chat, "Retry failed", Export log and Clear. Runs that were skipped (offline, logged out) are listed too, with the reason.
- **Settings:** TikTok session (log in, verify, log out), background automation, interval or fixed local time, messages, chat order, low-battery run, shortcuts to the battery, exact alarm and notification settings, and Appearance (dynamic color).
- **Messages editor** (Settings > Edit messages, or Today > Edit messages): choose Single message or Random list. The single message saves as you type. List edits are a draft until you tap Save, so you can Discard them; messages are trimmed, limited to 280 characters, de-duplicated and capped at 200 when saved.

### Moving your friends list from the original app

Export the list in the original Streak Saver, then in this app open **Friends > menu > Import list** and pick the file. The importer is tolerant (different key casing, wrapped lists, dates with or without a zone) and reports how many chats were added, how many were already there and how many could not be read. It has not been tested against a real export from the original app yet, so please open an issue with a sample (remove usernames first) if a file is rejected. You can also add chats by hand.

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

**Tag builds refuse to run without these secrets.** Branch and pull request builds use the same key when the secrets exist, and otherwise fall back to a throwaway key so they still produce a test APK. A throwaway-key APK cannot update an install signed with a different key, so never hand it out as a release.

**Publishing a version:**

1. Bump `versionName` and `versionCode` in `android/app/build.gradle.kts` and `version` in the root `Cargo.toml`. The workflow fails if they disagree.
2. Add a `## [X.Y.Z] - date` section to `CHANGELOG.md`. It becomes the release notes.
3. Commit, then tag and push:

```bash
git tag vX.Y.Z
git push origin vX.Y.Z
```

The workflow checks that the tag matches the app version, builds and verifies the signed Release APK, and attaches `StreakSaver-RS-vX.Y.Z.apk` and its `.sha256` file to the GitHub Release. Every build also writes the signing certificate fingerprint and the APK checksum to the job summary. With the secrets configured, every build shows the same certificate fingerprint; without them, branch builds use a throwaway key and the fingerprint changes every time.

## Differences from the original app

| | Original (Jon2G) | Streak Saver RS |
|---|---|---|
| Language | C# on .NET MAUI | Rust core with a Kotlin and Compose Android shell |
| App id | `com.jon2g.tiktokstreaksaver` | `com.sxvuj.streaksaver` |
| App name | Streak Saver | Streak Saver RS |
| Version | 2.2.0 | starts at 1.0.0 (now 1.3.0) |
| Platforms | Android, experimental iOS | Android |
| Update source | Jon2G releases | this repository's releases |

## Play Protect

Version 1.0.0 was blocked by Google Play Protect on at least one phone ("Harmful app blocked"), while a build from CI was not. Play Protect does not say why, and this project cannot reproduce it, so what follows is what is known and what is only a guess.

**What it is not.** The signature of the release APK is valid (`apksigner verify` passes in CI). A broken signature produces "App not installed" or a parse error, not "Harmful app blocked". Google describes "Harmful App Blocked" as a classification: the app was identified as a Potentially Harmful Application. That decision comes from Google's scanning, behavior and reputation signals, not from the file being malformed.

**Why the release and the CI build differ.** For v1.1.1 the two builds come from the same commit (`fe4dd21`), so the code and manifest are the same. The CI build that installed fine was made on 24 November, before the signing secrets existed, so it was signed with a throwaway key. The release APK was rebuilt on 27 November and signed with the permanent key (certificate SHA-256 `8D4536A2...CBC5EF2`). On the reporter's phone both showed the "scan this app" prompt, then the CI build was reported safe and the release build was reported "Harmful app blocked". The signing certificate is therefore the one known difference (the delivery path, and the time of the scan, are the others). Google's list of Play Protect signals mentions static and dynamic analysis, similarity, machine learning and "developer relationships", but does not say whether signing certificates are used, so this is an observation, not an explanation.

**How to tell.** Builds from pull requests that use the repository secrets are signed with the permanent key, so installing such a build is a direct test. If it is blocked too, the certificate (or something tied to it) is the problem and a new signing key is worth trying. If it is not blocked, the cause was specific to that one release file.

**What 1.2.0 changes, as hygiene only.** Google tells developers to use permissions only for their intended purpose. `USE_EXACT_ALARM` is reserved for alarm and calendar apps, so it is removed. The app now asks for `SCHEDULE_EXACT_ALARM` only, which you grant in Settings > Exact alarms. Without it runs can start a few minutes late, which does not matter for a 23 hour schedule. Release APKs are signed with the v2 and v3 schemes (v1 is not used at minSdk 24), and the encrypted dependency-metadata block is no longer embedded. None of this is a guaranteed fix. The app still uses a foreground service, a boot receiver and a WebView with JavaScript, because the job needs them.

**If it still happens:**

1. Check the download first: compare the APK with the `.sha256` file on the release page.
2. Tap "More details" on the warning, then "Install anyway", or turn off scanning for this install.
3. Report it to Google. The developer appeal form is at [support.google.com/googleplay/android-developer/contact/protectappeals](https://support.google.com/googleplay/android-developer/contact/protectappeals). Give the package name `com.sxvuj.streaksaver`, the release version and the signing certificate SHA-256 from the build job summary.
4. Open an issue here with your Android version and the exact wording of the warning.

Changing the signing key is a last resort. It stops every existing install from updating (users must export their list, uninstall and reinstall), so only do it after the test above points at the certificate.

**A separate mechanism in Indonesia.** Since 30 September 2026 Google's Android developer verification applies to users in Indonesia, Brazil, Singapore and Thailand: on certified devices, apps from unregistered developers can only be installed through adb or an advanced flow. That is a different block with a different message from "Harmful app blocked". Google offers free limited-distribution accounts for students and hobbyists (up to 20 devices). See the [Android developer verification announcement](https://android-developers.googleblog.com/2026/06/android-developer-verification.html).

## Disclaimer

This app is for educational purposes. Use it responsibly and in line with TikTok's Terms of Service. Automating an account can lead to restrictions, and the authors are not responsible for any action taken against your account.

## Privacy

There are no analytics, ads or developer-owned servers in this project. The app talks to TikTok through its WebView, and your friends list, settings and history stay in the app's private storage on your device. Auto backup is disabled in the manifest.

## License and credits

Released under the [MIT License](LICENSE). The original work is copyright (c) 2024 jon2g and the Rust rewrite is copyright (c) 2026 sxvuj

- [Jon2G/TiktokStreakSaver](https://github.com/Jon2G/TiktokStreakSaver): the original app this project is based on, including the TikTok automation scripts and the behavior the Rust core reproduces.
- [Google Sans Flex](https://fonts.google.com/specimen/Google+Sans+Flex): the typeface, used under the SIL Open Font License 1.1 (see `licenses/GoogleSansFlex-OFL.txt`). The app ships rounded static instances (ROND 100) made with `scripts/make-fonts.py`. The visual design follows the Fitbit (Google Health) app, and no assets from it are included.
- [eulfn/streak-tiktok](https://github.com/eulfn/streak-tiktok): the multi-page redesign and stability work that the upstream app builds on.
- [RusdiEneri](https://github.com/RusdiEneri/TiktokStreakSaver): the login guard and inbox viewport fixes ported upstream.
- [erkamyaman/rust-anything](https://github.com/erkamyaman/rust-anything): the parity-first Android JNI profile used to structure the port.
