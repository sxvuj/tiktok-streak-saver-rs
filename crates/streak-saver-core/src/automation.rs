use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use std::collections::HashSet;

use crate::messages::MessageDeck;
use crate::models::{
    AppSettings, ChatPriority, FriendConfig, FriendMessageResult, StreakRunResult,
    AUTO_DISABLE_FAILURE_THRESHOLD,
};

/// Verbatim v2.2.0 TikTok WebView automation script bundled at compile time.
pub const TIKTOK_AUTOMATION_JS: &str = include_str!("../assets/tiktok_automation.js");

/// Verbatim v2.2.0 group-chat diagnostic script bundled at compile time.
pub const DETECT_GROUPS_JS: &str = include_str!("../assets/detect_groups.js");

/// Canonical error message emitted by `tiktok_automation.js` when a chat is not found.
pub const ERR_USER_NOT_FOUND: &str = "User not found in chat list";

/// Target CSS viewport dimensions so TikTok's virtualized chat list renders lazy children.
pub const TARGET_CSS_WIDTH: u32 = 1920;
pub const TARGET_CSS_HEIGHT: u32 = 1080;

/// Density-scaled physical pixel dimensions for the headless Android WebView.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
pub struct ViewportSize {
    pub width_px: u32,
    pub height_px: u32,
}

/// Computes physical pixel dimensions for a 1920×1080 CSS viewport at the device's display density.
pub fn compute_webview_viewport(density: f32) -> ViewportSize {
    let clamped = if density.is_finite() && density > 0.5 {
        density.clamp(0.75, 4.0)
    } else {
        1.0
    };
    ViewportSize {
        width_px: ((TARGET_CSS_WIDTH as f32) * clamped).round() as u32,
        height_px: ((TARGET_CSS_HEIGHT as f32) * clamped).round() as u32,
    }
}

/// Escapes a string for safe interpolation inside single-quoted JS literals (`'...'`)
/// in `tiktok_automation.js`, preserving exact v2.2.0 behavior (`\`, `'`, `"`, `\n`, `\r`).
pub fn escape_js_literal(input: &str) -> String {
    let mut out = String::with_capacity(input.len() + 8);
    for ch in input.chars() {
        match ch {
            '\\' => out.push_str("\\\\"),
            '\'' => out.push_str("\\'"),
            '"' => out.push_str("\\\""),
            '\n' => out.push_str("\\n"),
            '\r' => {}
            _ => out.push(ch),
        }
    }
    out
}

/// Renders `tiktok_automation.js` with `[UserName]`, `[Message]`, and `[IsGroup]` substituted.
pub fn render_automation_script(username: &str, message: &str, is_group: bool) -> String {
    let safe_user = escape_js_literal(username.trim());
    let safe_msg = escape_js_literal(message);
    let is_group_str = if is_group { "true" } else { "false" };

    TIKTOK_AUTOMATION_JS
        .replace("[UserName]", &safe_user)
        .replace("[Message]", &safe_msg)
        .replace("[IsGroup]", is_group_str)
}

/// Configuration for a single automation run.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct AutomationConfig {
    pub max_retries_per_friend: u32,
    pub skip_unreachable_users: bool,
    pub chat_priority: ChatPriority,
    pub randomize_messages: bool,
    pub custom_message: String,
    /// The pool random mode draws from. `None` means the built-in phrases.
    #[serde(default)]
    pub random_messages: Option<Vec<String>>,
}

impl AutomationConfig {
    fn build_deck(&self) -> MessageDeck {
        match &self.random_messages {
            Some(list) => MessageDeck::from_pool(list.clone()),
            None => MessageDeck::from_builtin(),
        }
    }

    fn build_seeded_deck(&self, seed: u64) -> MessageDeck {
        match &self.random_messages {
            Some(list) => MessageDeck::from_pool_seeded(list.clone(), seed),
            None => MessageDeck::from_builtin_seeded(seed),
        }
    }
}

impl From<&AppSettings> for AutomationConfig {
    fn from(s: &AppSettings) -> Self {
        Self {
            max_retries_per_friend: 2,
            skip_unreachable_users: s.skip_unreachable_users,
            chat_priority: s.chat_priority,
            randomize_messages: s.randomize_normal_messages,
            custom_message: s.message_text.clone(),
            random_messages: s.random_messages.clone(),
        }
    }
}

