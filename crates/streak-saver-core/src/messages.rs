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
