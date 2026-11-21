use chrono::{DateTime, NaiveDate, Utc};
use serde::{Deserialize, Serialize};

/// Default custom streak message matching `Jon2G/TiktokStreakSaver` v2.2.0.
pub const DEFAULT_MESSAGE_TEXT: &str = "Hey! Keeping our streak alive! 🔥";

/// Default run interval in hours (classic 23-hour streak window).
pub const DEFAULT_INTERVAL_HOURS: u8 = 23;

/// Maximum number of run history entries persisted on device.
pub const MAX_RUN_HISTORY: usize = 50;

/// Consecutive "User not found in chat list" failures before auto-disabling a friend.
pub const AUTO_DISABLE_FAILURE_THRESHOLD: u32 = 3;

/// Chat ordering priority when processing both DMs and group chats.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize, Default)]
#[serde(rename_all = "snake_case")]
pub enum ChatPriority {
    #[default]
    Mixed,
    FriendsFirst,
    GroupsFirst,
}

impl ChatPriority {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Mixed => "mixed",
            Self::FriendsFirst => "friends_first",
            Self::GroupsFirst => "groups_first",
        }
    }

    pub fn from_setting(value: &str) -> Self {
        match value.trim().to_ascii_lowercase().as_str() {
            "friends_first" => Self::FriendsFirst,
            "groups_first" => Self::GroupsFirst,
            _ => Self::Mixed,
        }
    }
}

/// Configuration and lifetime counters for a single TikTok friend or group chat.
/// Field names preserve JSON compatibility with `TiktokStreakSaver` v2.2.0 (`PascalCase`).
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "PascalCase")]
pub struct FriendConfig {
    pub id: String,
    pub username: String,
    #[serde(default)]
    pub display_name: String,
    #[serde(default = "default_true")]
    pub is_enabled: bool,
    #[serde(default)]
    pub last_message_sent: Option<DateTime<Utc>>,
    #[serde(default)]
    pub success_count: u32,
    #[serde(default)]
    pub failure_count: u32,
    #[serde(default)]
    pub consecutive_failures: u32,
    #[serde(default)]
    pub is_group: bool,
}

fn default_true() -> bool {
    true
}

impl FriendConfig {
    pub fn new_friend(
        id: impl Into<String>,
        username: impl Into<String>,
        display_name: impl Into<String>,
    ) -> Self {
        Self {
            id: id.into(),
            username: normalize_target_name(&username.into(), false),
            display_name: display_name.into().trim().to_string(),
            is_enabled: true,
            last_message_sent: None,
            success_count: 0,
            failure_count: 0,
            consecutive_failures: 0,
            is_group: false,
        }
    }

    pub fn new_group(id: impl Into<String>, group_name: impl Into<String>) -> Self {
        let name = group_name.into().trim().to_string();
        Self {
            id: id.into(),
            username: name.clone(),
            display_name: name,
            is_enabled: true,
            last_message_sent: None,
            success_count: 0,
            failure_count: 0,
            consecutive_failures: 0,
            is_group: true,
        }
    }

    /// Returns display label used in logs and notifications (`@username` or `Group: name`).
    pub fn display_label(&self) -> String {
        if self.is_group {
            format!("Group: {}", self.username)
        } else {
            format!("@{}", self.username.trim_start_matches('@'))
        }
    }

    /// Returns whether a message was already sent to this friend on the given UTC calendar day.
    pub fn was_sent_on_date(&self, date: NaiveDate) -> bool {
        self.last_message_sent
            .map(|ts| ts.date_naive() == date)
            .unwrap_or(false)
    }
}

/// Normalizes a TikTok username (strips leading `@` and trims) or group name (trims).
pub fn normalize_target_name(raw: &str, is_group: bool) -> String {
    let trimmed = raw.trim();
    if is_group {
        trimmed.to_string()
    } else {
        trimmed.trim_start_matches('@').trim().to_string()
    }
}

/// Per-friend outcome within a streak run.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "PascalCase")]
pub struct FriendMessageResult {
    pub friend_id: String,
    pub username: String,
    #[serde(default)]
    pub display_name: String,
    #[serde(default)]
    pub is_group: bool,
    pub failed: bool,
    #[serde(default)]
    pub error_message: Option<String>,
    pub timestamp: DateTime<Utc>,
}

