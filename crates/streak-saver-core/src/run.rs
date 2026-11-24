use chrono::{DateTime, Utc};

use crate::automation::{
    build_run_plan_windowed, AutomationAction, AutomationConfig, RunStateMachine,
};
use crate::error::Result;
use crate::models::{FriendConfig, RunSummary};
use crate::storage::AppStorage;

/// One streak run from start to finish, as seen by the platform shell.
///
/// The shell only has to do three things: call [`RunSession::start`], inject the script of every
/// `InjectScript` action into the WebView and report the outcome with [`RunSession::callback`],
/// and call [`RunSession::finish`] when it receives `Complete` (or when the user stops the run).
/// Retries, per-friend counters, the three-strikes auto-disable and the history entry all happen
/// here, in tested Rust, instead of being re-implemented per platform.
#[derive(Debug)]
pub struct RunSession {
    machine: RunStateMachine,
    friends: Vec<FriendConfig>,
    storage: AppStorage,
    persist_warning: Option<String>,
}

impl RunSession {
    /// Builds the run plan from the stored settings and friends and returns the first action.
    ///
    /// `targeted_ids` limits the run to specific chats (a manual retry); pass an empty slice for
    /// a normal run. When nothing needs sending the first action is already `Complete`.
    pub fn start(
        storage: AppStorage,
        targeted_ids: &[String],
        now: DateTime<Utc>,
    ) -> Result<(Self, AutomationAction)> {
        let settings = storage.load_settings()?;
        let friends = storage.load_friends()?;
        let ids = if targeted_ids.is_empty() {
            None
        } else {
            Some(targeted_ids)
        };
        let plan = build_run_plan_windowed(
            &friends,
            ids,
            settings.chat_priority,
            now,
            settings.skip_window_hours(),
        );
        let mut machine = RunStateMachine::new(plan, AutomationConfig::from(&settings), now);
        let first = machine.next_action();
        Ok((
            Self {
                machine,
                friends,
                storage,
                persist_warning: None,
            },
            first,
        ))
    }

    /// Reports the result of the last injected script and returns the next action.
    ///
    /// Friend counters are written to disk after every callback, so a run that is killed halfway
    /// does not send the same message again on the next run.
    pub fn callback(
        &mut self,
        user: &str,
        success: bool,
        error_message: &str,
        now: DateTime<Utc>,
    ) -> AutomationAction {
        let action =
            self.machine
                .handle_callback(user, success, error_message, now, &mut self.friends);
        if let Err(e) = self.storage.save_friends(&self.friends) {
            self.persist_warning = Some(e.to_string());
        }
        action
    }

    /// Ends the run, saves friend counters and appends a history entry when anything was
    /// attempted. Also used when the user stops a run early: what was done so far is kept.
    pub fn finish(self, now: DateTime<Utc>) -> Result<RunSummary> {
        let Self {
            machine,
            friends,
            storage,
            persist_warning,
        } = self;

        let auto_disabled = machine.auto_disabled_ids().len();
        let result = machine.finish(now);
        storage.save_friends(&friends)?;

        let sent = result.sent_count();
        let failed = result.failed_count();
        let total = result.total_count();
        let recorded = total > 0;
        if recorded {
            storage.append_run_result(result)?;
        }

        let summary = if recorded {
            format!("Done: {sent} sent, {failed} failed")
        } else {
            "Nothing to send".to_string()
        };
        Ok(RunSummary {
            sent,
            failed,
            total,
            had_errors: failed > 0,
            auto_disabled,
            recorded,
            summary,
            warning: persist_warning,
        })
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used, clippy::expect_used, clippy::panic)]
mod tests {
    use super::*;
    use crate::automation::ERR_USER_NOT_FOUND;
    use crate::models::AppSettings;
    use chrono::{Duration, TimeZone};
    use std::fs;
    use std::path::PathBuf;

