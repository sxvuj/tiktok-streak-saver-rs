package com.sxvuj.streaksaver.data

import android.content.Context
import com.sxvuj.streaksaver.rust.RustCore
import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone

/**
 * The one place the app talks to `RustCore`. Every call is blocking (small local files), so
 * callers decide the thread. Writes to the settings file go through [LOCK] because the UI and the
 * background service both change it and each write is a read-modify-write.
 */
class StreakRepo(context: Context) {
    private val dir: String = context.applicationContext.filesDir.absolutePath

    private fun utcOffsetMinutes(): Int = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 60_000

    // --- Reading ---------------------------------------------------------------------------------

    fun settings(): Settings =
        Reply.parse(RustCore.loadSettingsJson(dir)).obj?.let { Settings.from(it) } ?: Settings()

    fun friends(): List<Chat> =
        Reply.parse(RustCore.loadFriendsJson(dir)).array?.mapObjects { Chat.from(it) } ?: emptyList()

    fun dashboard(): Dashboard =
        Reply.parse(RustCore.loadDashboardJson(dir, utcOffsetMinutes())).obj?.let { Dashboard.from(it) }
            ?: Dashboard()

    /** Newest run first. */
    fun history(): List<RunEntry> =
        Reply.parse(RustCore.loadHistoryJson(dir)).array?.mapObjects { RunEntry.from(it) } ?: emptyList()

    fun historyStats(): HistoryStats =
        Reply.parse(RustCore.loadHistoryStatsJson(dir)).obj?.let { HistoryStats.from(it) } ?: HistoryStats()

    fun session(): Session =
        Reply.parse(RustCore.loadSessionJson(dir)).obj?.let { Session.from(it) } ?: Session()

    fun nextRunMs(): Long = RustCore.getNextRunEpochMs(dir)

    // --- Settings --------------------------------------------------------------------------------

    /** Reads the settings file, lets [change] edit it and writes it back. */
    fun updateSettings(change: (JSONObject) -> Unit): Boolean = synchronized(LOCK) {
        val current = Reply.parse(RustCore.loadSettingsJson(dir)).obj ?: return false
        change(current)
        RustCore.saveSettingsJson(dir, current.toString())
    }

    // --- Random message pool ---------------------------------------------------------------------

    fun randomMessages(): RandomMessages =
        Reply.parse(RustCore.loadRandomMessagesJson(dir)).obj?.let { RandomMessages.from(it) } ?: RandomMessages()

    /** Stores [messages] as the pool. The core trims, de-duplicates and caps it, and says what it kept. */
    fun saveRandomMessages(messages: List<String>): Reply = synchronized(LOCK) {
        Reply.parse(RustCore.saveRandomMessagesJson(dir, JSONArray(messages).toString()))
    }

    /** Goes back to the built-in phrases. */
    fun resetRandomMessages(): Boolean = synchronized(LOCK) { RustCore.resetRandomMessages(dir) }

    fun setNextRun(epochMs: Long): Boolean = synchronized(LOCK) { RustCore.setNextRunEpochMs(dir, epochMs) }

    fun recordCleanRun(): Boolean = synchronized(LOCK) { RustCore.recordCleanRunSuccess(dir) }

    /** Decides between an hourly retry and the normal slot. Returns the delay in milliseconds. */
    fun retryDelayMs(reason: String): Long? = synchronized(LOCK) {
        val data = Reply.parse(RustCore.evaluateRetryScheduleJson(dir, reason)).obj ?: return null
        if (data.has("RetryInOneHour")) 60L * 60L * 1000L else null
    }

    /** Delay until the next normal run, in milliseconds, or null when the settings are invalid. */
    fun normalDelayMs(): Long? {
        val data = Reply.parse(RustCore.computeNextRunLocalJson(dir, utcOffsetMinutes())).obj ?: return null
        return data.optLong("delay_ms", -1L).takeIf { it >= 0 }
    }

    fun shouldRunOnBatteryLow(): Boolean = synchronized(LOCK) { RustCore.shouldAnticipateBatteryLow(dir) }

    // --- Friends and groups ----------------------------------------------------------------------

    fun addChat(name: String, displayName: String, isGroup: Boolean): Reply =
        Reply.parse(RustCore.addTargetJson(dir, name, displayName, isGroup))

    fun editChat(id: String, name: String, displayName: String): Reply =
        Reply.parse(RustCore.updateTargetJson(dir, id, name, displayName))

    fun deleteChat(id: String): Reply = Reply.parse(RustCore.deleteTargetJson(dir, id))

    fun setChatEnabled(id: String, enabled: Boolean): Reply =
        Reply.parse(RustCore.setTargetEnabledJson(dir, id, enabled))

    fun setAllEnabled(isGroup: Boolean, enabled: Boolean): Int =
        Reply.parse(RustCore.setAllEnabledJson(dir, isGroup, enabled)).data.let { (it as? Number)?.toInt() ?: 0 }

    fun clearChats(): Boolean = RustCore.clearFriends(dir)

    fun exportChats(): String? = Reply.parse(RustCore.exportFriendsJson(dir)).text

    fun importChats(raw: String): Reply = Reply.parse(RustCore.importFriendsJson(dir, raw))

    // --- History ---------------------------------------------------------------------------------

    fun clearHistory(): Boolean = RustCore.clearHistory(dir)

    fun exportHistory(): String? = Reply.parse(RustCore.exportHistoryLog(dir)).text

    fun recordSkippedRun(reason: String): Boolean = RustCore.recordSkippedRun(dir, reason)

    // --- Session ---------------------------------------------------------------------------------

    /** Stores the outcome of a session check. An empty [displayName] keeps the stored name. */
    fun updateSession(valid: Boolean, displayName: String = ""): Boolean =
        RustCore.updateSessionJson(dir, valid, displayName)

    companion object {
        private val LOCK = Any()
    }
}
