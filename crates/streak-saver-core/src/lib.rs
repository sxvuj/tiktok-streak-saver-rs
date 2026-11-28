#![forbid(unsafe_code)]

//! Pure Rust core engine for TikTok Streak Saver.
//!
//! Ported from `Jon2G/TiktokStreakSaver` v2.2.0 (.NET 9 MAUI) following the
//! `erkamyaman/rust-anything` parity-first architecture: all business logic,
//! scheduling, session guards, retry state machines, JS template rendering,
//! persistence, and update checks live in pure, unit-tested Rust with zero
//! `unsafe` code.

pub mod automation;
pub mod error;
pub mod messages;
pub mod models;
pub mod run;
pub mod scheduler;
pub mod session;
pub mod storage;
pub mod updater;

pub use automation::{
    AutomationAction, AutomationConfig, RunPlan, RunStateMachine, ViewportSize, DETECT_GROUPS_JS,
    TIKTOK_AUTOMATION_JS,
};
pub use error::{Result, StreakError};
pub use messages::{
    builtin_messages, sanitize_message, sanitize_pool, MessageDeck, SanitizedPool,
    MAX_MESSAGE_CHARS, MAX_RANDOM_MESSAGES, RANDOMIZED_STREAK_MESSAGES,
};
pub use models::{
    AppSettings, ChatPriority, DashboardState, FriendConfig, FriendMessageResult, HistoryStats,
    ImportReport, RandomMessagesState, RunSummary, SavedRandomMessages, SessionState,
    StreakRunResult, UpdateInfo,
};
pub use run::RunSession;
pub use scheduler::{
    compute_next_run, compute_next_run_with_offset, evaluate_battery_low_anticipation,
    evaluate_retry_schedule, RetryDecision, ScheduleDecision,
};
pub use session::{
    guard_login_navigation, has_valid_session_cookie, resolve_user_agent, LoginNavDecision,
    DEFAULT_DESKTOP_USER_AGENT, DEFAULT_MOBILE_USER_AGENT, TIKTOK_LOGIN_URL, TIKTOK_MESSAGES_URL,
};
pub use storage::AppStorage;
pub use updater::{check_latest_release_json, compare_semver, parse_semver};
