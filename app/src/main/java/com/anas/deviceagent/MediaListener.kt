package com.anas.deviceagent

import android.app.Notification
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Lets the app read the active media session (track and cover art) and the notifications the lock-screen wallpaper draws.
 *  Optionally cancels other apps' media notifications while the phone is locked, so the stock lock-screen media card is hidden. */
class MediaListener : NotificationListenerService() {
    private val h = Handler(Looper.getMainLooper())
    private val sweep = Runnable { sweepMedia() }
    companion object {
        @Volatile var inst: MediaListener? = null
        @Volatile var onChange: (() -> Unit)? = null
        fun dismiss(key: String) { try { inst?.cancelNotification(key) } catch (e: Throwable) {} }
    }
    private val rx = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            h.removeCallbacks(sweep); h.postDelayed(sweep, 600)
        }
    }

    private fun hideOn(): Boolean = getSharedPreferences("prefs", Context.MODE_PRIVATE).getBoolean("wp_hide_stock_media", true)

    private fun isMedia(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName == packageName) return false
        val n = sbn.notification ?: return false
        return n.category == Notification.CATEGORY_TRANSPORT || n.extras?.containsKey("android.mediaSession") == true
    }

    private fun sweepMedia() {
        try {
            if (!hideOn() || !NotifState.locked(this)) return
            val all = activeNotifications ?: return
            for (s in all) if (isMedia(s)) try { cancelNotification(s.key) } catch (e: Throwable) {}
        } catch (e: Throwable) {}
    }

    override fun onListenerConnected() {
        inst = this
        NotifState.rebuild(this)
        Thread { LockAssets.load(this) }.start()
        if (LockSetup.enabled(this)) LockSetup.startService(this)
        try {
            val f = IntentFilter(Intent.ACTION_SCREEN_OFF)
            registerReceiver(rx, f)
        } catch (e: Throwable) {}
    }
    override fun onListenerDisconnected() { inst = null; try { unregisterReceiver(rx) } catch (e: Throwable) {} }
    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        NotifState.rebuild(this); onChange?.invoke()
        if (sbn != null && hideOn() && isMedia(sbn)) { h.removeCallbacks(sweep); h.postDelayed(sweep, 350) }
    }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) { NotifState.rebuild(this); onChange?.invoke() }
}
