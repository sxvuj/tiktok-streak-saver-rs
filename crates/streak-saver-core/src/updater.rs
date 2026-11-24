use serde::Deserialize;
use std::cmp::Ordering;

use crate::error::{Result, StreakError};
use crate::models::UpdateInfo;

/// GitHub Releases API endpoint for this fork (sxvuj/tiktok-streak-saver-rs).
pub const GITHUB_LATEST_RELEASE_URL: &str =
    "https://api.github.com/repos/sxvuj/tiktok-streak-saver-rs/releases/latest";

/// User-Agent header required by the GitHub API.
pub const UPDATER_USER_AGENT: &str = "StreakSaverRS/1.0";

/// Expected APK asset filename prefix.
pub const APK_ASSET_PREFIX: &str = "StreakSaver-";

#[derive(Debug, Deserialize)]
struct GitHubReleasePayload {
    tag_name: String,
    #[serde(default)]
    body: String,
    #[serde(default)]
    html_url: String,
    #[serde(default)]
    assets: Vec<GitHubAssetPayload>,
}

#[derive(Debug, Deserialize)]
struct GitHubAssetPayload {
    name: String,
    browser_download_url: String,
}

/// Parses a semver string such as `v2.2.0` or `2.2.0` into `(major, minor, patch)`.
pub fn parse_semver(raw: &str) -> Result<(u64, u64, u64)> {
    let cleaned = raw.trim().trim_start_matches(['v', 'V']);
    let core = cleaned.split('-').next().unwrap_or(cleaned);
    let mut parts = core.split('.');

    let major = parts
        .next()
        .and_then(|s| s.parse::<u64>().ok())
        .ok_or_else(|| StreakError::InvalidVersion(raw.to_string()))?;
    let minor = parts
        .next()
        .and_then(|s| s.parse::<u64>().ok())
        .unwrap_or(0);
    let patch = parts
        .next()
        .and_then(|s| s.parse::<u64>().ok())
        .unwrap_or(0);

    Ok((major, minor, patch))
}

/// Compares two semver strings (`Ordering::Greater` if `latest > current`).
pub fn compare_semver(latest: &str, current: &str) -> Result<Ordering> {
    let l = parse_semver(latest)?;
    let c = parse_semver(current)?;
    Ok(l.cmp(&c))
}

/// Parses a GitHub Releases `/releases/latest` JSON body and selects the Android APK asset.
pub fn check_latest_release_json(current_version: &str, release_json: &str) -> Result<UpdateInfo> {
    let payload: GitHubReleasePayload = serde_json::from_str(release_json)
        .map_err(|e| StreakError::Serialization(e.to_string()))?;

    let update_available = compare_semver(&payload.tag_name, current_version)? == Ordering::Greater;

    let apk_download_url = payload
        .assets
        .iter()
        .find(|a| a.name.starts_with(APK_ASSET_PREFIX) && a.name.ends_with(".apk"))
        .or_else(|| payload.assets.iter().find(|a| a.name.ends_with(".apk")))
        .map(|a| a.browser_download_url.clone());

    Ok(UpdateInfo {
        current_version: current_version.to_string(),
        latest_version: payload.tag_name,
        update_available,
        release_notes: payload.body,
        apk_download_url,
        release_page_url: payload.html_url,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_and_compares_versions() {
        assert_eq!(parse_semver("v2.2.0"), Ok((2, 2, 0)));
        assert_eq!(compare_semver("v2.3.0", "2.2.0"), Ok(Ordering::Greater));
        assert_eq!(compare_semver("v2.2.0", "2.2.0"), Ok(Ordering::Equal));
    }

    #[test]
    fn selects_streak_saver_apk_asset() {
        let json = r#"{
            "tag_name": "v2.3.0",
            "body": "Bug fixes",
            "html_url": "https://github.com/sxvuj/tiktok-streak-saver-rs/releases/tag/v1.1.1",
            "assets": [
                {"name": "StreakSaver.ipa", "browser_download_url": "https://example.com/app.ipa"},
                {"name": "StreakSaver-v2.3.0.apk", "browser_download_url": "https://example.com/StreakSaver-v2.3.0.apk"}
            ]
        }"#;
        let info = check_latest_release_json("2.2.0", json);
        assert!(info.is_ok());
        if let Ok(u) = info {
            assert!(u.update_available);
            assert_eq!(
                u.apk_download_url.as_deref(),
                Some("https://example.com/StreakSaver-v2.3.0.apk")
            );
        }
    }
}
