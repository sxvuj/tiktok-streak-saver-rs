package com.sxvuj.streaksaver.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.PowerManager
import android.webkit.CookieManager
import android.webkit.WebStorage
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.sxvuj.streaksaver.data.Chat
import com.sxvuj.streaksaver.data.Dashboard
import com.sxvuj.streaksaver.data.HistoryStats
import com.sxvuj.streaksaver.data.ImportResult
import com.sxvuj.streaksaver.data.RandomMessages
import com.sxvuj.streaksaver.data.RunEntry
import com.sxvuj.streaksaver.data.SavedMessages
import com.sxvuj.streaksaver.data.Session
import com.sxvuj.streaksaver.data.Settings
import com.sxvuj.streaksaver.data.StreakRepo
import com.sxvuj.streaksaver.data.UpdateChecker
import com.sxvuj.streaksaver.data.UpdateInfo
import com.sxvuj.streaksaver.rust.RustCore
import com.sxvuj.streaksaver.service.RunStatus
import com.sxvuj.streaksaver.service.Scheduler
import com.sxvuj.streaksaver.service.StreakService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Everything the screens show, loaded from the Rust core in one go. */
data class UiData(
    val loaded: Boolean = false,
    val settings: Settings = Settings(),
    val randomMessages: RandomMessages = RandomMessages(),
    val chats: List<Chat> = emptyList(),
    val dashboard: Dashboard = Dashboard(),
    val history: List<RunEntry> = emptyList(),
    val stats: HistoryStats = HistoryStats(),
    val session: Session = Session(),
    val update: UpdateInfo? = null,
    val updateChecking: Boolean = false,
    val ignoringBatteryOptimizations: Boolean = true,
    val exactAlarmsAllowed: Boolean = true,
    val notificationsAllowed: Boolean = true,
)

/** Holds the screen state and turns user actions into calls on the Rust core. */
class AppModel(application: Application) : AndroidViewModel(application) {
    private val app: Application = application
    private val repo = StreakRepo(app)
    private var refreshJob: Job? = null

    var ui by mutableStateOf(UiData())
        private set

    val run = RunStatus.state

    // Appearance is a phone-only UI choice, so it lives in SharedPreferences, not in the Rust core.
    private val uiPrefs = app.getSharedPreferences(UI_PREFS, Context.MODE_PRIVATE)

    /** Wallpaper-based Material You colors (Android 12+). Off keeps the built-in blue palette. */
    var dynamicColor by mutableStateOf(uiPrefs.getBoolean(KEY_DYNAMIC_COLOR, false))
        private set

    fun setDynamicColor(on: Boolean) {
        dynamicColor = on
        uiPrefs.edit().putBoolean(KEY_DYNAMIC_COLOR, on).apply()
    }

    private val messageFlow = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** One-off messages for a snackbar. */
    val messages: SharedFlow<String> = messageFlow.asSharedFlow()

    val versionName: String = try {
        app.packageManager.getPackageInfo(app.packageName, 0).versionName ?: "1.0.0"
    } catch (e: Exception) {
        "1.0.0"
    }

    init {
        viewModelScope.launch {
            var wasRunning = false
            var lastIndex = -1
            RunStatus.state.collect { state ->
                if ((wasRunning && !state.running) || (state.running && state.index != lastIndex)) refresh()
                wasRunning = state.running
                lastIndex = state.index
            }
        }
        refresh()
        checkUpdate(silent = true)
    }

    private fun say(text: String) {
        messageFlow.tryEmit(text)
    }

    // --- Loading ---------------------------------------------------------------------------------

