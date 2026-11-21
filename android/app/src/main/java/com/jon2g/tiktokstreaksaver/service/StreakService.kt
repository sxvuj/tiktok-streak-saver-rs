package com.jon2g.tiktokstreaksaver.service

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.app.NotificationCompat
import com.jon2g.tiktokstreaksaver.receiver.AlarmReceiver
import com.jon2g.tiktokstreaksaver.rust.RustCore
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Android Foreground Service for background TikTok streak messaging.
 *
 * Mirrors `Jon2G/TiktokStreakSaver` v2.2.0 `StreakService`:
 * - Creates the headless WebView on the main thread sized to 1920x1080 density-scaled pixels.
 * - Delegates scheduling, retry budgets, run planning, UA fallback, and JS rendering to `RustCore`.
 * - Resets the static running mutex in `onDestroy` so an OS kill cannot wedge future runs.
 */
class StreakService : Service() {

    companion object {
        private const val TAG = "StreakService"
        const val ACTION_STOP_SERVICE = "STOP_SERVICE"
        const val EXTRA_TARGET_FRIEND_IDS = "target_friend_ids"
        const val CHANNEL_SERVICE = "streak_service_channel"
        const val CHANNEL_STATUS = "streak_status_channel"
        private const val NOTIF_ID_SERVICE = 1001
        private const val NOTIF_ID_STATUS = 1002
        private const val MESSAGES_URL = "https://www.tiktok.com/messages?lang=en"

        val isRunning = AtomicBoolean(false)

        fun scheduleAlarmAt(context: Context, triggerAtMillis: Long) {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val intent = Intent(context, AlarmReceiver::class.java).apply {
                action = AlarmReceiver.ACTION_STREAK_ALARM
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pi = PendingIntent.getBroadcast(context, 0, intent, flags)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            } else {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pi)
            }
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var webView: WebView? = null
    private val targets = mutableListOf<JSONObject>()
    private var currentIndex = 0
    private var sentCount = 0
    private var failedCount = 0
    private var customMessage = "Hey! Keeping our streak alive! 🔥"
    private var randomizeMessages = false
    private var storageDirPath = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            Log.i(TAG, "Service stop requested by user")
            finishAndStop("Run stopped by user.", hadErrors = false)
            return START_NOT_STICKY
        }

        if (!isRunning.compareAndSet(false, true)) {
            Log.w(TAG, "OnStartCommand ignored — automation already running")
            return START_NOT_STICKY
        }

        storageDirPath = filesDir.absolutePath
        ensureChannels()
        startForeground(NOTIF_ID_SERVICE, buildProgressNotification("Preparing to send streaks..."))
        acquireWakeLock()

        if (!hasActiveNetwork()) {
            handleOfflineSkip()
            return START_NOT_STICKY
        }

