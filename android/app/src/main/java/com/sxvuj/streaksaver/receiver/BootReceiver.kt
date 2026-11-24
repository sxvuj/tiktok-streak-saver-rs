package com.sxvuj.streaksaver.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.sxvuj.streaksaver.service.Scheduler

/**
 * Alarms are lost when the phone restarts or the app is updated, and a fixed local time moves when
 * the time zone changes. This puts the schedule back in all three cases.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        try {
            when (intent.action) {
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED -> Scheduler.restore(context.applicationContext)
                Intent.ACTION_TIMEZONE_CHANGED -> Scheduler.restore(context.applicationContext, recompute = true)
            }
        } catch (e: Exception) {
            Log.e("BootReceiver", "Could not restore the schedule", e)
        }
    }
}
