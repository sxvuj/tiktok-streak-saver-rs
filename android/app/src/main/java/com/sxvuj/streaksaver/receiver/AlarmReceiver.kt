package com.sxvuj.streaksaver.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.sxvuj.streaksaver.service.StreakService

class AlarmReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_STREAK_ALARM = "com.sxvuj.streaksaver.ACTION_STREAK_ALARM"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STREAK_ALARM) return
        val svcIntent = Intent(context, StreakService::class.java)
        ContextCompat.startForegroundService(context, svcIntent)
    }
}
