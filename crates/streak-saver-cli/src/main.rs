#![forbid(unsafe_code)]

use std::fs;
use std::path::PathBuf;
use std::process::ExitCode;

use chrono::{DateTime, Utc};
use clap::{Parser, Subcommand};
use serde::Deserialize;

use streak_saver_core::automation::{
    build_run_plan, compute_webview_viewport, escape_js_literal, AutomationAction,
    AutomationConfig, RunStateMachine,
};
use streak_saver_core::models::{ChatPriority, FriendConfig};
use streak_saver_core::scheduler::compute_next_run;
use streak_saver_core::session::{guard_login_navigation, has_valid_session_cookie};
use streak_saver_core::storage::AppStorage;
use streak_saver_core::updater::check_latest_release_json;

#[derive(Parser)]
#[command(
    name = "streak-saver",
    version,
    about = "Pure Rust CLI & parity verifier for TikTok Streak Saver (v2.2.0 port)"
)]
struct Cli {
    /// Directory where settings.json, friends_list.json, and run_history.json are stored.
    #[arg(long, default_value = ".streak-saver-data")]
    data_dir: PathBuf,

    #[command(subcommand)]
    command: Commands,
}

#[derive(Subcommand)]
enum Commands {
    /// Add a TikTok friend or group chat.
    AddFriend {
        #[arg(long)]
        id: String,
        #[arg(long)]
        username: String,
        #[arg(long, default_value = "")]
        display_name: String,
        #[arg(long, default_value_t = false)]
        group: bool,
    },
    /// List configured friends/groups.
    ListFriends,
    /// Show next scheduled run timestamp based on current settings.
    NextRun,
    /// Simulate a full streak run locally through the Rust state machine.
    SimulateRun {
        #[arg(long, default_value_t = false)]
        randomize: bool,
        #[arg(long, default_value = "mixed")]
        priority: String,
    },
    /// Show persisted run history statistics.
    Stats,
    /// Check a downloaded GitHub Releases JSON payload for newer APK versions.
    CheckUpdate {
        #[arg(long)]
        release_json_path: PathBuf,
    },
    /// Replay recorded v2.2.0 behavioral parity cases (rust-anything cases.jsonl).
    ParityVerify {
        #[arg(long, default_value = "rust-anything/cases.jsonl")]
        cases: PathBuf,
    },
}

#[derive(Deserialize)]
struct ParityCase {
    id: String,
    unit: String,
    input: serde_json::Value,
    expected: serde_json::Value,
}

fn main() -> ExitCode {
    let cli = Cli::parse();
    match run(cli) {
        Ok(()) => ExitCode::SUCCESS,
        Err(msg) => {
            eprintln!("error: {msg}");
            ExitCode::FAILURE
        }
    }
}

fn run(cli: Cli) -> Result<(), String> {
    match cli.command {
        Commands::AddFriend {
            id,
            username,
            display_name,
            group,
        } => {
            let storage = AppStorage::new(&cli.data_dir).map_err(|e| e.to_string())?;
            let mut friends = storage.load_friends().map_err(|e| e.to_string())?;
            let entry = if group {
                FriendConfig::new_group(id, username)
            } else {
                FriendConfig::new_friend(id, username, display_name)
            };
            friends.push(entry);
            storage.save_friends(&friends).map_err(|e| e.to_string())?;
            println!("Saved {} configured targets.", friends.len());
            Ok(())
        }
        Commands::ListFriends => {
            let storage = AppStorage::new(&cli.data_dir).map_err(|e| e.to_string())?;
            let friends = storage.load_friends().map_err(|e| e.to_string())?;
            let json = serde_json::to_string_pretty(&friends).map_err(|e| e.to_string())?;
            println!("{json}");
            Ok(())
        }
        Commands::NextRun => {
            let storage = AppStorage::new(&cli.data_dir).map_err(|e| e.to_string())?;
            let settings = storage.load_settings().map_err(|e| e.to_string())?;
            let decision = compute_next_run(&settings, Utc::now()).map_err(|e| e.to_string())?;
            let json = serde_json::to_string_pretty(&decision).map_err(|e| e.to_string())?;
            println!("{json}");
            Ok(())
        }
        Commands::SimulateRun {
            randomize,
            priority,
        } => {
            let storage = AppStorage::new(&cli.data_dir).map_err(|e| e.to_string())?;
            let mut settings = storage.load_settings().map_err(|e| e.to_string())?;
            settings.randomize_normal_messages = randomize;
            settings.chat_priority = ChatPriority::from_setting(&priority);
            let mut friends = storage.load_friends().map_err(|e| e.to_string())?;

            let now = Utc::now();
            let plan = build_run_plan(&friends, None, settings.chat_priority, now);
            let config = AutomationConfig::from(&settings);
            let mut sm = RunStateMachine::new(plan, config, now).with_seeded_deck(220);

            loop {
                match sm.next_action() {
                    AutomationAction::InjectScript {
                        target_name,
                        message,
                        index,
                        total,
                        ..
                    } => {
                        println!("[{index}/{total}] Simulated send to {target_name}: {message}");
                        let _ =
                            sm.handle_callback(&target_name, true, "", Utc::now(), &mut friends);
                    }
                    AutomationAction::Complete { summary, .. } => {
                        println!("{summary}");
                        break;
                    }
                }
            }

            let run_result = sm.finish(Utc::now());
            storage.save_friends(&friends).map_err(|e| e.to_string())?;
            let _ = storage
                .append_run_result(run_result)
                .map_err(|e| e.to_string())?;
            Ok(())
        }
        Commands::Stats => {
            let storage = AppStorage::new(&cli.data_dir).map_err(|e| e.to_string())?;
            let stats = storage.compute_history_stats().map_err(|e| e.to_string())?;
            let json = serde_json::to_string_pretty(&stats).map_err(|e| e.to_string())?;
            println!("{json}");
            Ok(())
        }
        Commands::CheckUpdate { release_json_path } => {
            let raw = fs::read_to_string(&release_json_path).map_err(|e| e.to_string())?;
            let info = check_latest_release_json(env!("CARGO_PKG_VERSION"), &raw)
                .map_err(|e| e.to_string())?;
            let json = serde_json::to_string_pretty(&info).map_err(|e| e.to_string())?;
            println!("{json}");
            Ok(())
        }
        Commands::ParityVerify { cases } => verify_parity(&cases),
    }
}

