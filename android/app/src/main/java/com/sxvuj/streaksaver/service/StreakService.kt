package com.sxvuj.streaksaver.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.sxvuj.streaksaver.data.Reply
import com.sxvuj.streaksaver.data.RunOutcome
import com.sxvuj.streaksaver.data.StreakRepo
import com.sxvuj.streaksaver.data.Step
import com.sxvuj.streaksaver.rust.RustCore

/**
 * Foreground service that sends the streak messages.
 *
 * All planning, retries, per-chat counters, the three-strikes auto-disable and the history entry
 * live in the Rust `RunSession`; this class only hosts a headless WebView, injects the scripts the
 * core hands out, reports what the page answered and keeps the next alarm armed.
 *
 * Every path ends in [finishRun] or [abort], so the next run is always scheduled and the history
 * always shows what happened, including runs that never got to send anything.
 */
class StreakService : Service() {

    companion object {
        private const val TAG = "StreakService"
        const val ACTION_STOP_SERVICE = "STOP_SERVICE"
        const val EXTRA_TARGET_FRIEND_IDS = "target_friend_ids"
        const val EXTRA_MANUAL = "manual"
        private const val MESSAGES_URL = "https://www.tiktok.com/messages?lang=en"
        private const val PAGE_TIMEOUT_MS = 60_000L
        private const val SEND_TIMEOUT_MS = 90_000L
        private const val RUN_DEADLINE_MS = 25L * 60L * 1000L

        /** Starts a run. [manual] runs report "nothing to send"; scheduled runs stay quiet about it. */
        fun start(context: Context, targetIds: List<String> = emptyList(), manual: Boolean = false) {
            val intent = Intent(context, StreakService::class.java).putExtra(EXTRA_MANUAL, manual)
            if (targetIds.isNotEmpty()) intent.putExtra(EXTRA_TARGET_FRIEND_IDS, targetIds.joinToString(","))
            ContextCompat.startForegroundService(context, intent)
        }

        /** Asks a running service to stop after the chat it is on. Harmless when nothing runs. */
        fun requestStop(context: Context) {
            context.startService(Intent(context, StreakService::class.java).setAction(ACTION_STOP_SERVICE))
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var repo: StreakRepo
    private var wakeLock: PowerManager.WakeLock? = null
    private var webView: WebView? = null

    private var runActive = false
    private var sessionOpen = false
    private var manual = false
    private var userStopped = false
    private var pageHandled = false
    private var generation = 0
    private var firstStep: Step.Inject? = null
    private var awaiting: Step.Inject? = null

    private val sendWatchdog = Runnable { onSendTimeout() }
    private val pageWatchdog = Runnable {
        if (runActive && !pageHandled) abort("TikTok did not load in time.", retryReason = "send_error")
    }
    private val deadline = Runnable {
        if (runActive) finishRun(note = "Stopped after 25 minutes.")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Notifications.ensureChannels(this)

        if (intent?.action == ACTION_STOP_SERVICE) {
            if (runActive) {
                userStopped = true
                RunStatus.state.value = RunStatus.state.value.copy(stopping = true)
                finishRun(note = "Stopped by you.")
            } else {
                stopSelf()
            }
            return START_NOT_STICKY
        }

        // startForegroundService() demands startForeground() right away, even if a run is already going.
        try {
            ServiceCompat.startForeground(
                this,
                Notifications.ID_RUN,
                Notifications.runProgress(this, "Getting ready…"),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Android refused to start the foreground service", e)
            repo = StreakRepo(this)
            repo.recordSkippedRun("Android would not let the app run in the background.")
            Notifications.status(this, "Streak run skipped", "Android would not start the background run. Open the app and tap Run now.")
            if (repo.settings().scheduled) Scheduler.armFromSettings(this)
            stopSelf()
            return START_NOT_STICKY
        }

        if (runActive) return START_NOT_STICKY

        repo = StreakRepo(this)
        manual = intent?.getBooleanExtra(EXTRA_MANUAL, false) ?: false
        beginRun(intent?.getStringExtra(EXTRA_TARGET_FRIEND_IDS).orEmpty())
        return START_NOT_STICKY
    }

    // --- Run lifecycle -------------------------------------------------------------------------

    private fun beginRun(targetedCsv: String) {
        runActive = true
        sessionOpen = false
        userStopped = false
        pageHandled = false
        firstStep = null
        awaiting = null
        generation++
        RunStatus.state.value = RunStatus.State(running = true, current = "Getting ready…")
        acquireWakeLock()
        main.postDelayed(deadline, RUN_DEADLINE_MS)

        if (!hasNetwork()) {
            abort("No internet connection.", retryReason = "no_network")
            return
        }

        val cookies = CookieManager.getInstance().getCookie("https://www.tiktok.com").orEmpty()
        if (!RustCore.hasValidSessionCookie(cookies)) {
            repo.updateSession(valid = false)
            abort("TikTok login expired. Open the app and log in again.", retryReason = null)
            return
        }

        val reply = Reply.parse(RustCore.runStartJson(filesDir.absolutePath, targetedCsv))
        val data = reply.obj
        if (!reply.ok || data == null) {
            abort("Could not start the run: ${reply.error}", retryReason = "send_error")
            return
        }
        sessionOpen = true

        when (val step = Step.from(data)) {
            is Step.Done -> finishRun()
            is Step.Inject -> {
                firstStep = step
                openMessagesPage()
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun openMessagesPage() {
        val viewport = Reply.parse(RustCore.computeViewportJson(resources.displayMetrics.density)).obj
        val widthPx = viewport?.optInt("width_px", 1920) ?: 1920
        val heightPx = viewport?.optInt("height_px", 1080) ?: 1080
        val gen = generation

        val view = WebView(applicationContext)
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.settings.userAgentString = RustCore.resolveUserAgent("")
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        view.addJavascriptInterface(Bridge(gen), "StreakApp")
        view.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, widthPx, heightPx)
        view.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                if (!runActive || pageHandled || gen != generation) return
                val address = url.orEmpty()
                when {
                    address.contains("/login") -> {
                        repo.updateSession(valid = false)
                        abort("TikTok login expired. Open the app and log in again.", retryReason = null)
                    }
                    address.contains("tiktok.com/messages") -> {
                        pageHandled = true
                        main.removeCallbacks(pageWatchdog)
                        firstStep?.let { inject(it) }
                    }
                }
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                if (!runActive || pageHandled || gen != generation) return
                if (request?.isForMainFrame == true) {
                    abort("TikTok could not be loaded (${error?.description ?: "network error"}).", retryReason = "send_error")
                }
            }
        }
        webView = view
        main.postDelayed(pageWatchdog, PAGE_TIMEOUT_MS)
        view.loadUrl(MESSAGES_URL)
    }

    private fun inject(step: Step.Inject) {
        awaiting = step
        RunStatus.state.value = RunStatus.State(running = true, index = step.index, total = step.total, current = step.target)
        Notifications.updateRunProgress(this, "${step.index}/${step.total} · ${step.target}", step.index - 1, step.total)
        webView?.evaluateJavascript(step.script, null)
        main.removeCallbacks(sendWatchdog)
        main.postDelayed(sendWatchdog, SEND_TIMEOUT_MS)
    }

    private fun onSendTimeout() {
        val step = awaiting ?: return
        if (runActive) handleCallback(step.target, success = false, error = "Timed out waiting for TikTok")
    }

    private fun handleCallback(user: String, success: Boolean, error: String) {
        main.removeCallbacks(sendWatchdog)
        if (!runActive || !sessionOpen) return
        val reply = Reply.parse(RustCore.runCallbackJson(user, success, error))
        val data = reply.obj
        if (!reply.ok || data == null) {
            Log.w(TAG, "Run state lost: ${reply.error}")
            finishRun()
            return
        }
        when (val step = Step.from(data)) {
            is Step.Inject -> inject(step)
            is Step.Done -> finishRun()
        }
    }

    /** Ends the run normally: saves history, plans the next run, tells the user, shuts down. */
    private fun finishRun(note: String? = null) {
        if (!runActive) return
        val outcome: RunOutcome? = if (sessionOpen) {
            sessionOpen = false
            Reply.parse(RustCore.runFinishJson()).obj?.let { RunOutcome.from(it) }
        } else {
            null
        }

        var retryNote = ""
        if (repo.settings().scheduled) {
            val failed = outcome?.failed ?: 0
            if (failed > 0 && !userStopped) {
                val delay = repo.retryDelayMs("send_error")
                if (delay != null) {
                    Scheduler.armIn(this, delay)
                    retryNote = " Retrying failed chats in 1 hour."
                } else {
                    Scheduler.armFromSettings(this)
                }
            } else {
                if (outcome != null && outcome.total > 0 && failed == 0) repo.recordCleanRun()
                Scheduler.armFromSettings(this)
            }
        }

        val text = when {
            outcome == null -> note ?: "Run ended."
            outcome.total == 0 -> note ?: "Nothing to send, everyone is up to date."
            else -> buildString {
                append("Sent ${outcome.sent} of ${outcome.total}")
                if (outcome.failed > 0) append(" · ${outcome.failed} failed")
                if (outcome.autoDisabled > 0) append(" · ${outcome.autoDisabled} turned off after repeated errors")
                append('.')
                if (note != null) append(' ').append(note)
                append(retryNote)
            }
        }
        val quiet = outcome != null && outcome.total == 0 && !manual && note == null
        if (!quiet) Notifications.status(this, "Streak Saver", text)
        cleanup()
    }

    /** Ends a run that could not send anything: records why, schedules a retry if sensible. */
    private fun abort(message: String, retryReason: String?) {
        if (!runActive) return
        if (sessionOpen) {
            sessionOpen = false
            runCatching { RustCore.runFinishJson() }
        }
        repo.recordSkippedRun(message)

        var tail = ""
        if (retryReason != null && repo.settings().scheduled) {
            val delay = repo.retryDelayMs(retryReason)
            if (delay != null) {
                Scheduler.armIn(this, delay)
                tail = " Trying again in 1 hour."
            } else {
                Scheduler.armFromSettings(this)
                tail = " Next try at the usual time."
            }
        }
        Notifications.status(this, "Streak run skipped", message + tail)
        cleanup()
    }

    private fun cleanup(stopService: Boolean = true) {
        main.removeCallbacks(sendWatchdog)
        main.removeCallbacks(pageWatchdog)
        main.removeCallbacks(deadline)
        generation++
        webView?.let { view ->
            view.stopLoading()
            view.removeJavascriptInterface("StreakApp")
            view.destroy()
        }
        webView = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        runActive = false
        RunStatus.state.value = RunStatus.State()
        if (stopService) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        if (runActive) {
            // The system took the service away mid-run. Counters were saved after every chat; keep the
            // history entry and make sure the next run is still armed.
            if (sessionOpen) {
                sessionOpen = false
                runCatching { RustCore.runFinishJson() }
            }
            runCatching { if (repo.settings().scheduled) Scheduler.armFromSettings(this) }
            cleanup(stopService = false)
        }
        super.onDestroy()
    }

    // --- Helpers -------------------------------------------------------------------------------

    private inner class Bridge(private val gen: Int) {
        @JavascriptInterface
        fun log(msg: String) {
            Log.d("StreakJS", msg)
        }

        @JavascriptInterface
        fun onMessageSent(userName: String, success: Boolean, errorMessage: String) {
            main.post {
                if (gen == generation) handleCallback(userName, success, errorMessage)
            }
        }
    }

    private fun hasNetwork(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "StreakSaver::Run").apply {
            setReferenceCounted(false)
            acquire(RUN_DEADLINE_MS + 60_000L)
        }
    }
}
