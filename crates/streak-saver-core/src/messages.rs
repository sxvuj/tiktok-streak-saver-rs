use rand::rngs::StdRng;
use rand::seq::SliceRandom;
use rand::SeedableRng;

/// Exact built-in pool of randomized streak phrases extracted from `StreakSaver-v2.2.0.apk`.
pub const RANDOMIZED_STREAK_MESSAGES: &[&str] = &[
    "Streak",
    "streak",
    "streakk",
    "streaak",
    "streaakkk",
    "streaaak",
    "strk",
    "Strk",
    "S",
    "streaks",
    "Streaks",
    "streakss",
    "streak lol",
    "yo streak",
    "yoo streak",
    "yoo streakk",
    "hey streak",
    "hii streak",
    "hi streak",
    "heyy streak",
    "streak hii",
    "streak hi",
    "streak yo",
    "streak yoo",
    "streakkk",
    "strek",
    "streek",
    "streeek",
    "streeeek",
    "yo",
    "yoo",
    "yooo",
    "hey",
    "hii",
    "heyy",
    "heyyy",
    "here",
    "heree",
    "strkeee",
    "streak rn",
    "quick streak",
    "streakk lol",
    "streak lmao",
    "lol streak",
    "streaaakk",
    "streakkk lol",
    "daily streak",
    "streak streak",
    "ayo streak",
];

/// Longest random message the editor accepts, in characters.
pub const MAX_MESSAGE_CHARS: usize = 280;

/// Most messages the random pool can hold.
pub const MAX_RANDOM_MESSAGES: usize = 200;

/// The built-in phrases as owned strings, the starting point of the editable pool.
pub fn builtin_messages() -> Vec<String> {
    RANDOMIZED_STREAK_MESSAGES
        .iter()
        .map(|s| (*s).to_string())
        .collect()
}

/// Cleans one message typed or pasted by the user.
///
/// Line breaks and tabs become spaces (the chat box takes a single line), surrounding
/// whitespace is trimmed, and `None` comes back for an empty message or one longer than
/// [`MAX_MESSAGE_CHARS`]. Nothing is cut short silently.
pub fn sanitize_message(raw: &str) -> Option<String> {
    let flat: String = raw
        .chars()
        .map(|c| if c.is_control() { ' ' } else { c })
        .collect();
    let clean = flat.trim();
    if clean.is_empty() || clean.chars().count() > MAX_MESSAGE_CHARS {
        return None;
    }
    Some(clean.to_string())
}

/// A cleaned message list and how many entries were left out.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SanitizedPool {
    pub messages: Vec<String>,
    /// Entries that were empty, too long, exact duplicates or beyond [`MAX_RANDOM_MESSAGES`].
    pub dropped: usize,
}

/// Cleans a whole list: every entry through [`sanitize_message`], exact duplicates removed
/// (case matters, so "Streak" and "streak" are both kept) and the list capped at
/// [`MAX_RANDOM_MESSAGES`]. The order is kept.
pub fn sanitize_pool(raw: &[String]) -> SanitizedPool {
    let mut messages: Vec<String> = Vec::new();
    let mut dropped = 0usize;
    for entry in raw {
        match sanitize_message(entry) {
            Some(clean) if messages.len() < MAX_RANDOM_MESSAGES && !messages.contains(&clean) => {
                messages.push(clean);
            }
            _ => dropped += 1,
        }
    }
    SanitizedPool { messages, dropped }
}

/// Shuffle-and-reshuffle-on-exhaust message deck.
/// Guarantees no phrase repeats within a single full pass of the pool.
#[derive(Debug, Clone)]
pub struct MessageDeck {
    pool: Vec<String>,
    order: Vec<usize>,
    cursor: usize,
    rng: StdRng,
}

impl MessageDeck {
    /// Creates a deck from the built-in v2.2.0 pool using OS entropy.
    pub fn from_builtin() -> Self {
        Self::from_pool_with_rng(
            RANDOMIZED_STREAK_MESSAGES
                .iter()
                .map(|s| (*s).to_string())
                .collect(),
            StdRng::from_entropy(),
        )
    }

    /// Creates a deterministic deck with a fixed seed (used in parity tests and simulations).
    pub fn from_builtin_seeded(seed: u64) -> Self {
        Self::from_pool_with_rng(
            RANDOMIZED_STREAK_MESSAGES
                .iter()
                .map(|s| (*s).to_string())
                .collect(),
            StdRng::seed_from_u64(seed),
        )
    }

    /// Creates a deck from the user's own phrase list using OS entropy.
    pub fn from_pool(pool: Vec<String>) -> Self {
        Self::from_pool_with_rng(pool, StdRng::from_entropy())
    }

    /// Creates a deterministic deck from a custom phrase list (tests and simulations).
    pub fn from_pool_seeded(pool: Vec<String>, seed: u64) -> Self {
        Self::from_pool_with_rng(pool, StdRng::seed_from_u64(seed))
    }

