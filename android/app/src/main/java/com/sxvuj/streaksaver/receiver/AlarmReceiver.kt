package com.sxvuj.streaksaver.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sxvuj.streaksaver.data.StreakRepo
import com.sxvuj.streaksaver.service.Notifications
import com.sxvuj.streaksaver.service.Scheduler
import com.sxvuj.streaksaver.service.StreakService

/** Fires when the armed alarm is due and starts a streak run. */
class AlarmReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_STREAK_ALARM = "com.sxvuj.streaksaver.ACTION_STREAK_ALARM"
        private const val TAG = "AlarmReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STREAK_ALARM) return
        try {
            StreakService.start(context.applicationContext)
        } catch (e: Exception) {
            // Android refused a background start. Say so, keep the schedule alive and let the user run it by hand.
            Log.e(TAG, "Could not start the streak service", e)
            val app = context.applicationContext
            val repo = StreakRepo(app)
            repo.recordSkippedRun("Android would not let the app start in the background.")
            Notifications.ensureChannels(app)
            Notifications.status(app, "Streak run skipped", "Android blocked the background start. Open the app and tap Run now.")
            if (repo.settings().scheduled) Scheduler.armFromSettings(app)
        }
    }
}
