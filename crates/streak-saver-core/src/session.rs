use serde::{Deserialize, Serialize};
use url::Url;

/// Canonical TikTok login URL used in `LoginPage`.
pub const TIKTOK_LOGIN_URL: &str = "https://www.tiktok.com/login";

/// Canonical TikTok messages inbox URL used by `StreakService`.
pub const TIKTOK_MESSAGES_URL: &str = "https://www.tiktok.com/messages?lang=en";

/// Modern Chrome desktop fallback User-Agent matching `TikTokWebViewHelper` v2.2.0.
pub const DEFAULT_DESKTOP_USER_AGENT: &str =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

/// Mobile Chrome fallback User-Agent matching `TikTokWebViewHelper` v2.2.0.
pub const DEFAULT_MOBILE_USER_AGENT: &str =
    "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

/// Action the Android/iOS Login WebView should take for a pending navigation URL.
#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(tag = "action", rename_all = "snake_case")]
pub enum LoginNavDecision {
    /// Allow standard `http://` or `https://` navigation to proceed.
    Allow,
    /// Block custom scheme (`snssdk://`, `intent://`, etc.) and reload the extracted `params_url`.
    BlockAndReload { target_url: String },
    /// Block custom scheme with no safe web fallback.
    Block,
}

/// Checks whether a WebView `CookieManager` cookie header string contains a non-empty `sessionid`.
///
/// Mirrors `TikTokWebViewHelper` cookie verification on `https://www.tiktok.com` / `https://tiktok.com`.
pub fn has_valid_session_cookie(cookie_header: Option<&str>) -> bool {
    let Some(header) = cookie_header else {
        return false;
    };
    header.split(';').any(|part| {
        let trimmed = part.trim();
        if let Some(value) = trimmed.strip_prefix("sessionid=") {
            !value.trim().is_empty()
        } else {
            false
        }
    })
}

/// Returns the captured login User-Agent if present and non-empty, else the Chrome desktop fallback.
pub fn resolve_user_agent(captured_ua: Option<&str>) -> String {
    captured_ua
        .map(str::trim)
        .filter(|ua| !ua.is_empty())
        .map(ToString::to_string)
        .unwrap_or_else(|| DEFAULT_DESKTOP_USER_AGENT.to_string())
}

/// Custom-scheme login guard ported from `RusdiEneri/TiktokStreakSaver` commit `cca1651`
/// and `Jon2G/TiktokStreakSaver` v2.2.0.
///
/// Blocks `snssdk://`, `intent://`, and any non-HTTP(S) scheme during TikTok login so the
/// app does not close or jump to the native TikTok app. If the blocked URL embeds a valid
/// `http://` or `https://` `params_url=` query parameter, instructs the WebView to reload it.
pub fn guard_login_navigation(raw_url: &str) -> LoginNavDecision {
    let trimmed = raw_url.trim();
    if trimmed.is_empty() {
        return LoginNavDecision::Block;
    }

    let lower = trimmed.to_ascii_lowercase();
    if lower.starts_with("http://") || lower.starts_with("https://") {
        return LoginNavDecision::Allow;
    }

    // Attempt to extract `params_url=` from query string (supports snssdk:// and intent:// URLs).
    if let Some(fallback) = extract_params_url(trimmed) {
        return LoginNavDecision::BlockAndReload {
            target_url: fallback,
        };
    }

    LoginNavDecision::Block
}

/// Detects whether a navigated URL indicates a completed TikTok login landing page.
pub fn is_post_login_url(raw_url: &str) -> bool {
    let lower = raw_url.to_ascii_lowercase();
    lower.contains("tiktok.com/messages")
        || lower.contains("tiktok.com/foryou")
        || lower.contains("tiktok.com/@")
}

fn extract_params_url(raw_url: &str) -> Option<String> {
    if let Ok(parsed) = Url::parse(raw_url) {
        for (key, value) in parsed.query_pairs() {
            if key == "params_url" && is_http_or_https(&value) {
                return Some(value.into_owned());
            }
        }
    }

    // Fallback for malformed Android `intent://...#Intent;...` URIs where `Url::parse` may not split query pairs.
    let marker = "params_url=";
    let start = raw_url.find(marker)? + marker.len();
    let tail = &raw_url[start..];
    let end = tail.find(['&', '#']).unwrap_or(tail.len());
    let encoded = &tail[..end];
    let decoded = percent_decode(encoded);
    if is_http_or_https(&decoded) {
        Some(decoded)
    } else {
        None
    }
}

fn is_http_or_https(candidate: &str) -> bool {
    let lower = candidate.trim().to_ascii_lowercase();
    lower.starts_with("http://") || lower.starts_with("https://")
}

fn percent_decode(input: &str) -> String {
    let bytes = input.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut i = 0;
    while i < bytes.len() {
        if bytes[i] == b'%' && i + 2 < bytes.len() {
            if let (Some(hi), Some(lo)) = (hex_val(bytes[i + 1]), hex_val(bytes[i + 2])) {
                out.push((hi << 4) | lo);
                i += 3;
                continue;
            }
        } else if bytes[i] == b'+' {
            out.push(b' ');
            i += 1;
            continue;
        }
        out.push(bytes[i]);
        i += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

fn hex_val(b: u8) -> Option<u8> {
    match b {
        b'0'..=b'9' => Some(b - b'0'),
        b'a'..=b'f' => Some(b - b'a' + 10),
        b'A'..=b'F' => Some(b - b'A' + 10),
        _ => None,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cookie_check_detects_sessionid() {
        assert!(has_valid_session_cookie(Some(
            "ttwid=abc; sessionid=deadbeef1234; sid_tt=xyz"
        )));
        assert!(!has_valid_session_cookie(Some("ttwid=abc; sessionid=")));
        assert!(!has_valid_session_cookie(None));
    }

    #[test]
    fn login_guard_blocks_snssdk_and_extracts_params_url() {
        let url = "snssdk1233://webview?params_url=https%3A%2F%2Fwww.tiktok.com%2Fmessages%3Flang%3Den&hide_nav_bar=1";
        assert_eq!(
            guard_login_navigation(url),
            LoginNavDecision::BlockAndReload {
                target_url: "https://www.tiktok.com/messages?lang=en".to_string()
            }
        );
    }

    #[test]
    fn login_guard_blocks_intent_without_web_fallback() {
        let url = "intent://open#Intent;scheme=snssdk1233;package=com.zhiliaoapp.musically;end";
        assert_eq!(guard_login_navigation(url), LoginNavDecision::Block);
    }

    #[test]
    fn login_guard_allows_https() {
        assert_eq!(
            guard_login_navigation("https://www.tiktok.com/login"),
            LoginNavDecision::Allow
        );
    }
}
