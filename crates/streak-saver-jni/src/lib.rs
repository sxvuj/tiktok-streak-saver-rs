#![allow(non_snake_case)]
// JNI exports must be `#[no_mangle]`, which the workspace `unsafe_code` lint rejects.
#![allow(unsafe_code)]

//! Android JNI entry points for `com.sxvuj.streaksaver.rust.RustCore`.
//!
//! Follows the `erkamyaman/rust-anything` Android profile: all business logic lives in
//! `streak-saver-core`, and the thin Kotlin Android shell crosses JNI via JSON payloads
//! and primitive values without holding panicking Rust state.

use std::sync::{Mutex, MutexGuard, PoisonError};

use chrono::{DateTime, Utc};
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jfloat, jint, jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use serde::Serialize;

use streak_saver_core::automation::{
    build_run_plan, compute_webview_viewport, render_automation_script,
};
use streak_saver_core::messages::MessageDeck;
use streak_saver_core::models::{AppSettings, ChatPriority, FriendConfig, StreakRunResult};
use streak_saver_core::run::RunSession;
use streak_saver_core::scheduler::{
    compute_next_run, compute_next_run_with_offset, evaluate_battery_low_anticipation,
    evaluate_retry_schedule, record_clean_run_success,
};
use streak_saver_core::session::{
    guard_login_navigation, has_valid_session_cookie, resolve_user_agent,
};
use streak_saver_core::storage::{summarize_history, AppStorage};
use streak_saver_core::updater::{check_latest_release_json, GITHUB_LATEST_RELEASE_URL};

#[derive(Serialize)]
struct JniResponse<T: Serialize> {
    ok: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    data: Option<T>,
    #[serde(skip_serializing_if = "Option::is_none")]
    error: Option<String>,
}

fn read_jstring(env: &mut JNIEnv<'_>, s: JString<'_>) -> Result<String, String> {
    env.get_string(&s)
        .map(|js| js.into())
        .map_err(|e| format!("JNI get_string failed: {e}"))
}

