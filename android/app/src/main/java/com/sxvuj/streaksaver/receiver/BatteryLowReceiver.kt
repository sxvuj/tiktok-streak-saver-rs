package com.sxvuj.streaksaver.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sxvuj.streaksaver.data.StreakRepo
import com.sxvuj.streaksaver.service.StreakService

/** Runs the streak early, once a day, when the battery is low and the user asked for that. */
class BatteryLowReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BATTERY_LOW) return
        try {
            val app = context.applicationContext
            if (StreakRepo(app).shouldRunOnBatteryLow()) StreakService.start(app)
        } catch (e: Exception) {
            Log.e("BatteryLowReceiver", "Could not start the early run", e)
        }
    }
}
