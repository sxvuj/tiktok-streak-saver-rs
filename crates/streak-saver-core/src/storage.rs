use std::fs;
use std::path::{Path, PathBuf};

use crate::error::{Result, StreakError};
use crate::models::{
    AppSettings, FriendConfig, HistoryStats, SessionState, StreakRunResult, MAX_RUN_HISTORY,
};

const SETTINGS_FILE: &str = "settings.json";
const FRIENDS_FILE: &str = "friends_list.json";
const HISTORY_FILE: &str = "run_history.json";
const SESSION_FILE: &str = "session_state.json";

/// Atomic file-backed persistence store for settings, friends, run history, and session state.
///
/// Writes to `<file>.tmp` and atomically renames into place, matching the `.tmp` durability
/// pattern observed in `Jon2G/TiktokStreakSaver` v2.2.0.
#[derive(Debug, Clone)]
pub struct AppStorage {
    root: PathBuf,
}

impl AppStorage {
    pub fn new(root: impl AsRef<Path>) -> Result<Self> {
        let root_path = root.as_ref().to_path_buf();
        fs::create_dir_all(&root_path).map_err(|e| StreakError::Io(e.to_string()))?;
        Ok(Self { root: root_path })
    }

    pub fn load_settings(&self) -> Result<AppSettings> {
        self.read_json_or_default(SETTINGS_FILE)
    }

    pub fn save_settings(&self, settings: &AppSettings) -> Result<()> {
        self.write_json_atomic(SETTINGS_FILE, settings)
    }

    pub fn load_friends(&self) -> Result<Vec<FriendConfig>> {
        self.read_json_or_default(FRIENDS_FILE)
    }

    pub fn save_friends(&self, friends: &[FriendConfig]) -> Result<()> {
        self.write_json_atomic(FRIENDS_FILE, friends)
    }

    pub fn load_history(&self) -> Result<Vec<StreakRunResult>> {
        self.read_json_or_default(HISTORY_FILE)
    }

    /// Appends a run result to the front of `run_history` and enforces the 50-run cap.
    pub fn append_run_result(&self, result: StreakRunResult) -> Result<Vec<StreakRunResult>> {
        let mut history = self.load_history()?;
        history.insert(0, result);
        history.truncate(MAX_RUN_HISTORY);
        self.write_json_atomic(HISTORY_FILE, &history)?;
        Ok(history)
    }

    pub fn clear_history(&self) -> Result<()> {
        let empty: Vec<StreakRunResult> = Vec::new();
        self.write_json_atomic(HISTORY_FILE, &empty)
    }

    pub fn load_session(&self) -> Result<SessionState> {
        self.read_json_or_default(SESSION_FILE)
    }

    pub fn save_session(&self, session: &SessionState) -> Result<()> {
        self.write_json_atomic(SESSION_FILE, session)
    }

    /// Exports friends list as pretty-printed JSON compatible with v2.2.0 `FriendsPage` import/export.
    pub fn export_friends_json(&self) -> Result<String> {
        let friends = self.load_friends()?;
        serde_json::to_string_pretty(&friends)
            .map_err(|e| StreakError::Serialization(e.to_string()))
    }

    /// Imports friends from a JSON string, deduplicating by `(is_group, lowercase username)`.
    pub fn import_friends_json(&self, raw_json: &str) -> Result<usize> {
        let incoming: Vec<FriendConfig> = serde_json::from_str(raw_json)
            .map_err(|e| StreakError::Serialization(e.to_string()))?;
        let mut existing = self.load_friends()?;
        let mut added = 0usize;

        for mut item in incoming {
            let norm = item.username.trim().trim_start_matches('@').to_string();
            if norm.is_empty() {
                continue;
            }
            if !item.is_group {
                item.username = norm;
            }
            let duplicate = existing.iter().any(|e| {
                e.is_group == item.is_group && e.username.eq_ignore_ascii_case(&item.username)
            });
            if !duplicate {
                existing.push(item);
                added += 1;
            }
        }

        self.save_friends(&existing)?;
        Ok(added)
    }

    /// Computes aggregate success-rate stats over persisted run history.
    pub fn compute_history_stats(&self) -> Result<HistoryStats> {
        let history = self.load_history()?;
        Ok(summarize_history(&history))
    }

    /// Exports run history as a human-readable diagnostic log string.
    pub fn export_history_log(&self) -> Result<String> {
        let history = self.load_history()?;
        let mut out = String::new();
        for run in &history {
            out.push_str(&format!(
                "[{}] targeted={} duration={}ms sent={} failed={}\n",
                run.run_time.to_rfc3339(),
                run.is_targeted_retry,
                run.duration_ms,
                run.sent_count(),
                run.failed_count()
            ));
            for r in &run.friend_results {
                let status = if r.failed { "FAIL" } else { "OK" };
                let err = r.error_message.as_deref().unwrap_or("");
                out.push_str(&format!(
                    "  - {} {} ({}) {}\n",
                    status,
                    if r.is_group { "GROUP" } else { "DM" },
                    r.username,
                    err
                ));
            }
        }
        Ok(out)
    }

    fn read_json_or_default<T>(&self, filename: &str) -> Result<T>
    where
        T: serde::de::DeserializeOwned + Default,
    {
        let path = self.root.join(filename);
        if !path.exists() {
            return Ok(T::default());
        }
        let raw = fs::read_to_string(&path).map_err(|e| StreakError::Io(e.to_string()))?;
        serde_json::from_str(&raw).map_err(|e| StreakError::Serialization(e.to_string()))
    }

    fn write_json_atomic<T: serde::Serialize + ?Sized>(
        &self,
        filename: &str,
        value: &T,
    ) -> Result<()> {
        let path = self.root.join(filename);
        let tmp_path = self.root.join(format!("{filename}.tmp"));
        let serialized = serde_json::to_string_pretty(value)
            .map_err(|e| StreakError::Serialization(e.to_string()))?;
        fs::write(&tmp_path, serialized).map_err(|e| StreakError::Io(e.to_string()))?;
        fs::rename(&tmp_path, &path).map_err(|e| StreakError::Io(e.to_string()))?;
        Ok(())
    }
}

/// Pure function computing aggregate run history metrics.
pub fn summarize_history(history: &[StreakRunResult]) -> HistoryStats {
    let total_runs = history.len();
    let total_messages_sent: usize = history.iter().map(StreakRunResult::sent_count).sum();
    let total_messages_failed: usize = history.iter().map(StreakRunResult::failed_count).sum();
    let attempted = total_messages_sent + total_messages_failed;
    let overall_success_rate_pct = if attempted == 0 {
        100.0
    } else {
        (total_messages_sent as f64 / attempted as f64) * 100.0
    };
    let last_run_time = history.first().map(|r| r.run_time);

    HistoryStats {
        total_runs,
        total_messages_sent,
        total_messages_failed,
        overall_success_rate_pct,
        last_run_time,
    }
}

/// Searches friends by username or display name (case-insensitive substring match).
pub fn search_friends<'a>(friends: &'a [FriendConfig], query: &str) -> Vec<&'a FriendConfig> {
    let q = query.trim().to_ascii_lowercase();
    if q.is_empty() {
        return friends.iter().collect();
    }
    friends
        .iter()
        .filter(|f| {
            f.username.to_ascii_lowercase().contains(&q)
                || f.display_name.to_ascii_lowercase().contains(&q)
        })
        .collect()
}

/// Bulk enables or disables all friends in place.
pub fn set_all_friends_enabled(friends: &mut [FriendConfig], enabled: bool) {
    for f in friends {
        f.is_enabled = enabled;
    }
}
