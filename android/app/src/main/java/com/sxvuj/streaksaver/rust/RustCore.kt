package com.sxvuj.streaksaver.rust

/**
 * JNI bridge to `libstreak_saver_jni.so` (`crates/streak-saver-jni`).
 *
 * Every method signature here maps 1:1 to an exported `Java_com_sxvuj_streaksaver_rust_RustCore_*`
 * symbol in `crates/streak-saver-jni/src/lib.rs`. A method declared here without a matching
 * symbol only fails at the moment it is called (UnsatisfiedLinkError), so keep both files in sync.
 *
 * Calls that return `String` answer with JSON of the shape `{"ok":true,"data":...}` or
 * `{"ok":false,"error":"..."}`; `com.sxvuj.streaksaver.data.Reply` parses that.
 */
object RustCore {
    init {
        System.loadLibrary("streak_saver_jni")
    }

    // --- Session and WebView helpers ---------------------------------------------------------
    external fun guardLoginUrl(url: String): String
    external fun hasValidSessionCookie(cookieHeader: String): Boolean
    external fun resolveUserAgent(capturedUa: String): String
    external fun computeViewportJson(density: Float): String
    external fun renderAutomationScript(username: String, message: String, isGroup: Boolean): String
    external fun pickRandomMessage(): String
    external fun normalizeChatPriority(raw: String): String

    // --- Time, scheduling and updates --------------------------------------------------------
    external fun isoToEpochMs(iso: String): Long
    external fun computeNextRunJson(storageDir: String): String
    external fun computeNextRunLocalJson(storageDir: String, utcOffsetMinutes: Int): String
    external fun evaluateRetryScheduleJson(storageDir: String, failureReason: String): String
    external fun recordCleanRunSuccess(storageDir: String): Boolean
    external fun shouldAnticipateBatteryLow(storageDir: String): Boolean
    external fun setNextRunEpochMs(storageDir: String, epochMs: Long): Boolean
    external fun getNextRunEpochMs(storageDir: String): Long
    external fun latestReleaseUrl(): String
    external fun checkReleaseUpdateJson(currentVersion: String, githubReleaseJson: String): String

    // --- Settings ----------------------------------------------------------------------------
    external fun loadSettingsJson(storageDir: String): String
    external fun saveSettingsJson(storageDir: String, settingsJson: String): Boolean

    // --- Random message pool -----------------------------------------------------------------
    external fun loadRandomMessagesJson(storageDir: String): String
    external fun saveRandomMessagesJson(storageDir: String, messagesJson: String): String
    external fun resetRandomMessages(storageDir: String): Boolean

    // --- Friends and groups ------------------------------------------------------------------
    external fun loadFriendsJson(storageDir: String): String
    external fun saveFriendsJson(storageDir: String, friendsJson: String): Boolean
    external fun exportFriendsJson(storageDir: String): String
    external fun importFriendsJson(storageDir: String, raw: String): String
    external fun clearFriends(storageDir: String): Boolean
    external fun addTargetJson(storageDir: String, name: String, displayName: String, isGroup: Boolean): String
    external fun updateTargetJson(storageDir: String, id: String, name: String, displayName: String): String
    external fun deleteTargetJson(storageDir: String, id: String): String
    external fun setTargetEnabledJson(storageDir: String, id: String, enabled: Boolean): String
    external fun setAllEnabledJson(storageDir: String, isGroup: Boolean, enabled: Boolean): String

    // --- History and dashboard ---------------------------------------------------------------
    external fun loadDashboardJson(storageDir: String, utcOffsetMinutes: Int): String
    external fun loadHistoryJson(storageDir: String): String
    external fun loadHistoryStatsJson(storageDir: String): String
    external fun appendRunResultJson(storageDir: String, runResultJson: String): Long
    external fun clearHistory(storageDir: String): Boolean
    external fun exportHistoryLog(storageDir: String): String
    external fun recordSkippedRun(storageDir: String, reason: String): Boolean

    // --- TikTok session ----------------------------------------------------------------------
    external fun loadSessionJson(storageDir: String): String
    external fun updateSessionJson(storageDir: String, valid: Boolean, displayName: String): Boolean

    // --- A run in progress -------------------------------------------------------------------
    external fun buildRunPlanJson(storageDir: String, targetedIdsCsv: String): String
    external fun runStartJson(storageDir: String, targetedIdsCsv: String): String
    external fun runCallbackJson(user: String, success: Boolean, error: String): String
    external fun runFinishJson(): String
}