        val targetedIds = intent?.getStringExtra(EXTRA_TARGET_FRIEND_IDS).orEmpty()
        prepareAndStartWebView(targetedIds)
        return START_NOT_STICKY
    }

    private fun prepareAndStartWebView(targetedIdsCsv: String) {
        val settingsWrapper = JSONObject(RustCore.loadSettingsJson(storageDirPath))
        val settings = settingsWrapper.optJSONObject("data") ?: JSONObject()
        customMessage = settings.optString("message_text", customMessage)
        randomizeMessages = settings.optBoolean("randomize_normal_messages", false)

        val planWrapper = JSONObject(RustCore.buildRunPlanJson(storageDirPath, targetedIdsCsv))
        val plan = planWrapper.optJSONObject("data") ?: JSONObject()
        val arr = plan.optJSONArray("targets") ?: JSONArray()
        targets.clear()
        for (i in 0 until arr.length()) {
            targets.add(arr.getJSONObject(i))
        }

        if (targets.isEmpty()) {
            finishAndStop("All friends already messaged today or none configured.", hadErrors = false)
            return
        }

        val cookies = CookieManager.getInstance().getCookie("https://www.tiktok.com").orEmpty()
        if (!RustCore.hasValidSessionCookie(cookies)) {
            postStatusNotification("TikTok login required", "Please login via the app first.")
            finishAndStop("TikTok login required.", hadErrors = true)
            return
        }

        mainHandler.post { initHeadlessWebView() }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun initHeadlessWebView() {
        val density = resources.displayMetrics.density
        val vpWrapper = JSONObject(RustCore.computeViewportJson(density))
        val vp = vpWrapper.optJSONObject("data")
        val widthPx = vp?.optInt("width_px", 1920) ?: 1920
        val heightPx = vp?.optInt("height_px", 1080) ?: 1080

        val wv = WebView(applicationContext).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = RustCore.resolveUserAgent("")
            addJavascriptInterface(StreakJsBridge(), "StreakApp")
            measure(
                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
            )
            layout(0, 0, widthPx, heightPx)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (url != null && url.contains("tiktok.com/messages")) {
                        injectNextFriend()
                    }
                }
            }
        }
        webView = wv
        wv.loadUrl(MESSAGES_URL)
    }

    private fun injectNextFriend() {
        if (currentIndex >= targets.size) {
            val summary = "Done: $sentCount sent, $failedCount failed"
            if (failedCount == 0) {
                RustCore.recordCleanRunSuccess(storageDirPath)
                scheduleNormalNextRun()
            } else {
                scheduleRetryOrNormal("send_error")
            }
            postStatusNotification("TikTok Streak Saver", summary)
            finishAndStop(summary, hadErrors = failedCount > 0)
            return
        }

        val friend = targets[currentIndex]
        val username = friend.optString("Username", "")
        val isGroup = friend.optBoolean("IsGroup", false)
        val msg = if (randomizeMessages) RustCore.pickRandomMessage() else customMessage
        val script = RustCore.renderAutomationScript(username, msg, isGroup)

        updateProgressNotification("Sending ${currentIndex + 1}/${targets.size}: $username")
        mainHandler.post { webView?.evaluateJavascript(script, null) }
    }

    private inner class StreakJsBridge {
        @JavascriptInterface
        fun log(msg: String) {
            Log.d("StreakJS", msg)
        }

        @JavascriptInterface
        fun onMessageSent(userName: String, success: Boolean, errorMessage: String) {
            if (success) {
                sentCount++
            } else {
                failedCount++
                Log.w(TAG, "Failed for $userName: $errorMessage")
            }
            currentIndex++
            mainHandler.post { injectNextFriend() }
        }
    }

    private fun handleOfflineSkip() {
        scheduleRetryOrNormal("no_network")
        postStatusNotification(
            "TikTok Streak Saver — offline",
            "No Wi-Fi or mobile data. Streak run skipped; retrying in 1 hour."
        )
        finishAndStop("Skipped: no network", hadErrors = true)
    }

    private fun scheduleNormalNextRun() {
        val res = JSONObject(RustCore.computeNextRunJson(storageDirPath))
        val delayMs = res.optJSONObject("data")?.optLong("delay_ms", 23L * 3600L * 1000L)
            ?: (23L * 3600L * 1000L)
        scheduleAlarmAt(applicationContext, System.currentTimeMillis() + delayMs)
    }

    private fun scheduleRetryOrNormal(reason: String) {
        val res = JSONObject(RustCore.evaluateRetryScheduleJson(storageDirPath, reason))
        val data = res.optJSONObject("data")
        val retryObj = data?.optJSONObject("RetryInOneHour")
        if (retryObj != null) {
            scheduleAlarmAt(applicationContext, System.currentTimeMillis() + 3600L * 1000L)
        } else {
            scheduleNormalNextRun()
        }
    }

    private fun hasActiveNetwork(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "TiktokStreakSaver::StreakWakeLock"
        ).apply { acquire(10 * 60 * 1000L) }
    }

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_SERVICE,
                    "TikTok Streak Saver",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_STATUS,
                    "Streak status",
                    NotificationManager.IMPORTANCE_DEFAULT
                )
            )
        }
    }

    private fun buildProgressNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setContentTitle("TikTok Streak Saver")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setOngoing(true)
            .build()

    private fun updateProgressNotification(text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIF_ID_SERVICE, buildProgressNotification(text))
    }

    private fun postStatusNotification(title: String, text: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notif = NotificationCompat.Builder(this, CHANNEL_STATUS)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setAutoCancel(true)
            .build()
        nm.notify(NOTIF_ID_STATUS, notif)
    }

    private fun finishAndStop(summary: String, hadErrors: Boolean) {
        Log.i(TAG, "Finishing service (hadErrors=$hadErrors): $summary")
        mainHandler.post {
            webView?.destroy()
            webView = null
        }
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        webView?.destroy()
        webView = null
        // Safety net: reset static mutex even if system kills the service.
        isRunning.set(false)
    }
}
