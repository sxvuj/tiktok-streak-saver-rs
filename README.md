# TikTok Streak Saver — Rust Rewrite (`v2.2.0`)

Full Rust rewrite of [Jon2G/TiktokStreakSaver](https://github.com/Jon2G/TiktokStreakSaver) (`v2.2.0`, originally .NET 9 MAUI) structured using the [erkamyaman/rust-anything](https://github.com/erkamyaman/rust-anything) parity-first Android JNI profile.

## Architecture

- **`crates/streak-saver-core`** — 100% pure Rust (`#![forbid(unsafe_code)]`, zero `unwrap`/`expect`/`panic` in production code):
  - `models`: `FriendConfig`, `FriendMessageResult`, `StreakRunResult`, `AppSettings`, `SessionState`, `ChatPriority`, `UpdateInfo` (JSON-compatible with v2.2.0).
  - `messages`: Exact 49-phrase randomized streak pool extracted from `StreakSaver-v2.2.0.apk`, with shuffle-and-reshuffle-on-exhaust deck.
  - `scheduler`: 1–23h interval scheduling, fixed daily time rollover, 1-hour offline/error retry budget, and battery-low anticipation.
  - `session`: `sessionid=` cookie validator, Chrome desktop UA fallback, and `snssdk://` / `intent://` `params_url` login guard.
  - `automation`: Verbatim bundled `tiktok_automation.js` & `detect_groups.js`, JS literal escaping, `ChatPriority` (`mixed`, `friends_first`, `groups_first`), targeted retry planning, bounded per-friend retries, 3-strike auto-disable on `"User not found in chat list"`, and 1920×1080 density-scaled viewport calculation.
  - `storage`: Atomic `.tmp` + rename JSON persistence, 50-run history cap, friends import/export, search, bulk enable/disable, and log export.
  - `updater`: Semver comparison and GitHub Releases APK asset selection (`Jon2G/TiktokStreakSaver`).
- **`crates/streak-saver-jni`** — Android JNI `cdylib` (`libstreak_saver_jni.so`) with 16 KB ELF page alignment (`-Wl,-z,max-page-size=16384`) for Android 15/16.
- **`crates/streak-saver-cli`** — Standalone Rust CLI for local management, run simulation, and `rust-anything/cases.jsonl` parity verification.
- **`android/`** — Thin Kotlin + Jetpack Compose Android shell (`MainActivity`, `LoginWebViewActivity`, `StreakService`, `AlarmReceiver`, `BootReceiver`, `BatteryLowReceiver`, `FileProvider`) delegating all logic to `RustCore`.

## Build & Verify

### 1. Run the Rust Gate & Parity Suite (Desktop / CI)
```bash
cargo fmt --all -- --check
cargo clippy --workspace --all-targets -- -D warnings
cargo test --workspace
cargo run -p streak-saver-cli -- parity-verify --cases rust-anything/cases.jsonl
```

### 2. Cross-Compile for Android (`arm64-v8a`, `armeabi-v7a`, `x86_64`)
```bash
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
cargo install cargo-ndk

cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 \
  -o android/app/src/main/jniLibs \
  build --release -p streak-saver-jni
```

### 3. Build the Android APK
Open `android/` in Android Studio or run Gradle with the Android SDK installed:
```bash
cd android && gradle assembleDebug
```
