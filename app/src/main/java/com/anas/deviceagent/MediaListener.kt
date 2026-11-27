package com.anas.deviceagent

import android.service.notification.NotificationListenerService

/** Only exists so the app may read the active media session (track and cover art). */
class MediaListener : NotificationListenerService()
