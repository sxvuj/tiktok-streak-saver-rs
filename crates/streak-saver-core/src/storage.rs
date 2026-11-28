use std::fs;
use std::path::{Path, PathBuf};

use chrono::{DateTime, Datelike, Duration, NaiveDate, NaiveDateTime, TimeZone, Utc};
use serde_json::{Map, Value};

use crate::error::{Result, StreakError};
use crate::messages::sanitize_pool;
use crate::models::{
    builtin_message_count, normalize_target_name, AppSettings, DashboardState, FriendConfig,
    HistoryStats, ImportReport, RandomMessagesState, SavedRandomMessages, SessionState,
    StreakRunResult, MAX_RUN_HISTORY,
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

    /// The random-message pool as the editor shows it (the built-in phrases until the user edits).
    pub fn load_random_messages(&self) -> Result<RandomMessagesState> {
        let settings = self.load_settings()?;
        Ok(RandomMessagesState {
            custom: settings.random_messages.is_some(),
            messages: settings.effective_random_messages(),
            builtin_count: builtin_message_count(),
        })
    }

    /// Replaces the random-message pool with the cleaned version of `raw` (see
    /// [`sanitize_pool`]). An empty result is kept as the user's choice: random mode then sends
    /// the plain custom message instead.
    pub fn save_random_messages(&self, raw: &[String]) -> Result<SavedRandomMessages> {
        let cleaned = sanitize_pool(raw);
        let mut settings = self.load_settings()?;
        settings.random_messages = Some(cleaned.messages.clone());
        self.save_settings(&settings)?;
        Ok(SavedRandomMessages {
            messages: cleaned.messages,
            dropped: cleaned.dropped,
        })
    }

    /// Goes back to the built-in phrases.
    pub fn reset_random_messages(&self) -> Result<()> {
        let mut settings = self.load_settings()?;
        settings.random_messages = None;
        self.save_settings(&settings)
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
    ///
    /// The reader is deliberately tolerant so an export from the original app loads even if its
    /// details differ slightly: field names are matched case-insensitively, `null` values and
    /// missing fields get defaults, timestamps may omit the time zone, and the list may be
    /// wrapped in an object (`{"Friends": [...]}`). Entries without a usable name are counted
    /// as invalid instead of aborting the whole import.
    pub fn import_friends_json(&self, raw_json: &str, now: DateTime<Utc>) -> Result<ImportReport> {
        let value: Value = serde_json::from_str(raw_json)
            .map_err(|e| StreakError::Serialization(format!("Not valid JSON: {e}")))?;
        let items = extract_import_items(&value).ok_or_else(|| {
            StreakError::InvalidFriend("Expected a JSON list of friends and groups".to_string())
        })?;

        let mut existing = self.load_friends()?;
        let mut report = ImportReport::default();

        for item in items {
            let Some(mut parsed) = parse_import_item(item) else {
                report.skipped_invalid += 1;
                continue;
            };
            let duplicate = existing.iter().any(|e| {
                e.is_group == parsed.is_group && e.username.eq_ignore_ascii_case(&parsed.username)
            });
            if duplicate {
                report.skipped_duplicates += 1;
                continue;
            }
            if parsed.id.trim().is_empty() || existing.iter().any(|e| e.id == parsed.id) {
                parsed.id = generate_target_id(&existing, now);
            }
            existing.push(parsed);
            report.added += 1;
        }

        self.save_friends(&existing)?;
        Ok(report)
    }

    /// Removes every friend and group.
    pub fn clear_friends(&self) -> Result<()> {
        let empty: Vec<FriendConfig> = Vec::new();
        self.save_friends(&empty)
    }

    /// Adds a friend (`@username`) or a group chat and returns the stored entry.
    pub fn add_target(
        &self,
        name: &str,
        display_name: &str,
        is_group: bool,
        now: DateTime<Utc>,
    ) -> Result<FriendConfig> {
        let clean = normalize_target_name(name, is_group);
        if clean.is_empty() {
            return Err(StreakError::InvalidFriend(
                "The name cannot be empty".to_string(),
            ));
        }
        let mut friends = self.load_friends()?;
        if friends
            .iter()
            .any(|f| f.is_group == is_group && f.username.eq_ignore_ascii_case(&clean))
        {
            return Err(StreakError::InvalidFriend(format!(
                "{clean} is already in the list"
            )));
        }

        let id = generate_target_id(&friends, now);
        let entry = if is_group {
            FriendConfig::new_group(id, clean)
        } else {
            let shown = if display_name.trim().is_empty() {
                clean.clone()
            } else {
                display_name.trim().to_string()
            };
            FriendConfig::new_friend(id, clean, shown)
        };
        friends.push(entry.clone());
        self.save_friends(&friends)?;
        Ok(entry)
    }

    /// Renames a friend or group chat. The type (friend or group) cannot change.
    pub fn update_target(&self, id: &str, name: &str, display_name: &str) -> Result<FriendConfig> {
        let mut friends = self.load_friends()?;
        let Some(index) = friends.iter().position(|f| f.id == id) else {
            return Err(StreakError::FriendNotFound(id.to_string()));
        };
        let is_group = friends[index].is_group;
        let clean = normalize_target_name(name, is_group);
        if clean.is_empty() {
            return Err(StreakError::InvalidFriend(
                "The name cannot be empty".to_string(),
            ));
        }
        if friends.iter().any(|f| {
            f.id != id && f.is_group == is_group && f.username.eq_ignore_ascii_case(&clean)
        }) {
            return Err(StreakError::InvalidFriend(format!(
                "{clean} is already in the list"
            )));
        }

        let entry = &mut friends[index];
        entry.display_name = if is_group || display_name.trim().is_empty() {
            clean.clone()
        } else {
            display_name.trim().to_string()
        };
        entry.username = clean;
        let updated = entry.clone();
        self.save_friends(&friends)?;
        Ok(updated)
    }

    /// Deletes one friend or group chat.
    pub fn delete_target(&self, id: &str) -> Result<()> {
        let mut friends = self.load_friends()?;
        let before = friends.len();
        friends.retain(|f| f.id != id);
        if friends.len() == before {
            return Err(StreakError::FriendNotFound(id.to_string()));
        }
        self.save_friends(&friends)
    }

    /// Switches one friend or group chat on or off. Switching on clears the failure streak, so a
    /// chat that was auto-disabled gets a fresh start.
    pub fn set_target_enabled(&self, id: &str, enabled: bool) -> Result<()> {
        let mut friends = self.load_friends()?;
        let Some(entry) = friends.iter_mut().find(|f| f.id == id) else {
            return Err(StreakError::FriendNotFound(id.to_string()));
        };
        entry.is_enabled = enabled;
        if enabled {
            entry.consecutive_failures = 0;
        }
        self.save_friends(&friends)
    }

    /// Switches every friend (or every group) on or off and returns how many entries changed.
    pub fn set_enabled_for_kind(&self, is_group: bool, enabled: bool) -> Result<usize> {
        let mut friends = self.load_friends()?;
        let mut changed = 0usize;
        for entry in friends.iter_mut().filter(|f| f.is_group == is_group) {
            if entry.is_enabled != enabled {
                changed += 1;
            }
            entry.is_enabled = enabled;
            if enabled {
                entry.consecutive_failures = 0;
            }
        }
        self.save_friends(&friends)?;
        Ok(changed)
    }

    /// Writes a history entry for a run that could not start (offline, not logged in).
    pub fn record_skipped_run(&self, reason: &str, now: DateTime<Utc>) -> Result<()> {
        let entry = StreakRunResult {
            run_time: now,
            duration_ms: 0,
            is_targeted_retry: false,
            friends_error_message: Some(reason.to_string()),
            friend_results: Vec::new(),
        };
        self.append_run_result(entry).map(|_| ())
    }

    /// Numbers for the Home screen. `utc_offset_minutes` is the phone's offset from UTC, so
    /// "today" means the user's own calendar day.
    pub fn compute_dashboard(
        &self,
        now: DateTime<Utc>,
        utc_offset_minutes: i32,
    ) -> Result<DashboardState> {
        let settings = self.load_settings()?;
        let friends = self.load_friends()?;
        let history = self.load_history()?;

        let offset = Duration::minutes(i64::from(utc_offset_minutes));
        let local_day = |ts: DateTime<Utc>| -> NaiveDate { (ts + offset).date_naive() };
        let today = local_day(now);

        let enabled: Vec<&FriendConfig> = friends.iter().filter(|f| f.is_enabled).collect();
        let sent_today = enabled
            .iter()
            .filter(|f| f.last_message_sent.map(local_day) == Some(today))
            .count();

        let (mut sent, mut failed) = (0usize, 0usize);
        for run in history.iter().filter(|r| local_day(r.run_time) == today) {
            sent += run.sent_count();
            failed += run.failed_count();
        }
        let attempted = sent + failed;
        let success_rate_today_pct = if attempted == 0 {
            None
        } else {
            Some((sent as f64 / attempted as f64) * 100.0)
        };

        Ok(DashboardState {
            total_targets: friends.len(),
            enabled_targets: enabled.len(),
            friends_total: friends.iter().filter(|f| !f.is_group).count(),
            groups_total: friends.iter().filter(|f| f.is_group).count(),
            sent_today,
            remaining_today: enabled.len().saturating_sub(sent_today),
            success_rate_today_pct,
            last_run_time: history.first().map(|r| r.run_time),
            message_text: settings.message_text,
        })
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

/// Builds an ID that is not used by any existing entry.
fn generate_target_id(existing: &[FriendConfig], now: DateTime<Utc>) -> String {
    let base = now.timestamp_millis();
    let mut n = 0u32;
    loop {
        let candidate = if n == 0 {
            format!("{base:x}")
        } else {
            format!("{base:x}-{n}")
        };
        if !existing.iter().any(|f| f.id == candidate) {
            return candidate;
        }
        n += 1;
    }
}

/// Lower-cases a JSON key and drops underscores and dashes, so `IsEnabled`, `isEnabled` and
/// `is_enabled` all compare equal.
fn normalized_key(key: &str) -> String {
    key.chars()
        .filter(|c| *c != '_' && *c != '-')
        .flat_map(char::to_lowercase)
        .collect()
}

fn field<'a>(obj: &'a Map<String, Value>, names: &[&str]) -> Option<&'a Value> {
    obj.iter()
        .find(|(k, _)| names.contains(&normalized_key(k).as_str()))
        .map(|(_, v)| v)
}

fn extract_import_items(value: &Value) -> Option<Vec<&Value>> {
    match value {
        Value::Array(items) => Some(items.iter().collect()),
        Value::Object(map) => {
            const WRAPPERS: [&str; 6] = ["friends", "data", "items", "list", "targets", "chats"];
            for (key, inner) in map {
                if let Value::Array(items) = inner {
                    if WRAPPERS.contains(&normalized_key(key).as_str()) {
                        return Some(items.iter().collect());
                    }
                }
            }
            field(map, &["username", "name"]).map(|_| vec![value])
        }
        _ => None,
    }
}

fn as_count(value: Option<&Value>) -> u32 {
    value
        .and_then(Value::as_u64)
        .map(|n| u32::try_from(n).unwrap_or(u32::MAX))
        .unwrap_or(0)
}

/// Parses an ISO timestamp. A timestamp without a time zone is read as UTC. The year 0001
/// that .NET writes for "never" becomes `None`.
fn parse_lenient_timestamp(raw: &str) -> Option<DateTime<Utc>> {
    let raw = raw.trim();
    let parsed = DateTime::parse_from_rfc3339(raw)
        .map(|d| d.with_timezone(&Utc))
        .ok()
        .or_else(|| {
            NaiveDateTime::parse_from_str(raw, "%Y-%m-%dT%H:%M:%S%.f")
                .ok()
                .map(|n| Utc.from_utc_datetime(&n))
        })?;
    if parsed.year() < 2000 {
        None
    } else {
        Some(parsed)
    }
}

fn parse_import_item(value: &Value) -> Option<FriendConfig> {
    let obj = value.as_object()?;
    let is_group = field(obj, &["isgroup", "group"])
        .and_then(Value::as_bool)
        .unwrap_or(false);
    let raw_name = field(obj, &["username", "uniqueid", "name"]).and_then(Value::as_str)?;
    let username = normalize_target_name(raw_name, is_group);
    if username.is_empty() {
        return None;
    }

    let id = match field(obj, &["id"]) {
        Some(Value::String(s)) => s.trim().to_string(),
        Some(Value::Number(n)) => n.to_string(),
        _ => String::new(),
    };
    let display_name = if is_group {
        username.clone()
    } else {
        field(obj, &["displayname", "nickname"])
            .and_then(Value::as_str)
            .map(str::trim)
            .filter(|s| !s.is_empty())
            .map(ToString::to_string)
            .unwrap_or_else(|| username.clone())
    };

    Some(FriendConfig {
        id,
        username,
        display_name,
        is_enabled: field(obj, &["isenabled", "enabled"])
            .and_then(Value::as_bool)
            .unwrap_or(true),
        last_message_sent: field(obj, &["lastmessagesent"])
            .and_then(Value::as_str)
            .and_then(parse_lenient_timestamp),
        success_count: as_count(field(obj, &["successcount"])),
        failure_count: as_count(field(obj, &["failurecount"])),
        consecutive_failures: as_count(field(obj, &["consecutivefailures"])),
        is_group,
    })
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
mod tests {
    use super::*;
    use crate::models::FriendMessageResult;

    fn temp_store(tag: &str) -> (AppStorage, PathBuf) {
        let dir =
            std::env::temp_dir().join(format!("streak-saver-test-{}-{tag}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        (AppStorage::new(&dir).unwrap(), dir)
    }

    fn at(day: u32, hour: u32, min: u32) -> DateTime<Utc> {
        Utc.with_ymd_and_hms(2026, 11, day, hour, min, 0)
            .single()
            .unwrap()
    }

    #[test]
    fn add_target_normalizes_names_and_rejects_duplicates() {
        let (store, dir) = temp_store("add");
        let now = at(22, 1, 0);

        let a = store
            .add_target("  @Aaron.Shasenka ", "", false, now)
            .unwrap();
        assert_eq!(a.username, "Aaron.Shasenka");
        assert_eq!(a.display_name, "Aaron.Shasenka");
        assert!(a.is_enabled && !a.is_group);

        let dup = store.add_target("@aaron.shasenka", "", false, now);
        assert!(matches!(dup, Err(StreakError::InvalidFriend(_))));
        assert!(store.add_target("   ", "", false, now).is_err());

        // A group may share a name with a friend.
        let g = store.add_target("Aaron.Shasenka", "", true, now).unwrap();
        assert!(g.is_group);
        assert_ne!(a.id, g.id);
        assert_eq!(store.load_friends().unwrap().len(), 2);
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn update_and_delete_target() {
        let (store, dir) = temp_store("update");
        let now = at(22, 1, 0);
        let a = store.add_target("alice", "Alice", false, now).unwrap();
        let b = store.add_target("bob", "Bob", false, now).unwrap();

        let renamed = store.update_target(&a.id, "@alice2", "Alice Two").unwrap();
        assert_eq!(renamed.username, "alice2");
        assert_eq!(renamed.display_name, "Alice Two");
        assert!(store.update_target(&a.id, "bob", "").is_err());
        assert!(matches!(
            store.update_target("missing", "x", ""),
            Err(StreakError::FriendNotFound(_))
        ));

        store.delete_target(&b.id).unwrap();
        assert!(matches!(
            store.delete_target(&b.id),
            Err(StreakError::FriendNotFound(_))
        ));
        assert_eq!(store.load_friends().unwrap().len(), 1);
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn enabling_resets_failure_streak_and_bulk_toggle_is_per_kind() {
        let (store, dir) = temp_store("enable");
        let now = at(22, 1, 0);
        let f = store.add_target("ghost", "", false, now).unwrap();
        let g = store.add_target("Squad", "", true, now).unwrap();

        let mut friends = store.load_friends().unwrap();
        friends[0].is_enabled = false;
        friends[0].consecutive_failures = 3;
        store.save_friends(&friends).unwrap();

        store.set_target_enabled(&f.id, true).unwrap();
        let after = store.load_friends().unwrap();
        assert!(after[0].is_enabled);
        assert_eq!(after[0].consecutive_failures, 0);

        let changed = store.set_enabled_for_kind(true, false).unwrap();
        assert_eq!(changed, 1);
        let after = store.load_friends().unwrap();
        assert!(after.iter().find(|x| x.id == f.id).unwrap().is_enabled);
        assert!(!after.iter().find(|x| x.id == g.id).unwrap().is_enabled);
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn import_reads_an_original_style_export() {
        let (store, dir) = temp_store("import");
        store
            .add_target("existing", "", false, at(22, 1, 0))
            .unwrap();

        let raw = r#"[
            {"Id":"7b1","Username":"@aaron.shasenka.fa","DisplayName":"Aaron","IsEnabled":true,
             "LastMessageSent":"2026-11-21T20:56:00.123","SuccessCount":12,"FailureCount":1,
             "ConsecutiveFailures":0,"IsGroup":false},
            {"Id":"7b2","Username":"0xvae","DisplayName":null,"IsEnabled":false,
             "LastMessageSent":"0001-01-01T00:00:00","IsGroup":false},
            {"Id":9,"Username":"grup baru 9c","IsGroup":true},
            {"Id":"7b4","Username":"EXISTING","IsGroup":false},
            {"Id":"7b5","Username":"   ","IsGroup":false},
            "not an object"
        ]"#;
        let report = store.import_friends_json(raw, at(22, 2, 0)).unwrap();
        assert_eq!(
            report,
            ImportReport {
                added: 3,
                skipped_duplicates: 1,
                skipped_invalid: 2
            }
        );

        let friends = store.load_friends().unwrap();
        let aaron = friends
            .iter()
            .find(|f| f.username == "aaron.shasenka.fa")
            .unwrap();
        assert_eq!(aaron.id, "7b1");
        assert_eq!(aaron.success_count, 12);
        assert_eq!(
            aaron.last_message_sent,
            Some(at(21, 20, 56)).map(|t| t + Duration::milliseconds(123))
        );

        let vae = friends.iter().find(|f| f.username == "0xvae").unwrap();
        assert_eq!(vae.display_name, "0xvae");
        assert!(!vae.is_enabled);
        assert_eq!(vae.last_message_sent, None);

        let group = friends.iter().find(|f| f.is_group).unwrap();
        assert_eq!(group.id, "9");
        assert_eq!(group.username, "grup baru 9c");
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn import_accepts_wrapped_lists_and_reassigns_clashing_ids() {
        let (store, dir) = temp_store("wrapped");
        let first = store.add_target("first", "", false, at(22, 1, 0)).unwrap();
        let raw = format!(
            r#"{{"friends":[{{"id":"{}","username":"second"}}]}}"#,
            first.id
        );
        let report = store.import_friends_json(&raw, at(22, 3, 0)).unwrap();
        assert_eq!(report.added, 1);
        let friends = store.load_friends().unwrap();
        assert_eq!(friends.len(), 2);
        assert_ne!(friends[0].id, friends[1].id);
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn import_rejects_text_that_is_not_a_friend_list() {
        let (store, dir) = temp_store("reject");
        assert!(store.import_friends_json("not json", at(22, 1, 0)).is_err());
        assert!(store.import_friends_json("42", at(22, 1, 0)).is_err());
        assert!(store
            .import_friends_json(r#"{"a":1}"#, at(22, 1, 0))
            .is_err());
        assert!(store.load_friends().unwrap().is_empty());
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn export_then_import_round_trips() {
        let (src, src_dir) = temp_store("rt-src");
        let (dst, dst_dir) = temp_store("rt-dst");
        let now = at(22, 1, 0);
        src.add_target("alice", "Alice", false, now).unwrap();
        src.add_target("Squad", "", true, now).unwrap();
        let exported = src.export_friends_json().unwrap();
        let report = dst.import_friends_json(&exported, now).unwrap();
        assert_eq!(report.added, 2);
        assert_eq!(src.load_friends().unwrap(), dst.load_friends().unwrap());
        let _ = fs::remove_dir_all(src_dir);
        let _ = fs::remove_dir_all(dst_dir);
    }

    #[test]
    fn dashboard_uses_the_local_calendar_day() {
        let (store, dir) = temp_store("dash");
        let now = at(22, 1, 25); // 08:25 on the 22nd in UTC+7
        store.add_target("a", "", false, now).unwrap();
        store.add_target("b", "", false, now).unwrap();
        store.add_target("Squad", "", true, now).unwrap();

        let mut friends = store.load_friends().unwrap();
        // 20:56 on the 21st local time: yesterday for a user in UTC+7.
        friends[0].last_message_sent = Some(at(21, 13, 56));
        // 07:30 on the 22nd local time: today.
        friends[1].last_message_sent = Some(at(21, 0, 30) + Duration::hours(24));
        store.save_friends(&friends).unwrap();

        let run = StreakRunResult {
            run_time: at(22, 0, 30),
            duration_ms: 1000,
            is_targeted_retry: false,
            friends_error_message: None,
            friend_results: vec![
                FriendMessageResult {
                    friend_id: "x".into(),
                    username: "b".into(),
                    display_name: "b".into(),
                    is_group: false,
                    failed: false,
                    error_message: None,
                    timestamp: at(22, 0, 30),
                },
                FriendMessageResult {
                    friend_id: "y".into(),
                    username: "c".into(),
                    display_name: "c".into(),
                    is_group: false,
                    failed: true,
                    error_message: Some("boom".into()),
                    timestamp: at(22, 0, 31),
                },
            ],
        };
        store.append_run_result(run).unwrap();

        let dash = store.compute_dashboard(now, 420).unwrap();
        assert_eq!(dash.total_targets, 3);
        assert_eq!(dash.friends_total, 2);
        assert_eq!(dash.groups_total, 1);
        assert_eq!(dash.enabled_targets, 3);
        assert_eq!(dash.sent_today, 1);
        assert_eq!(dash.remaining_today, 2);
        assert_eq!(dash.success_rate_today_pct, Some(50.0));
        assert_eq!(dash.last_run_time, Some(at(22, 0, 30)));

        // Seen from UTC the same data lands on a different "today".
        let utc = store.compute_dashboard(now, 0).unwrap();
        assert_eq!(utc.sent_today, 1);
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn dashboard_of_an_empty_store_is_all_zero() {
        let (store, dir) = temp_store("empty");
        let dash = store.compute_dashboard(at(22, 1, 0), 420).unwrap();
        assert_eq!(dash.total_targets, 0);
        assert_eq!(dash.sent_today, 0);
        assert_eq!(dash.success_rate_today_pct, None);
        assert_eq!(dash.last_run_time, None);
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn skipped_runs_are_logged_without_counting_as_messages() {
        let (store, dir) = temp_store("skipped");
        store
            .record_skipped_run("Skipped: no Wi-Fi or mobile data", at(22, 1, 0))
            .unwrap();
        let history = store.load_history().unwrap();
        assert_eq!(history.len(), 1);
        assert_eq!(
            history[0].friends_error_message.as_deref(),
            Some("Skipped: no Wi-Fi or mobile data")
        );
        let stats = store.compute_history_stats().unwrap();
        assert_eq!(stats.total_runs, 1);
        assert_eq!(stats.total_messages_sent, 0);
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn old_settings_files_without_new_fields_still_load() {
        let (store, dir) = temp_store("oldsettings");
        fs::write(
            dir.join("settings.json"),
            r#"{"message_text":"hi","interval_hours":12}"#,
        )
        .unwrap();
        let s = store.load_settings().unwrap();
        assert_eq!(s.message_text, "hi");
        assert_eq!(s.interval_hours, 12);
        assert_eq!(s.next_run_utc, None);
        assert!(s.skip_unreachable_users);
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn random_messages_default_to_the_built_in_pool() {
        let (store, dir) = temp_store("rm-default");
        let state = store.load_random_messages().unwrap();
        assert!(!state.custom);
        assert_eq!(state.messages.len(), state.builtin_count);
        assert_eq!(state.builtin_count, 49);
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn random_messages_save_clean_and_reset() {
        let (store, dir) = temp_store("rm-save");
        let saved = store
            .save_random_messages(&[
                "  hey  ".to_string(),
                "hey".to_string(),
                "".to_string(),
                "yo\nthere".to_string(),
            ])
            .unwrap();
        assert_eq!(saved.messages, vec!["hey", "yo there"]);
        assert_eq!(saved.dropped, 2);

        let state = store.load_random_messages().unwrap();
        assert!(state.custom);
        assert_eq!(state.messages, vec!["hey", "yo there"]);

        // Other settings are untouched by a pool edit.
        assert_eq!(
            store.load_settings().unwrap().message_text,
            AppSettings::default().message_text
        );

        store.reset_random_messages().unwrap();
        let state = store.load_random_messages().unwrap();
        assert!(!state.custom);
        assert_eq!(state.messages.len(), 49);
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn an_emptied_pool_stays_empty_instead_of_falling_back_to_built_in() {
        let (store, dir) = temp_store("rm-empty");
        store.save_random_messages(&[]).unwrap();
        let state = store.load_random_messages().unwrap();
        assert!(state.custom);
        assert!(state.messages.is_empty());
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn settings_files_with_null_or_missing_pool_load_as_built_in() {
        let (store, dir) = temp_store("rm-null");
        fs::write(dir.join("settings.json"), r#"{"random_messages":null}"#).unwrap();
        assert!(!store.load_random_messages().unwrap().custom);
        fs::write(
            dir.join("settings.json"),
            r#"{"random_messages":["a","b"]}"#,
        )
        .unwrap();
        let state = store.load_random_messages().unwrap();
        assert!(state.custom);
        assert_eq!(state.messages, vec!["a", "b"]);
        let _ = fs::remove_dir_all(dir);
    }
}