/// Complete record of a single automated or manual streak run.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "PascalCase")]
pub struct StreakRunResult {
    pub run_time: DateTime<Utc>,
    /// Run duration in milliseconds.
    pub duration_ms: u64,
    #[serde(default)]
    pub is_targeted_retry: bool,
    #[serde(default)]
    pub friends_error_message: Option<String>,
    #[serde(default)]
    pub friend_results: Vec<FriendMessageResult>,
}

impl StreakRunResult {
    pub fn sent_count(&self) -> usize {
        self.friend_results.iter().filter(|r| !r.failed).count()
    }

    pub fn failed_count(&self) -> usize {
        self.friend_results.iter().filter(|r| r.failed).count()
    }

    pub fn total_count(&self) -> usize {
        self.friend_results.len()
    }

    pub fn failed_friend_ids(&self) -> Vec<String> {
        self.friend_results
            .iter()
            .filter(|r| r.failed)
            .map(|r| r.friend_id.clone())
            .collect()
    }

    /// Success rate in `[0.0, 100.0]`. Returns `100.0` if no friends were attempted without error.
    pub fn success_rate_pct(&self) -> f64 {
        let total = self.total_count();
        if total == 0 {
            if self.friends_error_message.is_some() {
                0.0
            } else {
                100.0
            }
        } else {
            (self.sent_count() as f64 / total as f64) * 100.0
        }
    }
}

/// Aggregate statistics computed from persisted `run_history`.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
pub struct HistoryStats {
    pub total_runs: usize,
    pub total_messages_sent: usize,
    pub total_messages_failed: usize,
    pub overall_success_rate_pct: f64,
    pub last_run_time: Option<DateTime<Utc>>,
}

/// Persistent user settings matching `SettingsService` keys in v2.2.0.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct AppSettings {
    pub message_text: String,
    pub randomize_normal_messages: bool,
    pub interval_hours: u8,
    pub last_run: Option<DateTime<Utc>>,
    pub is_scheduled: bool,
    pub skip_unreachable_users: bool,
    pub chat_priority: ChatPriority,
    pub use_fixed_time: bool,
    pub fixed_time_hour: u8,
    pub fixed_time_minute: u8,
    pub retry_count_date: Option<NaiveDate>,
    pub retry_count_today: u32,
    pub max_daily_retries: u32,
    pub last_run_failed: bool,
    pub last_run_failure_reason: Option<String>,
    pub battery_anticipation_date: Option<NaiveDate>,
    pub send_on_battery_low: bool,
}

impl Default for AppSettings {
    fn default() -> Self {
        Self {
            message_text: DEFAULT_MESSAGE_TEXT.to_string(),
            randomize_normal_messages: false,
            interval_hours: DEFAULT_INTERVAL_HOURS,
            last_run: None,
            is_scheduled: false,
            skip_unreachable_users: true,
            chat_priority: ChatPriority::Mixed,
            use_fixed_time: false,
            fixed_time_hour: 9,
            fixed_time_minute: 0,
            retry_count_date: None,
            retry_count_today: 0,
            max_daily_retries: 3,
            last_run_failed: false,
            last_run_failure_reason: None,
            battery_anticipation_date: None,
            send_on_battery_low: false,
        }
    }
}

/// Persistent TikTok WebView session metadata matching `SessionService` in v2.2.0.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct SessionState {
    pub session_valid: bool,
    pub session_last_check: Option<DateTime<Utc>>,
    pub session_display_name: String,
    pub session_login_ua: Option<String>,
    pub session_profile_photo: Option<String>,
}

impl Default for SessionState {
    fn default() -> Self {
        Self {
            session_valid: false,
            session_last_check: None,
            session_display_name: "User".to_string(),
            session_login_ua: None,
            session_profile_photo: None,
        }
    }
}

/// GitHub Release update information returned by `updater::check_latest_release_json`.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct UpdateInfo {
    pub current_version: String,
    pub latest_version: String,
    pub update_available: bool,
    pub release_notes: String,
    pub apk_download_url: Option<String>,
    pub release_page_url: String,
}