fn verify_parity(cases_path: &PathBuf) -> Result<(), String> {
    let raw = fs::read_to_string(cases_path)
        .map_err(|e| format!("failed to read {}: {e}", cases_path.display()))?;
    let mut passed = 0usize;
    let mut total = 0usize;

    for (line_no, line) in raw.lines().enumerate() {
        let trimmed = line.trim();
        if trimmed.is_empty() {
            continue;
        }
        total += 1;
        let case: ParityCase = serde_json::from_str(trimmed)
            .map_err(|e| format!("line {}: invalid JSON: {e}", line_no + 1))?;

        let actual = evaluate_case(&case)?;
        if actual == case.expected {
            passed += 1;
        } else {
            return Err(format!(
                "PARITY MISMATCH in {} ({}): expected {}, got {}",
                case.id, case.unit, case.expected, actual
            ));
        }
    }

    println!("PARITY PASS: {passed}/{total} recorded v2.2.0 cases matched.");
    Ok(())
}

fn evaluate_case(case: &ParityCase) -> Result<serde_json::Value, String> {
    match case.unit.as_str() {
        "session.guard_login_navigation" => {
            let url = case
                .input
                .get("url")
                .and_then(|v| v.as_str())
                .ok_or_else(|| "missing input.url".to_string())?;
            let decision = guard_login_navigation(url);
            serde_json::to_value(decision).map_err(|e| e.to_string())
        }
        "session.has_valid_session_cookie" => {
            let cookie = case.input.get("cookie").and_then(|v| v.as_str());
            Ok(serde_json::json!(has_valid_session_cookie(cookie)))
        }
        "automation.escape_js_literal" => {
            let text = case
                .input
                .get("text")
                .and_then(|v| v.as_str())
                .ok_or_else(|| "missing input.text".to_string())?;
            Ok(serde_json::json!(escape_js_literal(text)))
        }
        "automation.compute_webview_viewport" => {
            let density =
                case.input
                    .get("density")
                    .and_then(|v| v.as_f64())
                    .ok_or_else(|| "missing input.density".to_string())? as f32;
            serde_json::to_value(compute_webview_viewport(density)).map_err(|e| e.to_string())
        }
        "scheduler.compute_next_run" => {
            let settings: streak_saver_core::models::AppSettings =
                serde_json::from_value(case.input.get("settings").cloned().unwrap_or_default())
                    .map_err(|e| e.to_string())?;
            let now_str = case
                .input
                .get("now")
                .and_then(|v| v.as_str())
                .ok_or_else(|| "missing input.now".to_string())?;
            let now: DateTime<Utc> = now_str.parse().map_err(|e| format!("{e}"))?;
            let dec = compute_next_run(&settings, now).map_err(|e| e.to_string())?;
            serde_json::to_value(dec).map_err(|e| e.to_string())
        }
        other => Err(format!("unknown parity unit: {other}")),
    }
}
