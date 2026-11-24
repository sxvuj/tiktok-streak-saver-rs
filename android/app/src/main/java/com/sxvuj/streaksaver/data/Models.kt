package com.sxvuj.streaksaver.data

import com.sxvuj.streaksaver.rust.RustCore
import org.json.JSONArray
import org.json.JSONObject

/** Parsed answer of a `RustCore` call: `{"ok":true,"data":...}` or `{"ok":false,"error":"..."}`. */
class Reply(val ok: Boolean, val data: Any?, val error: String) {
    val obj: JSONObject? get() = data as? JSONObject
    val array: JSONArray? get() = data as? JSONArray
    val text: String? get() = data as? String

    companion object {
        fun parse(raw: String): Reply = try {
            val json = JSONObject(raw)
            if (json.optBoolean("ok", false)) {
                Reply(true, json.opt("data"), "")
            } else {
                Reply(false, null, json.optString("error", "Unknown error"))
            }
        } catch (e: Exception) {
            Reply(false, null, "Unreadable reply from the native core")
        }
    }
}

internal fun JSONObject.strOrNull(key: String): String? = if (isNull(key)) null else optString(key)

internal fun JSONObject.str(key: String, fallback: String = ""): String = strOrNull(key) ?: fallback

/** An RFC 3339 field as epoch milliseconds, or null when it is missing or unreadable. */
internal fun JSONObject.isoMs(key: String): Long? {
    val text = strOrNull(key) ?: return null
    val ms = RustCore.isoToEpochMs(text)
    return if (ms >= 0) ms else null
}

internal inline fun <T> JSONArray.mapObjects(transform: (JSONObject) -> T): List<T> {
    val out = ArrayList<T>(length())
    for (i in 0 until length()) {
        val item = optJSONObject(i) ?: continue
        out.add(transform(item))
    }
    return out
}

/** A friend or a group chat. */
data class Chat(
    val id: String,
    val username: String,
    val displayName: String,
    val enabled: Boolean,
    val lastSentMs: Long?,
    val successCount: Int,
    val failureCount: Int,
    val consecutiveFailures: Int,
    val isGroup: Boolean,
) {
    val title: String get() = displayName.ifBlank { username }

    companion object {
        fun from(o: JSONObject) = Chat(
            id = o.str("Id"),
            username = o.str("Username"),
            displayName = o.str("DisplayName"),
            enabled = o.optBoolean("IsEnabled", true),
            lastSentMs = o.isoMs("LastMessageSent")?.takeIf { it > 946_684_800_000L }, // ignore pre-2000 "never"
            successCount = o.optInt("SuccessCount"),
            failureCount = o.optInt("FailureCount"),
            consecutiveFailures = o.optInt("ConsecutiveFailures"),
            isGroup = o.optBoolean("IsGroup", false),
        )
    }
}

data class Settings(
    val messageText: String = "",
    val randomize: Boolean = false,
    val intervalHours: Int = 23,
    val scheduled: Boolean = false,
    val skipUnreachable: Boolean = true,
    val chatPriority: String = "mixed",
    val useFixedTime: Boolean = false,
    val fixedHour: Int = 9,
    val fixedMinute: Int = 0,
    val sendOnBatteryLow: Boolean = false,
    val lastRunFailed: Boolean = false,
    val failureReason: String? = null,
    val nextRunMs: Long? = null,
) {
    companion object {
        fun from(o: JSONObject) = Settings(
            messageText = o.str("message_text"),
            randomize = o.optBoolean("randomize_normal_messages"),
            intervalHours = o.optInt("interval_hours", 23),
            scheduled = o.optBoolean("is_scheduled"),
            skipUnreachable = o.optBoolean("skip_unreachable_users", true),
            chatPriority = o.str("chat_priority", "mixed"),
            useFixedTime = o.optBoolean("use_fixed_time"),
            fixedHour = o.optInt("fixed_time_hour", 9),
            fixedMinute = o.optInt("fixed_time_minute", 0),
            sendOnBatteryLow = o.optBoolean("send_on_battery_low"),
            lastRunFailed = o.optBoolean("last_run_failed"),
            failureReason = o.strOrNull("last_run_failure_reason"),
            nextRunMs = o.isoMs("next_run_utc"),
        )
    }
}

data class Session(
    val valid: Boolean = false,
    val lastCheckMs: Long? = null,
    val displayName: String = "",
) {
    companion object {
        fun from(o: JSONObject) = Session(
            valid = o.optBoolean("session_valid"),
            lastCheckMs = o.isoMs("session_last_check"),
            displayName = o.str("session_display_name"),
        )
    }
}

