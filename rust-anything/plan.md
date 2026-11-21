# rust-anything Migration Plan — `Jon2G/TiktokStreakSaver` (v2.2.0)

Source repository: https://github.com/Jon2G/TiktokStreakSaver  
Methodology reference: https://github.com/erkamyaman/rust-anything  
Verified binary baseline: `StreakSaver-v2.2.0.apk` (`TiktokStreakSaver.dll` + `assets/tiktok_automation.js` + `assets/detect_groups.js`)

## Why Hybrid Core + Thin Android Shell (rust-anything Android Profile)

Per `erkamyaman/rust-anything`'s Android profile (`skills/rust-anything/SKILL.md`), logic units are ported to pure Rust (`streak-saver-core`) and exposed via a 16 KB page-aligned JNI `cdylib` (`streak-saver-jni`), while Android SDK-only lifecycle surfaces (`WebView`, `ForegroundService`, `AlarmManager`, `BroadcastReceiver`, Jetpack Compose UI, `FileProvider`) stay in a thin Kotlin shell (`android/`).

## Unit Breakdown by Wave

| Wave | Original C# Unit | Rust Module (`streak-saver-core`) | Status | Notes |
| :--- | :--- | :--- | :--- | :--- |
| Wave 1 | `Models/FriendConfig.cs`, `StreakRunResult.cs`, `FriendMessageResult.cs` | `src/models.rs` | Ported | Preserves `PascalCase` JSON schema compatibility |
| Wave 1 | Randomized messages pool (`SettingsService.cs`) | `src/messages.rs` | Ported | Exact 49 phrases extracted from `StreakSaver-v2.2.0.apk`, shuffle-and-reshuffle deck |
| Wave 2 | `Services/TikTokWebViewHelper.cs`, `SessionService.cs` | `src/session.rs` | Ported | `sessionid=` cookie check, Chrome desktop UA fallback, `snssdk://` & `intent://` `params_url` login guard |
| Wave 2 | `Services/StreakScheduler.cs`, `BatteryLowReceiver.cs` | `src/scheduler.rs` | Ported | 1–23h interval, fixed daily time, 1h offline/error retry budget, battery-low anticipation |
| Wave 3 | `Services/StreakService.cs` state machine & JS templating | `src/automation.rs` | Ported | `tiktok_automation.js` embedded verbatim, JS literal escaping, `ChatPriority`, bounded per-friend retries, 3-strike auto-disable, 1920×1080 density scaling |
| Wave 3 | `Services/SettingsService.cs`, `Storage`, `UpdateService.cs` | `src/storage.rs`, `src/updater.rs` | Ported | Atomic `.tmp` rename writes, 50-run history cap, friends import/export, GitHub Releases semver + APK selector |
| Wave 4 | JNI bridge & standalone CLI | `crates/streak-saver-jni`, `crates/streak-saver-cli` | Ported | 19 JNI exports, 16 KB ELF alignment, `parity-verify` runner for `cases.jsonl` |
| Keep | Android SDK lifecycle & UI (`MainActivity`, `StreakService`, `AlarmReceiver`, `BootReceiver`, `BatteryLowReceiver`, `LoginWebViewActivity`) | `android/` (Kotlin + Compose) | Thin Shell | Zero business logic; delegates all decisions to `RustCore` |

## Gate Checklist

- `#![forbid(unsafe_code)]` on `streak-saver-core` and `streak-saver-cli`
- Workspace Clippy denies `unwrap_used`, `expect_used`, `panic`, `todo`, `unimplemented`
- Recorded v2.2.0 behavioral parity suite in `rust-anything/cases.jsonl`
- Verify on any Rust host with:
  ```bash
  cargo fmt --all -- --check
  cargo clippy --workspace --all-targets -- -D warnings
  cargo test --workspace
  cargo run -p streak-saver-cli -- parity-verify --cases rust-anything/cases.jsonl
  ```
