package com.sxvuj.streaksaver.service

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.sxvuj.streaksaver.data.StreakRepo
import com.sxvuj.streaksaver.receiver.AlarmReceiver

/**
 * Arms and cancels the one system alarm that starts the next streak run, and remembers when it is
 * due so the UI can show a real "next run" and the app can re-arm after a reboot or an update.
 */
object Scheduler {
    private const val RESTART_DELAY_MS = 90_000L
    private const val FIRST_RUN_DELAY_MS = 60_000L
    private const val DEFAULT_DELAY_MS = 23L * 60L * 60L * 1000L

    private fun alarmIntent(context: Context, flags: Int): PendingIntent? {
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            action = AlarmReceiver.ACTION_STREAK_ALARM
        }
        return PendingIntent.getBroadcast(context, 0, intent, flags or PendingIntent.FLAG_IMMUTABLE)
    }

    fun canScheduleExact(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        return am.canScheduleExactAlarms()
    }

    /** Arms the alarm for [triggerAtMs] and stores that time as the next run. */
    fun armAt(context: Context, triggerAtMs: Long) {
        val app = context.applicationContext
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = alarmIntent(app, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        try {
            if (canScheduleExact(app)) {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
            }
        } catch (e: SecurityException) {
            // Exact alarm access was revoked between the check and the call.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMs, pi)
        }
        StreakRepo(app).setNextRun(triggerAtMs)
    }

    /** Cancels the alarm and forgets the stored next run. */
    fun cancel(context: Context) {
        val app = context.applicationContext
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmIntent(app, PendingIntent.FLAG_UPDATE_CURRENT)?.let { pi ->
            am.cancel(pi)
            pi.cancel()
        }
        StreakRepo(app).setNextRun(0L)
    }

    /** Arms the next run from the saved schedule (interval or fixed local time). Returns the trigger time. */
    fun armFromSettings(context: Context): Long {
        val repo = StreakRepo(context)
        val delay = repo.normalDelayMs() ?: DEFAULT_DELAY_MS
        val at = System.currentTimeMillis() + delay
        armAt(context, at)
        return at
    }

    /** Arms a run in [delayMs] (hourly retries). */
    fun armIn(context: Context, delayMs: Long) {
        armAt(context, System.currentTimeMillis() + delayMs)
    }

    /**
     * First run after automation is switched on. A fixed daily time waits for its slot; an interval
     * schedule starts in a minute, because the streak should not wait a whole interval for its first
     * message (chats that were messaged recently are skipped by the run itself).
     */
    fun armFirstRun(context: Context): Long {
        if (StreakRepo(context).settings().useFixedTime) return armFromSettings(context)
        val at = System.currentTimeMillis() + FIRST_RUN_DELAY_MS
        armAt(context, at)
        return at
    }

    /**
     * Brings the alarm back after a reboot, an app update or opening the app: keeps the stored time
     * when it is still ahead, runs soon when it was missed and starts fresh when there is none.
     * [recompute] is for a time-zone change, which only moves a fixed local time.
     */
    fun restore(context: Context, recompute: Boolean = false) {
        val repo = StreakRepo(context)
        val settings = repo.settings()
        if (!settings.scheduled) return
        val stored = repo.nextRunMs()
        val now = System.currentTimeMillis()
        when {
            stored <= 0L -> armFirstRun(context)
            recompute && settings.useFixedTime -> armFromSettings(context)
            stored > now -> armAt(context, stored)
            else -> armAt(context, now + RESTART_DELAY_MS)
        }
    }
}
