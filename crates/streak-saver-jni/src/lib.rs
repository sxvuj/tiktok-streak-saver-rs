#![allow(non_snake_case)]
// JNI exports must be `#[no_mangle]`, which the workspace `unsafe_code` lint rejects.
#![allow(unsafe_code)]

//! Android JNI entry points for `com.sxvuj.streaksaver.rust.RustCore`.
//!
//! Follows the `erkamyaman/rust-anything` Android profile: all business logic lives in
//! `streak-saver-core`, and the thin Kotlin Android shell crosses JNI via JSON payloads
//! and primitive values without holding panicking Rust state.

use chrono::Utc;
use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jfloat, jlong, jstring, JNI_FALSE, JNI_TRUE};
use jni::JNIEnv;
use serde::Serialize;

use streak_saver_core::automation::{
    build_run_plan, compute_webview_viewport, render_automation_script,
};
use streak_saver_core::messages::MessageDeck;
use streak_saver_core::models::{AppSettings, ChatPriority, FriendConfig, StreakRunResult};
use streak_saver_core::scheduler::{
    compute_next_run, evaluate_battery_low_anticipation, evaluate_retry_schedule,
    record_clean_run_success,
};
use streak_saver_core::session::{
    guard_login_navigation, has_valid_session_cookie, resolve_user_agent,
};
use streak_saver_core::storage::{summarize_history, AppStorage};
use streak_saver_core::updater::check_latest_release_json;

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