/// Ordered queue of friends to process in a run, plus count of already-sent-today skips.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct RunPlan {
    pub targets: Vec<FriendConfig>,
    pub skipped_already_sent: usize,
    pub is_targeted_retry: bool,
}

/// Builds a prioritized run plan matching `StreakService` v2.2.0:
/// 1. Keeps only enabled friends.
/// 2. If `targeted_ids` is non-empty, filters to those IDs and bypasses the already-sent-today check.
/// 3. Otherwise skips friends already messaged on `now.date_naive()`.
/// 4. Applies `chat_priority` (`Mixed`, `FriendsFirst`, or `GroupsFirst`) stably.
pub fn build_run_plan(
    friends: &[FriendConfig],
    targeted_ids: Option<&[String]>,
    priority: ChatPriority,
    now: DateTime<Utc>,
) -> RunPlan {
    let today = now.date_naive();
    build_run_plan_with(friends, targeted_ids, priority, |f| {
        f.was_sent_on_date(today)
    })
}

/// Like [`build_run_plan`], but a chat is skipped when it received a message within the last
/// `skip_window_hours` hours instead of "on the same UTC calendar day".
///
/// This is what the Android service uses; see `AppSettings::skip_window_hours` for why.
pub fn build_run_plan_windowed(
    friends: &[FriendConfig],
    targeted_ids: Option<&[String]>,
    priority: ChatPriority,
    now: DateTime<Utc>,
    skip_window_hours: i64,
) -> RunPlan {
    build_run_plan_with(friends, targeted_ids, priority, |f| {
        f.was_sent_within(now, skip_window_hours)
    })
}

fn build_run_plan_with(
    friends: &[FriendConfig],
    targeted_ids: Option<&[String]>,
    priority: ChatPriority,
    already_sent: impl Fn(&FriendConfig) -> bool,
) -> RunPlan {
    let is_targeted_retry = targeted_ids.map(|ids| !ids.is_empty()).unwrap_or(false);
    let target_set: HashSet<&str> = targeted_ids
        .unwrap_or(&[])
        .iter()
        .map(String::as_str)
        .collect();

    let mut targets = Vec::new();
    let mut skipped_already_sent = 0usize;

    for friend in friends {
        if !friend.is_enabled {
            continue;
        }
        if is_targeted_retry {
            if target_set.contains(friend.id.as_str()) {
                targets.push(friend.clone());
            }
            continue;
        }
        if already_sent(friend) {
            skipped_already_sent += 1;
            continue;
        }
        targets.push(friend.clone());
    }

    match priority {
        ChatPriority::Mixed => {}
        ChatPriority::FriendsFirst => {
            targets.sort_by_key(|f| f.is_group);
        }
        ChatPriority::GroupsFirst => {
            targets.sort_by_key(|f| !f.is_group);
        }
    }

    RunPlan {
        targets,
        skipped_already_sent,
        is_targeted_retry,
    }
}

/// Next instruction emitted by `RunStateMachine` to the platform WebView driver.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "action", rename_all = "snake_case")]
pub enum AutomationAction {
    /// Inject the rendered JS script into the already-loaded `/messages` WebView.
    InjectScript {
        friend_id: String,
        target_name: String,
        is_group: bool,
        message: String,
        script: String,
        attempt: u32,
        index: usize,
        total: usize,
    },
    /// All friends processed (or stopped due to unreachable user when `skip_unreachable_users == false`).
    Complete {
        summary: String,
        had_errors: bool,
        auto_disabled_ids: Vec<String>,
    },
}

/// Pure Rust state machine driving per-friend injection, bounded retries, unmatched-callback
/// protection, and 3-strike auto-disable logic.
#[derive(Debug)]
pub struct RunStateMachine {
    config: AutomationConfig,
    targets: Vec<FriendConfig>,
    is_targeted_retry: bool,
    started_at: DateTime<Utc>,
    cursor: usize,
    current_attempt: u32,
    deck: MessageDeck,
    results: Vec<FriendMessageResult>,
    auto_disabled_ids: Vec<String>,
}

