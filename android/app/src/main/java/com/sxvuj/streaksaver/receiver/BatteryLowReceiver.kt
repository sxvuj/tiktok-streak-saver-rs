package com.sxvuj.streaksaver.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.sxvuj.streaksaver.rust.RustCore
import com.sxvuj.streaksaver.service.StreakService

class BatteryLowReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BATTERY_LOW) return
        if (RustCore.shouldAnticipateBatteryLow(context.filesDir.absolutePath)) {
            val svcIntent = Intent(context, StreakService::class.java)
            ContextCompat.startForegroundService(context, svcIntent)
        }
    }
}