fn to_jstring(env: &mut JNIEnv<'_>, text: &str) -> jstring {
    match env.new_string(text) {
        Ok(js) => js.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

fn json_ok<T: Serialize>(env: &mut JNIEnv<'_>, value: T) -> jstring {
    let payload = JniResponse {
        ok: true,
        data: Some(value),
        error: None,
    };
    let text = serde_json::to_string(&payload)
        .unwrap_or_else(|_| r#"{"ok":false,"error":"serialization failed"}"#.to_string());
    to_jstring(env, &text)
}

fn json_err(env: &mut JNIEnv<'_>, msg: impl Into<String>) -> jstring {
    let payload = JniResponse::<()> {
        ok: false,
        data: None,
        error: Some(msg.into()),
    };
    let text = serde_json::to_string(&payload)
        .unwrap_or_else(|_| r#"{"ok":false,"error":"serialization failed"}"#.to_string());
    to_jstring(env, &text)
}

/// `RustCore.guardLoginUrl(url: String): String` (JSON `LoginNavDecision`)
#[no_mangle]
pub extern "system" fn Java_com_sxvuj_streaksaver_rust_RustCore_guardLoginUrl(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    url: JString<'_>,
) -> jstring {
    match read_jstring(&mut env, url) {
        Ok(u) => json_ok(&mut env, guard_login_navigation(&u)),
        Err(e) => json_err(&mut env, e),
    }
}

/// `RustCore.hasValidSessionCookie(cookieHeader: String): Boolean`
#[no_mangle]
pub extern "system" fn Java_com_sxvuj_streaksaver_rust_RustCore_hasValidSessionCookie(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    cookie_header: JString<'_>,
) -> jboolean {
    let valid = read_jstring(&mut env, cookie_header)
        .map(|c| has_valid_session_cookie(Some(&c)))
        .unwrap_or(false);
    if valid {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// `RustCore.resolveUserAgent(capturedUa: String): String`
#[no_mangle]
pub extern "system" fn Java_com_sxvuj_streaksaver_rust_RustCore_resolveUserAgent(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    captured_ua: JString<'_>,
) -> jstring {
    let ua = read_jstring(&mut env, captured_ua)
        .ok()
        .map(|s| resolve_user_agent(Some(&s)))
        .unwrap_or_else(|| resolve_user_agent(None));
    to_jstring(&mut env, &ua)
}

/// `RustCore.computeViewportJson(density: Float): String`
#[no_mangle]
pub extern "system" fn Java_com_sxvuj_streaksaver_rust_RustCore_computeViewportJson(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    density: jfloat,
) -> jstring {
    let vp = compute_webview_viewport(density);
    json_ok(&mut env, vp)
}

/// `RustCore.renderAutomationScript(username: String, message: String, isGroup: Boolean): String`
#[no_mangle]
pub extern "system" fn Java_com_sxvuj_streaksaver_rust_RustCore_renderAutomationScript(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    username: JString<'_>,
    message: JString<'_>,
    is_group: jboolean,
) -> jstring {
    let Ok(u) = read_jstring(&mut env, username) else {
        return to_jstring(&mut env, "");
    };
    let Ok(m) = read_jstring(&mut env, message) else {
        return to_jstring(&mut env, "");
    };
    let script = render_automation_script(&u, &m, is_group == JNI_TRUE);
    to_jstring(&mut env, &script)
}

/// `RustCore.pickRandomMessage(): String`
#[no_mangle]
pub extern "system" fn Java_com_sxvuj_streaksaver_rust_RustCore_pickRandomMessage(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jstring {
    let mut deck = MessageDeck::from_builtin();
    let msg = deck.next_message().unwrap_or("streak").to_string();
    to_jstring(&mut env, &msg)
}

/// `RustCore.computeNextRunJson(storageDir: String): String`
#[no_mangle]
pub extern "system" fn Java_com_sxvuj_streaksaver_rust_RustCore_computeNextRunJson(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    storage_dir: JString<'_>,
) -> jstring {
    let res = (|| -> Result<_, String> {
        let dir = read_jstring(&mut env, storage_dir)?;
        let storage = AppStorage::new(dir).map_err(|e| e.to_string())?;
        let settings = storage.load_settings().map_err(|e| e.to_string())?;
        compute_next_run(&settings, Utc::now()).map_err(|e| e.to_string())
    })();
    match res {
        Ok(decision) => json_ok(&mut env, decision),
        Err(e) => json_err(&mut env, e),
    }
}

/// `RustCore.evaluateRetryScheduleJson(storageDir: String, failureReason: String): String`
#[no_mangle]
pub extern "system" fn Java_com_sxvuj_streaksaver_rust_RustCore_evaluateRetryScheduleJson(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    storage_dir: JString<'_>,
    failure_reason: JString<'_>,
) -> jstring {
    let res = (|| -> Result<_, String> {
        let dir = read_jstring(&mut env, storage_dir)?;
        let reason = read_jstring(&mut env, failure_reason)?;
        let storage = AppStorage::new(dir).map_err(|e| e.to_string())?;
        let mut settings = storage.load_settings().map_err(|e| e.to_string())?;
        let decision = evaluate_retry_schedule(&mut settings, Utc::now(), &reason)
            .map_err(|e| e.to_string())?;
        storage
            .save_settings(&settings)
            .map_err(|e| e.to_string())?;
        Ok(decision)
    })();
    match res {
        Ok(decision) => json_ok(&mut env, decision),
        Err(e) => json_err(&mut env, e),
    }
}

/// `RustCore.recordCleanRunSuccess(storageDir: String): Boolean`
#[no_mangle]
pub extern "system" fn Java_com_sxvuj_streaksaver_rust_RustCore_recordCleanRunSuccess(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    storage_dir: JString<'_>,
) -> jboolean {
    let ok = (|| -> Result<(), String> {
        let dir = read_jstring(&mut env, storage_dir)?;
        let storage = AppStorage::new(dir).map_err(|e| e.to_string())?;
        let mut settings = storage.load_settings().map_err(|e| e.to_string())?;
        record_clean_run_success(&mut settings, Utc::now());
        storage.save_settings(&settings).map_err(|e| e.to_string())
    })()
    .is_ok();
    if ok {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// `RustCore.shouldAnticipateBatteryLow(storageDir: String): Boolean`
#[no_mangle]
pub extern "system" fn Java_com_sxvuj_streaksaver_rust_RustCore_shouldAnticipateBatteryLow(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    storage_dir: JString<'_>,
) -> jboolean {
    let trigger = (|| -> Result<bool, String> {
        let dir = read_jstring(&mut env, storage_dir)?;
        let storage = AppStorage::new(dir).map_err(|e| e.to_string())?;
        let mut settings = storage.load_settings().map_err(|e| e.to_string())?;
        let should_run = evaluate_battery_low_anticipation(&mut settings, Utc::now());
        if should_run {
            storage
                .save_settings(&settings)
                .map_err(|e| e.to_string())?;
        }
        Ok(should_run)
    })()
    .unwrap_or(false);
    if trigger {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// `RustCore.buildRunPlanJson(storageDir: String, targetedIdsCsv: String): String`
#[no_mangle]
pub extern "system" fn Java_com_sxvuj_streaksaver_rust_RustCore_buildRunPlanJson(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    storage_dir: JString<'_>,
    targeted_ids_csv: JString<'_>,
) -> jstring {
    let res = (|| -> Result<_, String> {
        let dir = read_jstring(&mut env, storage_dir)?;
        let csv = read_jstring(&mut env, targeted_ids_csv)?;
        let storage = AppStorage::new(dir).map_err(|e| e.to_string())?;
        let settings = storage.load_settings().map_err(|e| e.to_string())?;
        let friends = storage.load_friends().map_err(|e| e.to_string())?;
        let ids: Vec<String> = csv
            .split(',')
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map(ToString::to_string)
            .collect();
        let opt_ids = if ids.is_empty() {
            None
        } else {
            Some(ids.as_slice())
        };
        Ok(build_run_plan(
            &friends,
            opt_ids,
            settings.chat_priority,
            Utc::now(),
        ))
    })();
    match res {
        Ok(plan) => json_ok(&mut env, plan),
        Err(e) => json_err(&mut env, e),
    }
}

/// `RustCore.loadSettingsJson(storageDir: String): String`
#[no_mangle]
pub extern "system" fn Java_com_sxvuj_streaksaver_rust_RustCore_loadSettingsJson(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    storage_dir: JString<'_>,
) -> jstring {
    let res = (|| -> Result<AppSettings, String> {
        let dir = read_jstring(&mut env, storage_dir)?;
        let storage = AppStorage::new(dir).map_err(|e| e.to_string())?;
        storage.load_settings().map_err(|e| e.to_string())
    })();
    match res {
        Ok(s) => json_ok(&mut env, s),
        Err(e) => json_err(&mut env, e),
    }
}

/// `RustCore.saveSettingsJson(storageDir: String, settingsJson: String): Boolean`
#[no_mangle]
pub extern "system" fn Java_com_sxvuj_streaksaver_rust_RustCore_saveSettingsJson(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    storage_dir: JString<'_>,
    settings_json: JString<'_>,
) -> jboolean {
    let ok = (|| -> Result<(), String> {
        let dir = read_jstring(&mut env, storage_dir)?;
        let raw = read_jstring(&mut env, settings_json)?;
        let parsed: AppSettings = serde_json::from_str(&raw).map_err(|e| e.to_string())?;
        let storage = AppStorage::new(dir).map_err(|e| e.to_string())?;
        storage.save_settings(&parsed).map_err(|e| e.to_string())
    })()
    .is_ok();
    if ok {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// `RustCore.loadFriendsJson(storageDir: String): String`
#[no_mangle]
pub extern "system" fn Java_com_sxvuj_streaksaver_rust_RustCore_loadFriendsJson(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    storage_dir: JString<'_>,
) -> jstring {
    let res = (|| -> Result<Vec<FriendConfig>, String> {
        let dir = read_jstring(&mut env, storage_dir)?;
        let storage = AppStorage::new(dir).map_err(|e| e.to_string())?;
        storage.load_friends().map_err(|e| e.to_string())
    })();
    match res {
        Ok(f) => json_ok(&mut env, f),
        Err(e) => json_err(&mut env, e),
    }
}

/// `RustCore.saveFriendsJson(storageDir: String, friendsJson: String): Boolean`
#[no_mangle]
pub extern "system" fn Java_com_sxvuj_streaksaver_rust_RustCore_saveFriendsJson(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    storage_dir: JString<'_>,
    friends_json: JString<'_>,
) -> jboolean {
    let ok = (|| -> Result<(), String> {
        let dir = read_jstring(&mut env, storage_dir)?;
        let raw = read_jstring(&mut env, friends_json)?;
        let parsed: Vec<FriendConfig> = serde_json::from_str(&raw).map_err(|e| e.to_string())?;
        let storage = AppStorage::new(dir).map_err(|e| e.to_string())?;
        storage.save_friends(&parsed).map_err(|e| e.to_string())
    })()
    .is_ok();
    if ok {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// `RustCore.appendRunResultJson(storageDir: String, runResultJson: String): Long`
/// Returns updated history count, or -1 on error.
#[no_mangle]
pub extern "system" fn Java_com_sxvuj_streaksaver_rust_RustCore_appendRunResultJson(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    storage_dir: JString<'_>,
    run_result_json: JString<'_>,
) -> jlong {
    let res = (|| -> Result<usize, String> {
        let dir = read_jstring(&mut env, storage_dir)?;
        let raw = read_jstring(&mut env, run_result_json)?;
        let result: StreakRunResult = serde_json::from_str(&raw).map_err(|e| e.to_string())?;
        let storage = AppStorage::new(dir).map_err(|e| e.to_string())?;
        let updated = storage
            .append_run_result(result)
            .map_err(|e| e.to_string())?;
        Ok(updated.len())
    })();
    match res {
        Ok(len) => len as jlong,
        Err(_) => -1,
    }
}

/// `RustCore.loadHistoryStatsJson(storageDir: String): String`
#[no_mangle]
pub extern "system" fn Java_com_sxvuj_streaksaver_rust_RustCore_loadHistoryStatsJson(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    storage_dir: JString<'_>,
) -> jstring {
    let res = (|| -> Result<_, String> {
        let dir = read_jstring(&mut env, storage_dir)?;
        let storage = AppStorage::new(dir).map_err(|e| e.to_string())?;
        let history = storage.load_history().map_err(|e| e.to_string())?;
        Ok(summarize_history(&history))
    })();
    match res {
        Ok(stats) => json_ok(&mut env, stats),
        Err(e) => json_err(&mut env, e),
    }
}

/// `RustCore.checkReleaseUpdateJson(currentVersion: String, githubReleaseJson: String): String`
#[no_mangle]
pub extern "system" fn Java_com_sxvuj_streaksaver_rust_RustCore_checkReleaseUpdateJson(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    current_version: JString<'_>,
    github_release_json: JString<'_>,
) -> jstring {
    let res = (|| -> Result<_, String> {
        let cur = read_jstring(&mut env, current_version)?;
        let raw = read_jstring(&mut env, github_release_json)?;
        check_latest_release_json(&cur, &raw).map_err(|e| e.to_string())
    })();
    match res {
        Ok(info) => json_ok(&mut env, info),
        Err(e) => json_err(&mut env, e),
    }
}

/// `RustCore.normalizeChatPriority(raw: String): String`
#[no_mangle]
pub extern "system" fn Java_com_sxvuj_streaksaver_rust_RustCore_normalizeChatPriority(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    raw: JString<'_>,
) -> jstring {
    let val = read_jstring(&mut env, raw)
        .map(|s| ChatPriority::from_setting(&s).as_str().to_string())
        .unwrap_or_else(|_| ChatPriority::Mixed.as_str().to_string());
    to_jstring(&mut env, &val)
}

// ---------------------------------------------------------------------------------------------
// Helpers for the exports below
// ---------------------------------------------------------------------------------------------

fn to_jbool(ok: bool) -> jboolean {
    if ok {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

fn open_storage(env: &mut JNIEnv<'_>, dir: JString<'_>) -> Result<AppStorage, String> {
    let path = read_jstring(env, dir)?;
    AppStorage::new(path).map_err(|e| e.to_string())
}

fn respond<T: Serialize>(env: &mut JNIEnv<'_>, res: Result<T, String>) -> jstring {
    match res {
        Ok(value) => json_ok(env, value),
        Err(e) => json_err(env, e),
    }
}

fn core_err(e: impl ToString) -> String {
    e.to_string()
}

/// The run in progress, if any. Only one run exists at a time (the Kotlin service enforces it).
static RUN_SESSION: Mutex<Option<RunSession>> = Mutex::new(None);

fn lock_run() -> MutexGuard<'static, Option<RunSession>> {
    // A panic elsewhere must not wedge every later run, so a poisoned lock is simply reused.
    RUN_SESSION.lock().unwrap_or_else(PoisonError::into_inner)
}

// ---------------------------------------------------------------------------------------------
// Time and release helpers
// ---------------------------------------------------------------------------------------------

/// `RustCore.isoToEpochMs(iso: String): Long`, or -1 when the text is not an RFC 3339 timestamp.
#[no_mangle]
pub extern "system" fn Java_com_sxvuj_streaksaver_rust_RustCore_isoToEpochMs(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    iso: JString<'_>,
) -> jlong {
    read_jstring(&mut env, iso)
        .ok()
        .and_then(|s| DateTime::parse_from_rfc3339(s.trim()).ok())
        .map(|d| d.timestamp_millis())
        .unwrap_or(-1)
}

/// `RustCore.latestReleaseUrl(): String`
#[no_mangle]
pub extern "system" fn Java_com_sxvuj_streaksaver_rust_RustCore_latestReleaseUrl(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jstring {
    to_jstring(&mut env, GITHUB_LATEST_RELEASE_URL)
}

/// `RustCore.computeNextRunLocalJson(storageDir: String, utcOffsetMinutes: Int): String`
#[no_mangle]
pub extern "system" fn Java_com_sxvuj_streaksaver_rust_RustCore_computeNextRunLocalJson(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    storage_dir: JString<'_>,
    utc_offset_minutes: jint,
) -> jstring {
    let res = (|| {
        let storage = open_storage(&mut env, storage_dir)?;
        let settings = storage.load_settings().map_err(core_err)?;
        compute_next_run_with_offset(&settings, Utc::now(), utc_offset_minutes).map_err(core_err)
    })();
    respond(&mut env, res)
}

/// `RustCore.setNextRunEpochMs(storageDir: String, epochMs: Long): Boolean`
/// Stores when the next automatic run is armed. A value of zero or less clears it.
#[no_mangle]
pub extern "system" fn Java_com_sxvuj_streaksaver_rust_RustCore_setNextRunEpochMs(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    storage_dir: JString<'_>,
    epoch_ms: jlong,
) -> jboolean {
    let ok = (|| -> Result<(), String> {
        let storage = open_storage(&mut env, storage_dir)?;
        let mut settings = storage.load_settings().map_err(core_err)?;
        settings.next_run_utc = if epoch_ms > 0 {
            DateTime::from_timestamp_millis(epoch_ms)
        } else {
            None
        };
        storage.save_settings(&settings).map_err(core_err)
    })()
    .is_ok();
    to_jbool(ok)
}

/// `RustCore.getNextRunEpochMs(storageDir: String): Long`, or -1 when no run is armed.
#[no_mangle]
pub extern "system" fn Java_com_sxvuj_streaksaver_rust_RustCore_getNextRunEpochMs(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    storage_dir: JString<'_>,
) -> jlong {
    (|| -> Result<jlong, String> {
        let storage = open_storage(&mut env, storage_dir)?;
        let settings = storage.load_settings().map_err(core_err)?;
        Ok(settings
            .next_run_utc
            .map(|t| t.timestamp_millis())
            .unwrap_or(-1))
    })()
    .unwrap_or(-1)
}

// ---------------------------------------------------------------------------------------------
// Dashboard and history
// ---------------------------------------------------------------------------------------------

/// `RustCore.loadDashboardJson(storageDir: String, utcOffsetMinutes: Int): String`
#[no_mangle]
pub extern "system" fn Java_com_sxvuj_streaksaver_rust_RustCore_loadDashboardJson(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    storage_dir: JString<'_>,
    utc_offset_minutes: jint,
) -> jstring {
    let res = (|| {
        let storage = open_storage(&mut env, storage_dir)?;
        storage
            .compute_dashboard(Utc::now(), utc_offset_minutes)
            .map_err(core_err)
    })();
    respond(&mut env, res)
}

/// `RustCore.loadHistoryJson(storageDir: String): String` (JSON list of runs, newest first)
#[no_mangle]
pub extern "system" fn Java_com_sxvuj_streaksaver_rust_RustCore_loadHistoryJson(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    storage_dir: JString<'_>,
) -> jstring {
    let res = (|| {
        let storage = open_storage(&mut env, storage_dir)?;
        storage.load_history().map_err(core_err)
    })();
    respond(&mut env, res)
}

/// `RustCore.clearHistory(storageDir: String): Boolean`
#[no_mangle]
pub extern "system" fn Java_com_sxvuj_streaksaver_rust_RustCore_clearHistory(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    storage_dir: JString<'_>,
) -> jboolean {
    let ok = (|| -> Result<(), String> {
        let storage = open_storage(&mut env, storage_dir)?;
        storage.clear_history().map_err(core_err)
    })()
    .is_ok();
    to_jbool(ok)
}

/// `RustCore.exportHistoryLog(storageDir: String): String` (JSON string with the log text)
#[no_mangle]
pub extern "system" fn Java_com_sxvuj_streaksaver_rust_RustCore_exportHistoryLog(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    storage_dir: JString<'_>,
) -> jstring {
    let res = (|| {
        let storage = open_storage(&mut env, storage_dir)?;
        storage.export_history_log().map_err(core_err)
    })();
    respond(&mut env, res)
}

/// `RustCore.recordSkippedRun(storageDir: String, reason: String): Boolean`
#[no_mangle]
pub extern "system" fn Java_com_sxvuj_streaksaver_rust_RustCore_recordSkippedRun(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    storage_dir: JString<'_>,
    reason: JString<'_>,
) -> jboolean {
    let ok = (|| -> Result<(), String> {
        let storage = open_storage(&mut env, storage_dir)?;
        let text = read_jstring(&mut env, reason)?;
        storage
            .record_skipped_run(&text, Utc::now())
            .map_err(core_err)
    })()
    .is_ok();
    to_jbool(ok)
}

// ---------------------------------------------------------------------------------------------
// Random messages
// ---------------------------------------------------------------------------------------------

/// `RustCore.loadRandomMessagesJson(storageDir: String): String` (JSON `RandomMessagesState`)
#[no_mangle]
pub extern "system" fn Java_com_sxvuj_streaksaver_rust_RustCore_loadRandomMessagesJson(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    storage_dir: JString<'_>,
) -> jstring {
    let res = (|| {
        let storage = open_storage(&mut env, storage_dir)?;
        storage.load_random_messages().map_err(core_err)
    })();
    respond(&mut env, res)
}

/// `RustCore.saveRandomMessagesJson(storageDir: String, messagesJson: String): String`
/// (JSON `SavedRandomMessages`). `messagesJson` is a JSON array of strings.
#[no_mangle]
pub extern "system" fn Java_com_sxvuj_streaksaver_rust_RustCore_saveRandomMessagesJson(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    storage_dir: JString<'_>,
    messages_json: JString<'_>,
) -> jstring {
    let res = (|| {
        let storage = open_storage(&mut env, storage_dir)?;
        let raw = read_jstring(&mut env, messages_json)?;
        let list: Vec<String> = serde_json::from_str(&raw)
            .map_err(|e| format!("Expected a JSON list of messages: {e}"))?;
        storage.save_random_messages(&list).map_err(core_err)
    })();
    respond(&mut env, res)
}

/// `RustCore.resetRandomMessages(storageDir: String): Boolean` (back to the built-in phrases)
#[no_mangle]
pub extern "system" fn Java_com_sxvuj_streaksaver_rust_RustCore_resetRandomMessages(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    storage_dir: JString<'_>,
) -> jboolean {
    let ok = (|| -> Result<(), String> {
        let storage = open_storage(&mut env, storage_dir)?;
        storage.reset_random_messages().map_err(core_err)
    })()
    .is_ok();
    to_jbool(ok)
}

// ---------------------------------------------------------------------------------------------
// Friends and groups
// ---------------------------------------------------------------------------------------------

/// `RustCore.exportFriendsJson(storageDir: String): String` (JSON string with the file text)
#[no_mangle]
pub extern "system" fn Java_com_sxvuj_streaksaver_rust_RustCore_exportFriendsJson(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    storage_dir: JString<'_>,
) -> jstring {
    let res = (|| {
        let storage = open_storage(&mut env, storage_dir)?;
        storage.export_friends_json().map_err(core_err)
    })();
    respond(&mut env, res)
}

/// `RustCore.importFriendsJson(storageDir: String, raw: String): String` (JSON `ImportReport`)
#[no_mangle]
pub extern "system" fn Java_com_sxvuj_streaksaver_rust_RustCore_importFriendsJson(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    storage_dir: JString<'_>,
    raw: JString<'_>,
) -> jstring {
    let res = (|| {
        let storage = open_storage(&mut env, storage_dir)?;
        let text = read_jstring(&mut env, raw)?;
        storage
            .import_friends_json(&text, Utc::now())
            .map_err(core_err)
    })();
    respond(&mut env, res)
}

/// `RustCore.clearFriends(storageDir: String): Boolean`
#[no_mangle]
pub extern "system" fn Java_com_sxvuj_streaksaver_rust_RustCore_clearFriends(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    storage_dir: JString<'_>,
) -> jboolean {
    let ok = (|| -> Result<(), String> {
        let storage = open_storage(&mut env, storage_dir)?;
        storage.clear_friends().map_err(core_err)
    })()
    .is_ok();
    to_jbool(ok)
}

/// `RustCore.addTargetJson(storageDir, name, displayName, isGroup): String` (JSON `FriendConfig`)
#[no_mangle]
pub extern "system" fn Java_com_sxvuj_streaksaver_rust_RustCore_addTargetJson(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    storage_dir: JString<'_>,
    name: JString<'_>,
    display_name: JString<'_>,
    is_group: jboolean,
) -> jstring {
    let res = (|| {
        let storage = open_storage(&mut env, storage_dir)?;
        let name = read_jstring(&mut env, name)?;
        let display = read_jstring(&mut env, display_name)?;
        storage
            .add_target(&name, &display, is_group == JNI_TRUE, Utc::now())
            .map_err(core_err)
    })();
    respond(&mut env, res)
}

/// `RustCore.updateTargetJson(storageDir, id, name, displayName): String` (JSON `FriendConfig`)
#[no_mangle]
pub extern "system" fn Java_com_sxvuj_streaksaver_rust_RustCore_updateTargetJson(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    storage_dir: JString<'_>,
    id: JString<'_>,
    name: JString<'_>,
    display_name: JString<'_>,
) -> jstring {
    let res = (|| {
        let storage = open_storage(&mut env, storage_dir)?;
        let id = read_jstring(&mut env, id)?;
        let name = read_jstring(&mut env, name)?;
        let display = read_jstring(&mut env, display_name)?;
        storage
            .update_target(&id, &name, &display)
            .map_err(core_err)
    })();
    respond(&mut env, res)
}

/// `RustCore.deleteTargetJson(storageDir, id): String` (`{"ok":true,"data":true}` on success)
#[no_mangle]
pub extern "system" fn Java_com_sxvuj_streaksaver_rust_RustCore_deleteTargetJson(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    storage_dir: JString<'_>,
    id: JString<'_>,
) -> jstring {
    let res = (|| {
        let storage = open_storage(&mut env, storage_dir)?;
        let id = read_jstring(&mut env, id)?;
        storage.delete_target(&id).map_err(core_err)?;
        Ok(true)
    })();
    respond(&mut env, res)
}

/// `RustCore.setTargetEnabledJson(storageDir, id, enabled): String`
#[no_mangle]
pub extern "system" fn Java_com_sxvuj_streaksaver_rust_RustCore_setTargetEnabledJson(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    storage_dir: JString<'_>,
    id: JString<'_>,
    enabled: jboolean,
) -> jstring {
    let res = (|| {
        let storage = open_storage(&mut env, storage_dir)?;
        let id = read_jstring(&mut env, id)?;
        storage
            .set_target_enabled(&id, enabled == JNI_TRUE)
            .map_err(core_err)?;
        Ok(true)
    })();
    respond(&mut env, res)
}

/// `RustCore.setAllEnabledJson(storageDir, isGroup, enabled): String` (data: number changed)
#[no_mangle]
pub extern "system" fn Java_com_sxvuj_streaksaver_rust_RustCore_setAllEnabledJson(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    storage_dir: JString<'_>,
    is_group: jboolean,
    enabled: jboolean,
) -> jstring {
    let res = (|| {
        let storage = open_storage(&mut env, storage_dir)?;
        storage
            .set_enabled_for_kind(is_group == JNI_TRUE, enabled == JNI_TRUE)
            .map_err(core_err)
    })();
    respond(&mut env, res)
}

// ---------------------------------------------------------------------------------------------
// TikTok session
// ---------------------------------------------------------------------------------------------

/// `RustCore.loadSessionJson(storageDir: String): String` (JSON `SessionState`)
#[no_mangle]
pub extern "system" fn Java_com_sxvuj_streaksaver_rust_RustCore_loadSessionJson(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    storage_dir: JString<'_>,
) -> jstring {
    let res = (|| {
        let storage = open_storage(&mut env, storage_dir)?;
        storage.load_session().map_err(core_err)
    })();
    respond(&mut env, res)
}

/// `RustCore.updateSessionJson(storageDir, valid, displayName): Boolean`
/// Records the result of a session check. An empty `displayName` keeps the stored name.
#[no_mangle]
pub extern "system" fn Java_com_sxvuj_streaksaver_rust_RustCore_updateSessionJson(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    storage_dir: JString<'_>,
    valid: jboolean,
    display_name: JString<'_>,
) -> jboolean {
    let ok = (|| -> Result<(), String> {
        let storage = open_storage(&mut env, storage_dir)?;
        let name = read_jstring(&mut env, display_name)?;
        let mut session = storage.load_session().map_err(core_err)?;
        session.session_valid = valid == JNI_TRUE;
        session.session_last_check = Some(Utc::now());
        if !name.trim().is_empty() {
            session.session_display_name = name.trim().to_string();
        }
        storage.save_session(&session).map_err(core_err)
    })()
    .is_ok();
    to_jbool(ok)
}

// ---------------------------------------------------------------------------------------------
// A run in progress
// ---------------------------------------------------------------------------------------------

/// `RustCore.runStartJson(storageDir: String, targetedIdsCsv: String): String`
/// Starts a run and returns the first `AutomationAction` (`inject_script` or `complete`).
#[no_mangle]
pub extern "system" fn Java_com_sxvuj_streaksaver_rust_RustCore_runStartJson(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    storage_dir: JString<'_>,
    targeted_ids_csv: JString<'_>,
) -> jstring {
    let res = (|| {
        let storage = open_storage(&mut env, storage_dir)?;
        let csv = read_jstring(&mut env, targeted_ids_csv)?;
        let ids: Vec<String> = csv
            .split(',')
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map(ToString::to_string)
            .collect();
        let (session, first) = RunSession::start(storage, &ids, Utc::now()).map_err(core_err)?;
        *lock_run() = Some(session);
        Ok(first)
    })();
    respond(&mut env, res)
}

/// `RustCore.runCallbackJson(user: String, success: Boolean, error: String): String`
/// Reports the result of the last injected script and returns the next `AutomationAction`.
#[no_mangle]
pub extern "system" fn Java_com_sxvuj_streaksaver_rust_RustCore_runCallbackJson(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
    user: JString<'_>,
    success: jboolean,
    error: JString<'_>,
) -> jstring {
    let res = (|| {
        let user = read_jstring(&mut env, user)?;
        let error = read_jstring(&mut env, error)?;
        let mut guard = lock_run();
        let run = guard
            .as_mut()
            .ok_or_else(|| "No run in progress".to_string())?;
        Ok(run.callback(&user, success == JNI_TRUE, &error, Utc::now()))
    })();
    respond(&mut env, res)
}

/// `RustCore.runFinishJson(): String` (JSON `RunSummary`)
/// Ends the run, writes friend counters and the history entry.
#[no_mangle]
pub extern "system" fn Java_com_sxvuj_streaksaver_rust_RustCore_runFinishJson(
    mut env: JNIEnv<'_>,
    _class: JClass<'_>,
) -> jstring {
    let taken = lock_run().take();
    let res = match taken {
        Some(run) => run.finish(Utc::now()).map_err(core_err),
        None => Err("No run in progress".to_string()),
    };
    respond(&mut env, res)
}