    fun refresh() {
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val cookieOk = hasSessionCookie()
            val data = withContext(Dispatchers.IO) { load(cookieOk) }
            ui = data.copy(update = ui.update, updateChecking = ui.updateChecking)
        }
    }

    private fun hasSessionCookie(): Boolean = try {
        RustCore.hasValidSessionCookie(CookieManager.getInstance().getCookie("https://www.tiktok.com").orEmpty())
    } catch (e: Exception) {
        true // no WebView on this device: do not claim the user was logged out
    }

    private fun load(cookieOk: Boolean): UiData {
        var session = repo.session()
        if (session.valid && !cookieOk) {
            repo.updateSession(valid = false)
            session = repo.session()
        }

        var settings = repo.settings()
        if (settings.scheduled && session.valid && !RunStatus.state.value.running) {
            val next = settings.nextRunMs ?: 0L
            if (next <= 0L || next < System.currentTimeMillis() - OVERDUE_MS) {
                // The alarm was lost (force stop, never armed, old version): arm it again.
                Scheduler.restore(app)
                settings = repo.settings()
            }
        }

        val power = app.getSystemService(Context.POWER_SERVICE) as PowerManager
        return UiData(
            loaded = true,
            settings = settings,
            randomMessages = repo.randomMessages(),
            chats = repo.friends(),
            dashboard = repo.dashboard(),
            history = repo.history(),
            stats = repo.historyStats(),
            session = session,
            ignoringBatteryOptimizations = power.isIgnoringBatteryOptimizations(app.packageName),
            exactAlarmsAllowed = Scheduler.canScheduleExact(app),
            notificationsAllowed = NotificationManagerCompat.from(app).areNotificationsEnabled(),
        )
    }

    // --- Running ---------------------------------------------------------------------------------

    fun runNow(ids: List<String> = emptyList()) {
        if (RunStatus.state.value.running) return
        if (!ui.session.valid) {
            say("Log in to TikTok first (Settings tab).")
            return
        }
        if (ui.chats.none { it.enabled }) {
            say("Turn on at least one friend or group first.")
            return
        }
        try {
            StreakService.start(app, ids, manual = true)
        } catch (e: Exception) {
            say("Android would not start the run: ${e.message ?: "unknown reason"}")
        }
    }

    fun stopRun() {
        StreakService.requestStop(app)
    }

    // --- Friends and groups ----------------------------------------------------------------------

    private fun idleOrSay(): Boolean {
        if (RunStatus.state.value.running) {
            say("A run is in progress. Try again when it finishes.")
            return false
        }
        return true
    }

    fun addChat(name: String, displayName: String, isGroup: Boolean) {
        if (!idleOrSay()) return
        viewModelScope.launch {
            val reply = withContext(Dispatchers.IO) { repo.addChat(name.trim(), displayName.trim(), isGroup) }
            say(if (reply.ok) "Added ${name.trim()}" else reply.error)
            refresh()
        }
    }

    fun editChat(id: String, name: String, displayName: String) {
        if (!idleOrSay()) return
        viewModelScope.launch {
            val reply = withContext(Dispatchers.IO) { repo.editChat(id, name.trim(), displayName.trim()) }
            if (!reply.ok) say(reply.error)
            refresh()
        }
    }

    fun deleteChat(id: String) {
        if (!idleOrSay()) return
        viewModelScope.launch {
            val reply = withContext(Dispatchers.IO) { repo.deleteChat(id) }
            say(if (reply.ok) "Removed" else reply.error)
            refresh()
        }
    }

    fun setChatEnabled(id: String, enabled: Boolean) {
        if (!idleOrSay()) return
        viewModelScope.launch {
            val reply = withContext(Dispatchers.IO) { repo.setChatEnabled(id, enabled) }
            if (!reply.ok) say(reply.error)
            refresh()
        }
    }

    fun setAllEnabled(isGroup: Boolean, enabled: Boolean) {
        if (!idleOrSay()) return
        viewModelScope.launch {
            val changed = withContext(Dispatchers.IO) { repo.setAllEnabled(isGroup, enabled) }
            val noun = if (isGroup) "groups" else "friends"
            say(if (changed == 0) "All $noun were already ${if (enabled) "on" else "off"}" else "${if (enabled) "Turned on" else "Turned off"} $changed $noun")
            refresh()
        }
    }

    fun clearChats() {
        if (!idleOrSay()) return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { repo.clearChats() }
            say(if (ok) "Removed all friends and groups" else "Could not clear the list")
            refresh()
        }
    }

    fun importFrom(uri: Uri) {
        if (!idleOrSay()) return
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                try {
                    app.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                } catch (e: Exception) {
                    null
                }
            }
            if (text == null) {
                say("Could not read that file")
                return@launch
            }
            val reply = withContext(Dispatchers.IO) { repo.importChats(text) }
            val report = reply.obj?.let { ImportResult.from(it) }
            say(
                when {
                    !reply.ok || report == null -> "Import failed: ${reply.error}"
                    report.added == 0 && report.duplicates == 0 -> "Nothing to import. Is this a friends list exported from Streak Saver?"
                    else -> "Imported ${report.added}" +
                        (if (report.duplicates > 0) " · ${report.duplicates} already in your list" else "") +
                        (if (report.invalid > 0) " · ${report.invalid} unreadable" else "")
                }
            )
            refresh()
        }
    }

    fun exportChatsTo(uri: Uri) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { writeText(uri, repo.exportChats()) }
            say(if (ok) "Exported ${ui.chats.size} chats" else "Could not write the file")
        }
    }

    // --- History ---------------------------------------------------------------------------------

    fun clearHistory() {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { repo.clearHistory() }
            say(if (ok) "History cleared" else "Could not clear history")
            refresh()
        }
    }

    fun exportHistoryTo(uri: Uri) {
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { writeText(uri, repo.exportHistory()) }
            say(if (ok) "Log exported" else "Could not write the file")
        }
    }

    private fun writeText(uri: Uri, text: String?): Boolean {
        if (text == null) return false
        return try {
            val stream = app.contentResolver.openOutputStream(uri, "wt") ?: return false
            stream.bufferedWriter().use { it.write(text) }
            true
        } catch (e: Exception) {
            false
        }
    }

    // --- Settings --------------------------------------------------------------------------------

    private fun change(rearm: Boolean = false, edit: (JSONObject) -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repo.updateSettings(edit)
                if (rearm && repo.settings().scheduled && ui.session.valid) Scheduler.armFromSettings(app)
            }
            refresh()
        }
    }

    fun saveMessage(text: String) = change { it.put("message_text", text) }

    /**
     * Stores the edited random-message pool. [onDone] gets the list the core actually kept (trimmed,
     * de-duplicated, capped) so the editor can show exactly what is saved.
     */
    fun saveRandomMessages(messages: List<String>, onDone: (List<String>) -> Unit = {}) {
        viewModelScope.launch {
            val reply = withContext(Dispatchers.IO) { repo.saveRandomMessages(messages) }
            val saved = reply.obj?.let { SavedMessages.from(it) }
            if (!reply.ok || saved == null) {
                say("Could not save the messages: ${reply.error.ifBlank { "unknown error" }}")
                return@launch
            }
            say(
                when {
                    saved.messages.isEmpty() -> "Saved. The list is empty, so your single message is used."
                    saved.dropped > 0 -> "Saved ${saved.messages.size} messages. Left out ${saved.dropped} (empty, too long or repeated)."
                    else -> "Saved ${saved.messages.size} messages"
                },
            )
            onDone(saved.messages)
            refresh()
        }
    }

    /** Puts the built-in phrases back. [onDone] gets them. */
    fun resetRandomMessages(onDone: (List<String>) -> Unit = {}) {
        viewModelScope.launch {
            val restored = withContext(Dispatchers.IO) {
                if (repo.resetRandomMessages()) repo.randomMessages() else null
            }
            if (restored == null) {
                say("Could not restore the built-in messages")
                return@launch
            }
            say("Back to the ${restored.messages.size} built-in messages")
            onDone(restored.messages)
            refresh()
        }
    }

    fun setRandomize(on: Boolean) = change { it.put("randomize_normal_messages", on) }

    fun setSkipUnreachable(on: Boolean) = change { it.put("skip_unreachable_users", on) }

    fun setPriority(value: String) = change { it.put("chat_priority", RustCore.normalizeChatPriority(value)) }

    fun setBatteryLow(on: Boolean) = change { it.put("send_on_battery_low", on) }

    fun setUseFixedTime(on: Boolean) = change(rearm = true) { it.put("use_fixed_time", on) }

    fun setFixedTime(hour: Int, minute: Int) = change(rearm = true) {
        it.put("fixed_time_hour", hour.coerceIn(0, 23))
        it.put("fixed_time_minute", minute.coerceIn(0, 59))
    }

    fun setInterval(hours: Int) = change(rearm = true) { it.put("interval_hours", hours.coerceIn(1, 23)) }

    fun setScheduled(on: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repo.updateSettings { it.put("is_scheduled", on) }
                if (!on) {
                    Scheduler.cancel(app)
                } else if (ui.session.valid) {
                    Scheduler.armFirstRun(app)
                }
            }
            if (on && !ui.session.valid) say("Automation is on. It starts as soon as you log in to TikTok.")
            refresh()
        }
    }

    // --- Session ---------------------------------------------------------------------------------

    fun onLoginResult(loggedIn: Boolean) {
        if (!loggedIn) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                repo.updateSession(valid = true)
                Scheduler.restore(app)
            }
            say("Logged in to TikTok")
            refresh()
        }
    }

    /** Checks for the TikTok session cookie in this app's WebView. It cannot prove the server still accepts it. */
    fun verifySession() {
        viewModelScope.launch {
            val cookieOk = hasSessionCookie()
            withContext(Dispatchers.IO) { repo.updateSession(cookieOk) }
            say(if (cookieOk) "TikTok session cookie found" else "Not logged in to TikTok")
            refresh()
        }
    }

    fun setDisplayName(name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { repo.updateSession(ui.session.valid, clean) }
            refresh()
        }
    }

    fun logout() {
        viewModelScope.launch {
            try {
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                WebStorage.getInstance().deleteAllData()
            } catch (e: Exception) {
                // No WebView to clear.
            }
            withContext(Dispatchers.IO) {
                repo.updateSession(valid = false)
                Scheduler.cancel(app)
            }
            say("Logged out. Automation resumes after you log in again.")
            refresh()
        }
    }

    // --- Updates ---------------------------------------------------------------------------------

    fun checkUpdate(silent: Boolean = false) {
        viewModelScope.launch {
            ui = ui.copy(updateChecking = true)
            val result = withContext(Dispatchers.IO) { UpdateChecker.check(versionName) }
            ui = ui.copy(updateChecking = false)
            result.onSuccess { info ->
                ui = ui.copy(update = info)
                if (!silent) say(if (info.available) "Version ${info.latestVersion} is available" else "You have the latest version")
            }
            result.onFailure {
                if (!silent) say("Could not check for updates: ${it.message ?: "no connection"}")
            }
        }
    }

    private companion object {
        const val OVERDUE_MS = 5L * 60L * 1000L
        const val UI_PREFS = "ui_prefs"
        const val KEY_DYNAMIC_COLOR = "dynamic_color"
    }
}
