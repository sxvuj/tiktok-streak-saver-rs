package com.sxvuj.streaksaver.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.sxvuj.streaksaver.R
import com.sxvuj.streaksaver.ui.MainActivity

/** Notification channels and builders for the streak service. */
object Notifications {
    const val CHANNEL_RUN = "streak_service_channel"
    const val CHANNEL_STATUS = "streak_status_channel"
    const val ID_RUN = 1001
    const val ID_STATUS = 1002

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RUN, "Streak run in progress", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_STATUS, "Streak results", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    private fun openApp(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        return PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun stopRun(context: Context): PendingIntent {
        val intent = Intent(context, StreakService::class.java).apply { action = StreakService.ACTION_STOP_SERVICE }
        return PendingIntent.getService(context, 1, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun runProgress(context: Context, text: String, index: Int = 0, total: Int = 0): Notification {
        val builder = NotificationCompat.Builder(context, CHANNEL_RUN)
            .setContentTitle("Sending streaks")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_streak)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp(context))
            .addAction(0, "Stop", stopRun(context))
        if (total > 0) builder.setProgress(total, index.coerceIn(0, total), false)
        return builder.build()
    }

    fun updateRunProgress(context: Context, text: String, index: Int, total: Int) {
        notify(context, ID_RUN, runProgress(context, text, index, total))
    }

    fun status(context: Context, title: String, text: String) {
        val notification = NotificationCompat.Builder(context, CHANNEL_STATUS)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setSmallIcon(R.drawable.ic_stat_streak)
            .setAutoCancel(true)
            .setContentIntent(openApp(context))
            .build()
        notify(context, ID_STATUS, notification)
    }

    private fun notify(context: Context, id: Int, notification: Notification) {
        try {
            val manager = NotificationManagerCompat.from(context)
            if (manager.areNotificationsEnabled()) {
                // POST_NOTIFICATIONS may still be missing on Android 13+, which notify() reports as a SecurityException.
                manager.notify(id, notification)
            }
        } catch (e: SecurityException) {
            // Notification permission denied: the run itself does not depend on it.
        }
    }
}
