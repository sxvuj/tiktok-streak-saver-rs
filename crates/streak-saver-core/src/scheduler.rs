use chrono::{DateTime, Duration, NaiveDate, NaiveTime, TimeZone, Utc};
use serde::{Deserialize, Serialize};

use crate::error::{Result, StreakError};
use crate::models::AppSettings;

/// Result of computing the next scheduled run timestamp.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub struct ScheduleDecision {
    pub next_run_utc: DateTime<Utc>,
    pub delay_ms: i64,
    pub mode: String,
}

/// Result of evaluating an offline or send-error hourly retry.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
pub enum RetryDecision {
    /// Retry in 1 hour; caller should persist updated `retry_count_today` and `retry_count_date`.
    RetryInOneHour {
        next_run_utc: DateTime<Utc>,
        attempt: u32,
        max_attempts: u32,
    },
    /// Daily retry budget exhausted; fall back to normal scheduled slot.
    BudgetExhausted {
        next_run_utc: DateTime<Utc>,
        attempts_used: u32,
    },
}

/// Validates and clamps/rejects scheduling settings.
pub fn validate_schedule_settings(settings: &AppSettings) -> Result<()> {
    if !(1..=23).contains(&settings.interval_hours) {
        return Err(StreakError::InvalidInterval(settings.interval_hours));
    }
    if settings.fixed_time_hour > 23 || settings.fixed_time_minute > 59 {
        return Err(StreakError::InvalidFixedTime {
            hour: settings.fixed_time_hour,
            minute: settings.fixed_time_minute,
        });
    }
    Ok(())
}

/// Computes the next scheduled run timestamp in UTC.
///
/// Mirrors `StreakScheduler` in `Jon2G/TiktokStreakSaver` v2.2.0:
/// - If `use_fixed_time` is enabled, targets `fixed_time_hour:fixed_time_minute` today if
///   still in the future (> 60s from `now`), otherwise tomorrow at that time.
/// - Otherwise schedules `interval_hours` (1..=23, default 23h) from `now`.
pub fn compute_next_run(settings: &AppSettings, now: DateTime<Utc>) -> Result<ScheduleDecision> {
    validate_schedule_settings(settings)?;

    if settings.use_fixed_time {
        let target_time = NaiveTime::from_hms_opt(
            u32::from(settings.fixed_time_hour),
            u32::from(settings.fixed_time_minute),
            0,
        )
        .ok_or(StreakError::InvalidFixedTime {
            hour: settings.fixed_time_hour,
            minute: settings.fixed_time_minute,
        })?;

        let today_dt = Utc.from_utc_datetime(&now.date_naive().and_time(target_time));
        let next_run_utc = if today_dt > now + Duration::seconds(60) {
            today_dt
        } else {
            today_dt + Duration::days(1)
        };
        let delay_ms = (next_run_utc - now).num_milliseconds().max(0);
        return Ok(ScheduleDecision {
            next_run_utc,
            delay_ms,
            mode: "fixed_time".to_string(),
        });
    }

    let next_run_utc = now + Duration::hours(i64::from(settings.interval_hours));
    let delay_ms = (next_run_utc - now).num_milliseconds().max(0);
    Ok(ScheduleDecision {
        next_run_utc,
        delay_ms,
        mode: "interval".to_string(),
    })
}

/// Evaluates whether an offline/errored run should schedule a 1-hour retry or fall back
/// to the normal schedule, updating `settings.retry_count_today`, `settings.retry_count_date`,
/// `settings.last_run_failed`, and `settings.last_run_failure_reason`.
pub fn evaluate_retry_schedule(
    settings: &mut AppSettings,
    now: DateTime<Utc>,
    failure_reason: &str,
) -> Result<RetryDecision> {
    let today: NaiveDate = now.date_naive();
    if settings.retry_count_date != Some(today) {
        settings.retry_count_date = Some(today);
        settings.retry_count_today = 0;
    }

    settings.last_run_failed = true;
    settings.last_run_failure_reason = Some(failure_reason.to_string());

    let max_attempts = settings.max_daily_retries.max(1);
    if settings.retry_count_today < max_attempts {
        settings.retry_count_today += 1;
        let next_run_utc = now + Duration::hours(1);
        Ok(RetryDecision::RetryInOneHour {
            next_run_utc,
            attempt: settings.retry_count_today,
            max_attempts,
        })
    } else {
        let normal = compute_next_run(settings, now)?;
        Ok(RetryDecision::BudgetExhausted {
            next_run_utc: normal.next_run_utc,
            attempts_used: settings.retry_count_today,
        })
    }
}

