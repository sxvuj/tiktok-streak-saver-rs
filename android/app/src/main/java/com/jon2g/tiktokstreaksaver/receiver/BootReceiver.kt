package com.jon2g.tiktokstreaksaver.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.jon2g.tiktokstreaksaver.rust.RustCore
import com.jon2g.tiktokstreaksaver.service.StreakService
import org.json.JSONObject

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val dir = context.filesDir.absolutePath
        val settings = JSONObject(RustCore.loadSettingsJson(dir)).optJSONObject("data") ?: return
        if (!settings.optBoolean("is_scheduled", false)) return

        val next = JSONObject(RustCore.computeNextRunJson(dir)).optJSONObject("data") ?: return
        val delayMs = next.optLong("delay_ms", 23L * 3600L * 1000L)
        StreakService.scheduleAlarmAt(context.applicationContext, System.currentTimeMillis() + delayMs)
    }
}
