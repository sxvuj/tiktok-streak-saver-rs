package com.sxvuj.streaksaver.rust

/**
 * JNI bridge to `libstreak_saver_jni.so` (`crates/streak-saver-jni`).
 *
 * Every method signature here maps 1:1 to an exported `Java_com_sxvuj_streaksaver_rust_RustCore_*`
 * symbol in `crates/streak-saver-jni/src/lib.rs`.
 */
object RustCore {
    init {
        System.loadLibrary("streak_saver_jni")
    }

    external fun guardLoginUrl(url: String): String
    external fun hasValidSessionCookie(cookieHeader: String): Boolean
    external fun resolveUserAgent(capturedUa: String): String
    external fun computeViewportJson(density: Float): String
    external fun renderAutomationScript(username: String, message: String, isGroup: Boolean): String
    external fun pickRandomMessage(): String
    external fun computeNextRunJson(storageDir: String): String
    external fun evaluateRetryScheduleJson(storageDir: String, failureReason: String): String
    external fun recordCleanRunSuccess(storageDir: String): Boolean
    external fun shouldAnticipateBatteryLow(storageDir: String): Boolean
    external fun buildRunPlanJson(storageDir: String, targetedIdsCsv: String): String
    external fun loadSettingsJson(storageDir: String): String
    external fun saveSettingsJson(storageDir: String, settingsJson: String): Boolean
    external fun loadFriendsJson(storageDir: String): String
    external fun saveFriendsJson(storageDir: String, friendsJson: String): Boolean
    external fun appendRunResultJson(storageDir: String, runResultJson: String): Long
    external fun loadHistoryStatsJson(storageDir: String): String
    external fun checkReleaseUpdateJson(currentVersion: String, githubReleaseJson: String): String
    external fun normalizeChatPriority(raw: String): String
}