impl RunStateMachine {
    pub fn new(plan: RunPlan, config: AutomationConfig, started_at: DateTime<Utc>) -> Self {
        let deck = config.build_deck();
        Self {
            config,
            targets: plan.targets,
            is_targeted_retry: plan.is_targeted_retry,
            started_at,
            cursor: 0,
            current_attempt: 1,
            deck,
            results: Vec::new(),
            auto_disabled_ids: Vec::new(),
        }
    }

    pub fn with_seeded_deck(mut self, seed: u64) -> Self {
        self.deck = self.config.build_seeded_deck(seed);
        self
    }

    /// IDs of chats switched off so far in this run (three "not found" failures in a row).
    pub fn auto_disabled_ids(&self) -> &[String] {
        &self.auto_disabled_ids
    }

    /// Emits the next action for the current cursor position.
    pub fn next_action(&mut self) -> AutomationAction {
        let total = self.targets.len();
        let Some(friend) = self.targets.get(self.cursor).cloned() else {
            return self.build_complete_action();
        };

        let message = if self.config.randomize_messages {
            self.deck
                .next_message()
                .map(ToString::to_string)
                .unwrap_or_else(|| self.config.custom_message.clone())
        } else {
            self.config.custom_message.clone()
        };

        let script = render_automation_script(&friend.username, &message, friend.is_group);
        AutomationAction::InjectScript {
            friend_id: friend.id,
            target_name: friend.username,
            is_group: friend.is_group,
            message,
            script,
            attempt: self.current_attempt,
            index: self.cursor + 1,
            total,
        }
    }

    /// Handles the `StreakApp.onMessageSent(userName, success, errorMessage)` callback
    /// and updates the corresponding `FriendConfig` counters in `all_friends`.
    pub fn handle_callback(
        &mut self,
        callback_user: &str,
        success: bool,
        error_message: &str,
        now: DateTime<Utc>,
        all_friends: &mut [FriendConfig],
    ) -> AutomationAction {
        let Some(current) = self.targets.get(self.cursor).cloned() else {
            return self.build_complete_action();
        };

        // Guard against stale or mismatched JS callback targets (bounded per-friend retry).
        let normalized_cb = callback_user.trim().trim_start_matches('@');
        let normalized_cur = current.username.trim().trim_start_matches('@');
        if !normalized_cb.eq_ignore_ascii_case(normalized_cur) {
            if self.current_attempt <= self.config.max_retries_per_friend {
                self.current_attempt += 1;
                return self.next_action();
            }
            // Retry budget exhausted on unmatched callback target.
            self.record_failure(
                &current,
                "Max retries exceeded for unmatched callback target",
                now,
                all_friends,
            );
            self.advance_cursor();
            return self.next_action();
        }

        if success {
            if let Some(f) = all_friends.iter_mut().find(|f| f.id == current.id) {
                f.last_message_sent = Some(now);
                f.success_count = f.success_count.saturating_add(1);
                f.consecutive_failures = 0;
            }
            self.results.push(FriendMessageResult {
                friend_id: current.id,
                username: current.username,
                display_name: current.display_name,
                is_group: current.is_group,
                failed: false,
                error_message: None,
                timestamp: now,
            });
            self.advance_cursor();
            return self.next_action();
        }

        // Failure path: retry up to `max_retries_per_friend` before recording final failure.
        if self.current_attempt <= self.config.max_retries_per_friend {
            self.current_attempt += 1;
            return self.next_action();
        }

        self.record_failure(&current, error_message, now, all_friends);

        if !self.config.skip_unreachable_users {
            self.cursor = self.targets.len();
            return self.build_complete_action();
        }

        self.advance_cursor();
        self.next_action()
    }

    /// Consumes the state machine and builds the final `StreakRunResult` for `run_history`.
    pub fn finish(self, finished_at: DateTime<Utc>) -> StreakRunResult {
        let duration_ms = (finished_at - self.started_at).num_milliseconds().max(0) as u64;
        StreakRunResult {
            run_time: self.started_at,
            duration_ms,
            is_targeted_retry: self.is_targeted_retry,
            friends_error_message: None,
            friend_results: self.results,
        }
    }