/// Resets failure/retry counters after a clean successful run.
pub fn record_clean_run_success(settings: &mut AppSettings, now: DateTime<Utc>) {
    settings.last_run = Some(now);
    settings.last_run_failed = false;
    settings.last_run_failure_reason = None;
    settings.retry_count_date = Some(now.date_naive());
    settings.retry_count_today = 0;
}

/// Evaluates whether `BatteryLowReceiver` should trigger an early streak run before
/// the device powers off (`send_on_battery_low` + once-per-day `battery_anticipation_date` guard).
pub fn evaluate_battery_low_anticipation(settings: &mut AppSettings, now: DateTime<Utc>) -> bool {
    if !settings.send_on_battery_low || !settings.is_scheduled {
        return false;
    }
    let today = now.date_naive();
    if settings.battery_anticipation_date == Some(today) {
        return false;
    }
    if settings
        .last_run
        .map(|ts| ts.date_naive() == today)
        .unwrap_or(false)
        && !settings.last_run_failed
    {
        return false;
    }
    settings.battery_anticipation_date = Some(today);
    true
}

#[cfg(test)]
mod tests {
    use super::*;

    fn fixed_now(y: i32, m: u32, d: u32, h: u32, min: u32) -> DateTime<Utc> {
        Utc.with_ymd_and_hms(y, m, d, h, min, 0)
            .single()
            .unwrap_or_else(Utc::now)
    }

    #[test]
    fn interval_schedule_defaults_to_23_hours() {
        let settings = AppSettings::default();
        let now = fixed_now(2026, 11, 21, 10, 0);
        let decision = compute_next_run(&settings, now);
        assert!(decision.is_ok());
        if let Ok(d) = decision {
            assert_eq!(d.next_run_utc, fixed_now(2026, 11, 22, 9, 0));
            assert_eq!(d.delay_ms, 23 * 3600 * 1000);
            assert_eq!(d.mode, "interval");
        }
    }

    #[test]
    fn fixed_time_rolls_to_tomorrow_when_passed() {
        let settings = AppSettings {
            use_fixed_time: true,
            fixed_time_hour: 8,
            fixed_time_minute: 30,
            ..AppSettings::default()
        };
        let now = fixed_now(2026, 11, 21, 10, 0);
        let decision = compute_next_run(&settings, now);
        assert!(decision.is_ok());
        if let Ok(d) = decision {
            assert_eq!(d.next_run_utc, fixed_now(2026, 11, 22, 8, 30));
            assert_eq!(d.mode, "fixed_time");
        }
    }

    #[test]
    fn retry_budget_exhausts_after_three_attempts() {
        let mut settings = AppSettings::default();
        let now = fixed_now(2026, 11, 21, 10, 0);
        for expected in 1..=3 {
            let dec = evaluate_retry_schedule(&mut settings, now, "no_network");
            assert!(matches!(
                dec,
                Ok(RetryDecision::RetryInOneHour { attempt, .. }) if attempt == expected
            ));
        }
        let exhausted = evaluate_retry_schedule(&mut settings, now, "no_network");
        assert!(matches!(
            exhausted,
            Ok(RetryDecision::BudgetExhausted {
                attempts_used: 3,
                ..
            })
        ));
    }

    #[test]
    fn rejects_out_of_bounds_interval() {
        let settings = AppSettings {
            interval_hours: 24,
            ..AppSettings::default()
        };
        assert_eq!(
            compute_next_run(&settings, fixed_now(2026, 11, 21, 10, 0)),
            Err(StreakError::InvalidInterval(24))
        );
    }
}