    fn temp_store(tag: &str) -> (AppStorage, PathBuf) {
        let dir =
            std::env::temp_dir().join(format!("streak-run-test-{}-{tag}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        (AppStorage::new(&dir).unwrap(), dir)
    }

    fn now() -> DateTime<Utc> {
        Utc.with_ymd_and_hms(2026, 11, 22, 13, 0, 0)
            .single()
            .unwrap()
    }

    fn inject_target(action: &AutomationAction) -> String {
        match action {
            AutomationAction::InjectScript { target_name, .. } => target_name.clone(),
            AutomationAction::Complete { .. } => panic!("expected InjectScript"),
        }
    }

    fn quick_settings() -> AppSettings {
        // No per-friend retries would change the expectations below, so keep the defaults.
        AppSettings::default()
    }

    #[test]
    fn full_run_updates_counters_and_history() {
        let (store, dir) = temp_store("full");
        store.save_settings(&quick_settings()).unwrap();
        store.add_target("alice", "", false, now()).unwrap();
        store.add_target("Squad", "", true, now()).unwrap();

        let (mut run, first) = RunSession::start(store.clone(), &[], now()).unwrap();
        assert_eq!(inject_target(&first), "alice");
        let second = run.callback("alice", true, "", now());
        assert_eq!(inject_target(&second), "Squad");
        let done = run.callback("Squad", true, "", now() + Duration::seconds(5));
        assert!(matches!(
            done,
            AutomationAction::Complete {
                had_errors: false,
                ..
            }
        ));

        let summary = run.finish(now() + Duration::seconds(6)).unwrap();
        assert_eq!((summary.sent, summary.failed, summary.total), (2, 0, 2));
        assert!(summary.recorded && !summary.had_errors);

        let friends = store.load_friends().unwrap();
        assert!(friends.iter().all(|f| f.success_count == 1));
        assert!(friends.iter().all(|f| f.last_message_sent.is_some()));
        let history = store.load_history().unwrap();
        assert_eq!(history.len(), 1);
        assert_eq!(history[0].duration_ms, 6000);
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn a_second_run_right_after_skips_everyone_and_writes_no_history() {
        let (store, dir) = temp_store("second");
        store.add_target("alice", "", false, now()).unwrap();

        let (mut run, first) = RunSession::start(store.clone(), &[], now()).unwrap();
        let _ = inject_target(&first);
        let _ = run.callback("alice", true, "", now());
        run.finish(now()).unwrap();

        let later = now() + Duration::hours(1);
        let (run2, first2) = RunSession::start(store.clone(), &[], later).unwrap();
        assert!(matches!(first2, AutomationAction::Complete { .. }));
        let summary = run2.finish(later).unwrap();
        assert!(!summary.recorded);
        assert_eq!(summary.summary, "Nothing to send");
        assert_eq!(store.load_history().unwrap().len(), 1);
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn a_run_23_hours_later_sends_again() {
        let (store, dir) = temp_store("next-day");
        store.add_target("alice", "", false, now()).unwrap();
        let (mut run, _) = RunSession::start(store.clone(), &[], now()).unwrap();
        let _ = run.callback("alice", true, "", now());
        run.finish(now()).unwrap();

        let next = now() + Duration::hours(23);
        let (_run, first) = RunSession::start(store.clone(), &[], next).unwrap();
        assert_eq!(inject_target(&first), "alice");
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn failures_are_retried_counted_and_can_auto_disable() {
        let (store, dir) = temp_store("fail");
        store.add_target("ghost", "", false, now()).unwrap();
        let mut friends = store.load_friends().unwrap();
        friends[0].consecutive_failures = 2;
        store.save_friends(&friends).unwrap();

        let (mut run, first) = RunSession::start(store.clone(), &[], now()).unwrap();
        let mut action = first;
        // Two retries per friend, so three failed callbacks end the attempt.
        for _ in 0..3 {
            assert_eq!(inject_target(&action), "ghost");
            action = run.callback("ghost", false, ERR_USER_NOT_FOUND, now());
        }
        assert!(matches!(action, AutomationAction::Complete { .. }));

        let summary = run.finish(now()).unwrap();
        assert_eq!((summary.sent, summary.failed), (0, 1));
        assert!(summary.had_errors);
        assert_eq!(summary.auto_disabled, 1);

        let friends = store.load_friends().unwrap();
        assert!(!friends[0].is_enabled);
        assert_eq!(friends[0].failure_count, 1);
        let history = store.load_history().unwrap();
        assert_eq!(history[0].failed_count(), 1);
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn counters_survive_a_run_that_is_never_finished() {
        let (store, dir) = temp_store("killed");
        store.add_target("alice", "", false, now()).unwrap();
        store.add_target("bob", "", false, now()).unwrap();

        let (mut run, _) = RunSession::start(store.clone(), &[], now()).unwrap();
        let _ = run.callback("alice", true, "", now());
        drop(run); // process killed before finish()

        let friends = store.load_friends().unwrap();
        assert!(friends[0].last_message_sent.is_some());
        assert!(friends[1].last_message_sent.is_none());

        let later = now() + Duration::minutes(30);
        let (_run, first) = RunSession::start(store.clone(), &[], later).unwrap();
        assert_eq!(inject_target(&first), "bob");
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn stopping_early_keeps_what_was_sent() {
        let (store, dir) = temp_store("stop");
        store.add_target("alice", "", false, now()).unwrap();
        store.add_target("bob", "", false, now()).unwrap();

        let (mut run, _) = RunSession::start(store.clone(), &[], now()).unwrap();
        let _ = run.callback("alice", true, "", now());
        let summary = run.finish(now()).unwrap();
        assert_eq!((summary.sent, summary.total), (1, 1));
        assert!(summary.recorded);
        assert_eq!(store.load_history().unwrap().len(), 1);
        let _ = fs::remove_dir_all(dir);
    }

    #[test]
    fn disabled_chats_are_never_planned() {
        let (store, dir) = temp_store("disabled");
        let f = store.add_target("alice", "", false, now()).unwrap();
        store.set_target_enabled(&f.id, false).unwrap();
        let (_run, first) = RunSession::start(store.clone(), &[], now()).unwrap();
        assert!(matches!(first, AutomationAction::Complete { .. }));
        let _ = fs::remove_dir_all(dir);
    }
}