    /// Creates a deck from a custom phrase list and RNG.
    pub fn from_pool_with_rng(pool: Vec<String>, mut rng: StdRng) -> Self {
        let mut order: Vec<usize> = (0..pool.len()).collect();
        order.shuffle(&mut rng);
        Self {
            pool,
            order,
            cursor: 0,
            rng,
        }
    }

    /// Returns the next randomized message, reshuffling only when the current pass is exhausted.
    pub fn next_message(&mut self) -> Option<&str> {
        if self.pool.is_empty() {
            return None;
        }
        if self.cursor >= self.order.len() {
            self.order.shuffle(&mut self.rng);
            self.cursor = 0;
        }
        let idx = self.order[self.cursor];
        self.cursor += 1;
        self.pool.get(idx).map(String::as_str)
    }

    pub fn len(&self) -> usize {
        self.pool.len()
    }

    pub fn is_empty(&self) -> bool {
        self.pool.is_empty()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashSet;

    #[test]
    fn no_duplicates_within_single_pass() {
        let mut deck = MessageDeck::from_builtin_seeded(42);
        let total = deck.len();
        assert_eq!(total, RANDOMIZED_STREAK_MESSAGES.len());

        let mut seen = HashSet::new();
        for _ in 0..total {
            let msg = deck.next_message().unwrap_or_default().to_string();
            assert!(seen.insert(msg), "duplicate message inside a single pass");
        }
    }

    #[test]
    fn builtin_messages_match_the_constant() {
        let list = builtin_messages();
        assert_eq!(list.len(), RANDOMIZED_STREAK_MESSAGES.len());
        assert_eq!(list[0], "Streak");
    }

    #[test]
    fn sanitize_message_trims_flattens_and_rejects() {
        assert_eq!(sanitize_message("  hi there \n"), Some("hi there".into()));
        assert_eq!(sanitize_message("a\nb\tc"), Some("a b c".into()));
        assert_eq!(sanitize_message("   \n\t "), None);
        assert_eq!(sanitize_message(""), None);
        let long = "x".repeat(MAX_MESSAGE_CHARS + 1);
        assert_eq!(sanitize_message(&long), None);
        let exact = "x".repeat(MAX_MESSAGE_CHARS);
        assert_eq!(sanitize_message(&exact), Some(exact.clone()));
    }

    #[test]
    fn sanitize_message_counts_characters_not_bytes() {
        // 280 emoji are 1120 bytes but 280 characters, which is allowed.
        let emoji = "🔥".repeat(MAX_MESSAGE_CHARS);
        assert_eq!(sanitize_message(&emoji), Some(emoji.clone()));
        let too_many = "🔥".repeat(MAX_MESSAGE_CHARS + 1);
        assert_eq!(sanitize_message(&too_many), None);
    }

    #[test]
    fn sanitize_pool_dedupes_exactly_and_counts_drops() {
        let raw = vec![
            "Streak".to_string(),
            "streak".to_string(),
            "  Streak  ".to_string(),
            "".to_string(),
            "yo".to_string(),
        ];
        let out = sanitize_pool(&raw);
        assert_eq!(out.messages, vec!["Streak", "streak", "yo"]);
        assert_eq!(out.dropped, 2);
    }

    #[test]
    fn sanitize_pool_caps_the_size() {
        let raw: Vec<String> = (0..MAX_RANDOM_MESSAGES + 5)
            .map(|i| format!("msg {i}"))
            .collect();
        let out = sanitize_pool(&raw);
        assert_eq!(out.messages.len(), MAX_RANDOM_MESSAGES);
        assert_eq!(out.dropped, 5);
    }

    #[test]
    fn custom_pool_deck_only_uses_its_own_phrases() {
        let pool = vec!["one".to_string(), "two".to_string()];
        let mut deck = MessageDeck::from_pool_seeded(pool.clone(), 7);
        for _ in 0..10 {
            let msg = deck.next_message().unwrap_or_default().to_string();
            assert!(pool.contains(&msg));
        }
    }

    #[test]
    fn empty_pool_deck_yields_nothing() {
        let mut deck = MessageDeck::from_pool(Vec::new());
        assert!(deck.is_empty());
        assert!(deck.next_message().is_none());
    }

    #[test]
    fn reshuffles_after_exhaustion() {
        let mut deck = MessageDeck::from_builtin_seeded(99);
        let total = deck.len();
        let first_pass: Vec<String> = (0..total)
            .filter_map(|_| deck.next_message().map(str::to_string))
            .collect();
        let second_pass: Vec<String> = (0..total)
            .filter_map(|_| deck.next_message().map(str::to_string))
            .collect();
        assert_eq!(first_pass.len(), total);
        assert_eq!(second_pass.len(), total);
        assert_ne!(first_pass, second_pass);
    }
}
