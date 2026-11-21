use thiserror::Error;

/// Typed error enum for all fallible operations in `streak-saver-core`.
#[derive(Debug, Error, Clone, PartialEq, Eq)]
pub enum StreakError {
    #[error("Invalid friend configuration: {0}")]
    InvalidFriend(String),

    #[error("Friend not found: {0}")]
    FriendNotFound(String),

    #[error("Invalid schedule interval: {0} (allowed: 1..=23 hours)")]
    InvalidInterval(u8),

    #[error("Invalid fixed schedule time: {hour:02}:{minute:02}")]
    InvalidFixedTime { hour: u8, minute: u8 },

    #[error("Storage I/O error: {0}")]
    Io(String),

    #[error("Serialization error: {0}")]
    Serialization(String),

    #[error("Invalid version string: {0}")]
    InvalidVersion(String),

    #[error("Automation state error: {0}")]
    Automation(String),
}

pub type Result<T> = std::result::Result<T, StreakError>;
