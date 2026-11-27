package com.anas.deviceagent

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Lets the app read the active media session (track and cover art) and the notifications the lock-screen wallpaper draws. */
class MediaListener : NotificationListenerService() {
    override fun onListenerConnected() { NotifState.rebuild(this) }
    override fun onNotificationPosted(sbn: StatusBarNotification?) { NotifState.rebuild(this) }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) { NotifState.rebuild(this) }
}
