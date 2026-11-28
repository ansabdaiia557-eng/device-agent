package com.anas.deviceagent

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

/** Shared wallpaper helpers: smart battery mode. */
object WpExtras {
    @Volatile private var low = false
    @Volatile private var checked = 0L

    fun lowBattery(ctx: Context): Boolean {
        val now = System.currentTimeMillis()
        if (now - checked < 20000L) return low
        checked = now
        try {
            val on = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE).getBoolean("wp_batt_saver", true)
            val i: Intent? = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            if (!on || i == null) { low = false; return low }
            val lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, 100); val sc = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            val pct = if (sc > 0) lvl * 100 / sc else 100
            val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            // hysteresis: enter below 20%, leave at 23% or when charging
            low = if (plugged) false else if (low) pct < 23 else pct < 20
        } catch (e: Throwable) { low = false }
        return low
    }
}