data class Dashboard(
    val totalTargets: Int = 0,
    val enabledTargets: Int = 0,
    val friendsTotal: Int = 0,
    val groupsTotal: Int = 0,
    val sentToday: Int = 0,
    val remainingToday: Int = 0,
    val successRateTodayPct: Double? = null,
    val lastRunMs: Long? = null,
) {
    companion object {
        fun from(o: JSONObject) = Dashboard(
            totalTargets = o.optInt("total_targets"),
            enabledTargets = o.optInt("enabled_targets"),
            friendsTotal = o.optInt("friends_total"),
            groupsTotal = o.optInt("groups_total"),
            sentToday = o.optInt("sent_today"),
            remainingToday = o.optInt("remaining_today"),
            successRateTodayPct = if (o.isNull("success_rate_today_pct")) null else o.optDouble("success_rate_today_pct"),
            lastRunMs = o.isoMs("last_run_time"),
        )
    }
}

data class FriendResult(
    val friendId: String,
    val username: String,
    val displayName: String,
    val isGroup: Boolean,
    val failed: Boolean,
    val error: String?,
) {
    val title: String get() = displayName.ifBlank { username }

    companion object {
        fun from(o: JSONObject) = FriendResult(
            friendId = o.str("FriendId"),
            username = o.str("Username"),
            displayName = o.str("DisplayName"),
            isGroup = o.optBoolean("IsGroup"),
            failed = o.optBoolean("Failed"),
            error = o.strOrNull("ErrorMessage"),
        )
    }
}

data class RunEntry(
    val runMs: Long,
    val durationMs: Long,
    val targetedRetry: Boolean,
    val error: String?,
    val results: List<FriendResult>,
) {
    val sent: Int get() = results.count { !it.failed }
    val failed: Int get() = results.count { it.failed }
    val failures: List<FriendResult> get() = results.filter { it.failed }
    val skipped: Boolean get() = results.isEmpty() && error != null

    companion object {
        fun from(o: JSONObject) = RunEntry(
            runMs = o.isoMs("RunTime") ?: 0L,
            durationMs = o.optLong("DurationMs"),
            targetedRetry = o.optBoolean("IsTargetedRetry"),
            error = o.strOrNull("FriendsErrorMessage"),
            results = o.optJSONArray("FriendResults")?.mapObjects { FriendResult.from(it) } ?: emptyList(),
        )
    }
}

data class HistoryStats(
    val totalRuns: Int = 0,
    val sent: Int = 0,
    val failed: Int = 0,
    val successRatePct: Double = 0.0,
) {
    companion object {
        fun from(o: JSONObject) = HistoryStats(
            totalRuns = o.optInt("total_runs"),
            sent = o.optInt("total_messages_sent"),
            failed = o.optInt("total_messages_failed"),
            successRatePct = o.optDouble("overall_success_rate_pct", 0.0),
        )
    }
}

data class ImportResult(val added: Int, val duplicates: Int, val invalid: Int) {
    companion object {
        fun from(o: JSONObject) = ImportResult(
            added = o.optInt("added"),
            duplicates = o.optInt("skipped_duplicates"),
            invalid = o.optInt("skipped_invalid"),
        )
    }
}

data class UpdateInfo(
    val currentVersion: String,
    val latestVersion: String,
    val available: Boolean,
    val notes: String,
    val apkUrl: String?,
    val pageUrl: String,
) {
    companion object {
        fun from(o: JSONObject) = UpdateInfo(
            currentVersion = o.str("current_version"),
            latestVersion = o.str("latest_version"),
            available = o.optBoolean("update_available"),
            notes = o.str("release_notes"),
            apkUrl = o.strOrNull("apk_download_url"),
            pageUrl = o.str("release_page_url"),
        )
    }
}

/** What a finished run reports (`RunSummary` in the Rust core). */
data class RunOutcome(
    val sent: Int,
    val failed: Int,
    val total: Int,
    val hadErrors: Boolean,
    val autoDisabled: Int,
    val recorded: Boolean,
    val summary: String,
) {
    companion object {
        fun from(o: JSONObject) = RunOutcome(
            sent = o.optInt("sent"),
            failed = o.optInt("failed"),
            total = o.optInt("total"),
            hadErrors = o.optBoolean("had_errors"),
            autoDisabled = o.optInt("auto_disabled"),
            recorded = o.optBoolean("recorded"),
            summary = o.str("summary"),
        )
    }
}

/** The next instruction from the Rust run state machine. */
sealed interface Step {
    data class Inject(
        val target: String,
        val script: String,
        val index: Int,
        val total: Int,
    ) : Step

    data class Done(val summary: String, val hadErrors: Boolean) : Step

    companion object {
        fun from(o: JSONObject): Step = when (o.optString("action")) {
            "inject_script" -> Inject(
                target = o.str("target_name"),
                script = o.str("script"),
                index = o.optInt("index"),
                total = o.optInt("total"),
            )
            else -> Done(o.str("summary"), o.optBoolean("had_errors"))
        }
    }
}