    fn record_failure(
        &mut self,
        current: &FriendConfig,
        error_message: &str,
        now: DateTime<Utc>,
        all_friends: &mut [FriendConfig],
    ) {
        let clean_err = if error_message.trim().is_empty() {
            "Unknown send error"
        } else {
            error_message.trim()
        };

        if let Some(f) = all_friends.iter_mut().find(|f| f.id == current.id) {
            f.failure_count = f.failure_count.saturating_add(1);
            f.consecutive_failures = f.consecutive_failures.saturating_add(1);
            // Auto-disable missing friend only after 3 consecutive failed runs (RusdiEneri cca1651 / v2.2.0).
            if clean_err.contains(ERR_USER_NOT_FOUND)
                && f.consecutive_failures >= AUTO_DISABLE_FAILURE_THRESHOLD
            {
                f.is_enabled = false;
                self.auto_disabled_ids.push(f.id.clone());
            }
        }

        self.results.push(FriendMessageResult {
            friend_id: current.id.clone(),
            username: current.username.clone(),
            display_name: current.display_name.clone(),
            is_group: current.is_group,
            failed: true,
            error_message: Some(clean_err.to_string()),
            timestamp: now,
        });
    }

    fn advance_cursor(&mut self) {
        self.cursor += 1;
        self.current_attempt = 1;
    }

