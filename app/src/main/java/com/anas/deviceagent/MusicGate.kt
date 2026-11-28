package com.anas.deviceagent

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState

/** Decides whether what is playing is a song. Cheap: the session lookup is cached for 2.5 s. */
object MusicGate {
    private val deny = listOf("youtube", "netflix", "tiktok", "musically", "instagram", "facebook", "whatsapp", "telegram",
        "twitter", "snapchat", "chrome", "firefox", "opera", "edge", "browser", "mxtech", "vlc", "primevideo", "disney", "hotstar",
        "shahid", "watchit", "twitch", "kwai", "likee", "zoom", "meet", "discord", "game", "reddit", "linkedin", "pinterest", "video", "player.video")
    private var checkedAt = 0L
    private var lastOk = false
    private var okSince = 0L
    private var badSince = 0L
    private var out = false

    private fun sessionIsSong(ctx: Context): Boolean {
        return try {
            val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val list = msm.getActiveSessions(ComponentName(ctx, MediaListener::class.java))
            val c = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: return false
            val pkg = c.packageName.lowercase()
            if (deny.any { pkg.contains(it) } && !pkg.contains("youtube.music")) return false
            val dur = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            // songs are rarely under 45 s; reels, clips and short videos are
            dur <= 0L || dur >= 45000L
        } catch (e: Throwable) { true } // no notification access: fall back to the plain playing flag
    }

    /** true only while a song-like session is playing, held for 1.5 s to start and 2 s to stop (no flicker between tracks). */
    fun playing(ctx: Context, now: Long): Boolean {
        if (now - checkedAt >= 2500L) {
            checkedAt = now
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            lastOk = am.isMusicActive && sessionIsSong(ctx)
        }
        if (lastOk) { badSince = 0L; if (okSince == 0L) okSince = now; if (!out && now - okSince >= 1500L) out = true }
        else { okSince = 0L; if (badSince == 0L) badSince = now; if (out && now - badSince >= 2000L) out = false }
        return out
    }
}