    fn build_complete_action(&self) -> AutomationAction {
        let sent = self.results.iter().filter(|r| !r.failed).count();
        let failed = self.results.iter().filter(|r| r.failed).count();
        let summary = format!("Done: {sent} sent, {failed} failed");
        AutomationAction::Complete {
            summary,
            had_errors: failed > 0,
            auto_disabled_ids: self.auto_disabled_ids.clone(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::TimeZone;

    fn ts(h: u32) -> DateTime<Utc> {
        Utc.with_ymd_and_hms(2026, 11, 21, h, 0, 0)
            .single()
            .unwrap_or_else(Utc::now)
    }

    #[test]
    fn js_escaping_matches_v2_2_0() {
        assert_eq!(
            escape_js_literal("Hi 'Bob' \"\\\"\nNext"),
            "Hi \\'Bob\\' \\\"\\\\\\\"\\nNext"
        );
    }

    #[test]
    fn priority_orders_friends_and_groups() {
        let friends = vec![
            FriendConfig::new_group("g1", "Squad"),
            FriendConfig::new_friend("f1", "@alice", "Alice"),
            FriendConfig::new_group("g2", "Family"),
            FriendConfig::new_friend("f2", "bob", "Bob"),
        ];
        let plan = build_run_plan(&friends, None, ChatPriority::FriendsFirst, ts(10));
        let ids: Vec<&str> = plan.targets.iter().map(|f| f.id.as_str()).collect();
        assert_eq!(ids, vec!["f1", "f2", "g1", "g2"]);

        let plan_g = build_run_plan(&friends, None, ChatPriority::GroupsFirst, ts(10));
        let ids_g: Vec<&str> = plan_g.targets.iter().map(|f| f.id.as_str()).collect();
        assert_eq!(ids_g, vec!["g1", "g2", "f1", "f2"]);
    }

    #[test]
    fn windowed_plan_skips_recent_sends_but_not_old_ones() {
        let now = ts(10);
        let mut recent = FriendConfig::new_friend("f1", "recent", "Recent");
        recent.last_message_sent = Some(now - chrono::Duration::hours(2));
        let mut old = FriendConfig::new_friend("f2", "old", "Old");
        old.last_message_sent = Some(now - chrono::Duration::hours(23));
        let never = FriendConfig::new_friend("f3", "never", "Never");
        let friends = vec![recent, old, never];

        let plan = build_run_plan_windowed(&friends, None, ChatPriority::Mixed, now, 22);
        let ids: Vec<&str> = plan.targets.iter().map(|f| f.id.as_str()).collect();
        assert_eq!(ids, vec!["f2", "f3"]);
        assert_eq!(plan.skipped_already_sent, 1);
    }

    #[test]
    fn windowed_plan_does_not_skip_everyone_after_a_midnight_run() {
        // Sent at 00:30 UTC, next run 23 hours later on the same calendar day.
        let sent = Utc
            .with_ymd_and_hms(2026, 11, 21, 0, 30, 0)
            .single()
            .unwrap_or_else(Utc::now);
        let now = sent + chrono::Duration::hours(23);
        let mut friend = FriendConfig::new_friend("f1", "alice", "Alice");
        friend.last_message_sent = Some(sent);
        let friends = vec![friend];

        // The day-based rule skips this friend, which is the bug the window avoids.
        let by_day = build_run_plan(&friends, None, ChatPriority::Mixed, now);
        assert!(by_day.targets.is_empty());
        let by_window = build_run_plan_windowed(&friends, None, ChatPriority::Mixed, now, 22);
        assert_eq!(by_window.targets.len(), 1);
    }

    #[test]
    fn auto_disables_friend_only_on_third_consecutive_not_found() {
        let mut friends = vec![FriendConfig {
            consecutive_failures: 2,
            ..FriendConfig::new_friend("f1", "ghost", "Ghost")
        }];
        let plan = build_run_plan(&friends, None, ChatPriority::Mixed, ts(10));
        let config = AutomationConfig {
            max_retries_per_friend: 0,
            skip_unreachable_users: true,
            chat_priority: ChatPriority::Mixed,
            randomize_messages: false,
            custom_message: "streak".to_string(),
            random_messages: None,
        };
        let mut sm = RunStateMachine::new(plan, config, ts(10));
        let _ = sm.next_action();
        let done = sm.handle_callback("ghost", false, ERR_USER_NOT_FOUND, ts(10), &mut friends);
        assert!(matches!(
            done,
            AutomationAction::Complete {
                had_errors: true,
                ..
            }
        ));
        assert!(!friends[0].is_enabled);
        assert_eq!(friends[0].consecutive_failures, 3);
    }

    #[test]
    fn viewport_density_scaling() {
        let vp = compute_webview_viewport(2.75);
        assert_eq!(vp.width_px, 5280);
        assert_eq!(vp.height_px, 2970);
    }

    fn random_config(pool: Option<Vec<String>>) -> AutomationConfig {
        AutomationConfig {
            max_retries_per_friend: 0,
            skip_unreachable_users: true,
            chat_priority: ChatPriority::Mixed,
            randomize_messages: true,
            custom_message: "my own text".to_string(),
            random_messages: pool,
        }
    }

    fn first_message(config: AutomationConfig, seed: u64) -> String {
        let friends = vec![FriendConfig::new_friend("f1", "alice", "Alice")];
        let plan = build_run_plan(&friends, None, ChatPriority::Mixed, ts(10));
        let mut sm = RunStateMachine::new(plan, config, ts(10)).with_seeded_deck(seed);
        match sm.next_action() {
            AutomationAction::InjectScript { message, .. } => message,
            AutomationAction::Complete { .. } => String::new(),
        }
    }

    #[test]
    fn random_mode_draws_from_the_users_pool() {
        let pool = vec!["only this".to_string()];
        assert_eq!(first_message(random_config(Some(pool)), 3), "only this");
    }

    #[test]
    fn random_mode_with_an_emptied_pool_sends_the_custom_message() {
        assert_eq!(
            first_message(random_config(Some(Vec::new())), 3),
            "my own text"
        );
    }

    #[test]
    fn random_mode_without_a_pool_uses_the_built_in_phrases() {
        let msg = first_message(random_config(None), 3);
        assert!(crate::messages::RANDOMIZED_STREAK_MESSAGES.contains(&msg.as_str()));
    }

    #[test]
    fn config_from_settings_carries_the_pool() {
        let settings = AppSettings {
            randomize_normal_messages: true,
            random_messages: Some(vec!["a".to_string()]),
            ..AppSettings::default()
        };
        let config = AutomationConfig::from(&settings);
        assert!(config.randomize_messages);
        assert_eq!(config.random_messages, Some(vec!["a".to_string()]));
    }

    #[test]
    fn old_config_json_without_a_pool_still_loads() {
        let raw = r#"{"max_retries_per_friend":2,"skip_unreachable_users":true,
            "chat_priority":"mixed","randomize_messages":true,"custom_message":"x"}"#;
        let config: Option<AutomationConfig> = serde_json::from_str(raw).ok();
        assert_eq!(config.map(|c| c.random_messages), Some(None));
    }
}
