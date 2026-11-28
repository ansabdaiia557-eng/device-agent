package com.anas.deviceagent

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.widget.Toast

/** Latest left/right balance from the playback capture. pan: -1 = all left, +1 = all right. */
object StereoState {
    @Volatile var pan = 0f
    @Volatile var tilt = 0f
    @Volatile var sL = 0f
    @Volatile var sR = 0f
    @Volatile var sD = 0f
    @Volatile var sU = 0f
    @Volatile var lastT = 0L
    @Volatile var energy = 0f
    @Volatile var active = false
    @Volatile var error = ""
    fun live(now: Long): Boolean = active && now - lastT < 900L && energy > 100f
}

class StereoActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 21)
        } else ask()
    }

    private fun ask() {
        try {
            val m = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(m.createScreenCaptureIntent(), 22)
        } catch (e: Throwable) { Toast.makeText(this, "مش قادر أفتح طلب الإذن: " + e.message, Toast.LENGTH_LONG).show(); finish() }
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        if (r.isNotEmpty() && r[0] == PackageManager.PERMISSION_GRANTED) ask()
        else { Toast.makeText(this, "لازم إذن الصوت", Toast.LENGTH_LONG).show(); finish() }
    }

    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 22) {
            if (res == RESULT_OK && data != null) {
                val i = Intent(this, StereoService::class.java).putExtra("code", res).putExtra("data", data)
                try {
                    if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
                    Toast.makeText(this, "تم، الاستيريو الحقيقي شغال. شغل موسيقى", Toast.LENGTH_LONG).show()
                } catch (e: Throwable) { Toast.makeText(this, "فشل التشغيل: " + e.message, Toast.LENGTH_LONG).show() }
            } else Toast.makeText(this, "رفضت الإذن، هيفضل الحركة مع الإيقاع", Toast.LENGTH_LONG).show()
            finish()
        }
    }
}

class StereoService : Service() {
    private var rec: AudioRecord? = null
    private var mp: MediaProjection? = null
    @Volatile private var run = false
    private var th: Thread? = null

    override fun onBind(i: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, id: Int): Int {
        if (intent == null || intent.action == "stop") { stopSelf(); return START_NOT_STICKY }
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("stereo", "Stereo wallpaper", NotificationManager.IMPORTANCE_MIN))
            val stop = android.app.PendingIntent.getService(this, 1, Intent(this, StereoService::class.java).setAction("stop"), android.app.PendingIntent.FLAG_IMMUTABLE)
            val n = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "stereo") else Notification.Builder(this))
                .setContentTitle("Device Agent: ستيريو الخلفية شغال")
                .setContentText("دوس للإيقاف")
                .setSmallIcon(android.R.drawable.ic_media_play).setContentIntent(stop).build()
            if (Build.VERSION.SDK_INT >= 29) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION) else startForeground(7, n)
            if (Build.VERSION.SDK_INT < 29) { StereoState.error = "needs Android 10+"; stopSelf(); return START_NOT_STICKY }
            val data = intent.getParcelableExtra<Intent>("data")
            val code = intent.getIntExtra("code", 0)
            val m = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val p = m.getMediaProjection(code, data!!)
            mp = p
            p.registerCallback(object : MediaProjection.Callback() { override fun onStop() { stopSelf() } }, null)
            startCapture(p)
        } catch (e: Throwable) { StereoState.error = "start: " + e.message; StereoState.active = false; stopSelf() }
        return START_NOT_STICKY
    }

    @android.annotation.TargetApi(29)
    private fun startCapture(p: MediaProjection) {
        val cfg = AudioPlaybackCaptureConfiguration.Builder(p)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA).addMatchingUsage(AudioAttributes.USAGE_GAME).addMatchingUsage(AudioAttributes.USAGE_UNKNOWN).build()
        val fmt = AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(44100).setChannelMask(AudioFormat.CHANNEL_IN_STEREO).build()
        val r = AudioRecord.Builder().setAudioFormat(fmt).setAudioPlaybackCaptureConfig(cfg).setBufferSizeInBytes(16384).build()
        rec = r
        r.startRecording()
        run = true; StereoState.active = true; StereoState.error = ""
        th = Thread {
            val buf = ShortArray(2048)
            var smooth = 0f
            var eSm = 0f
            var tSm = 0f
            var tBase = 0f
            var lp = 0.0
            var pkL = 1f; var pkR = 1f; var pkLo = 1f; var pkHi = 1f
            while (run) {
                val n = r.read(buf, 0, buf.size)
                if (n <= 0) { try { Thread.sleep(20) } catch (e: Throwable) {}; continue }
                var sl = 0.0; var sr = 0.0
                var i = 0
                var lfE = 0.0; var hfE = 0.0
                while (i + 1 < n) { val l = buf[i].toDouble(); val rr = buf[i + 1].toDouble(); sl += l * l; sr += rr * rr; val mo = (l + rr) * 0.5; lp += (mo - lp) * 0.06; val hp = mo - lp; lfE += lp * lp; hfE += hp * hp; i += 2 }
                val frames = (n / 2).coerceAtLeast(1)
                val rl = Math.sqrt(sl / frames).toFloat(); val rrms = Math.sqrt(sr / frames).toFloat()
                val e = (rl + rrms) / 2f
                eSm = if (e > eSm) e else eSm * 0.9f
                if (e > 150f) {
                    val raw = (rrms - rl) / (rrms + rl + 1e-3f)
                    // deadzone: only a clearly one-sided mix moves the ball
                    val m = Math.max(0f, Math.abs(raw) - 0.10f) / 0.36f
                    val tgt = (if (raw < 0f) -m else m).coerceIn(-1f, 1f)
                    smooth += (tgt - smooth) * 0.25f
                    StereoState.pan = smooth
                    val tl = ((hfE - lfE) / (hfE + lfE + 1.0)).toFloat()
                    tBase += (tl - tBase) * 0.01f
                    val tm = tl - tBase
                    val tt = (if (tm < 0f) -Math.max(0f, -tm - 0.06f) else Math.max(0f, tm - 0.06f)) / 0.25f
                    tSm += (tt.coerceIn(-1f, 1f) - tSm) * 0.25f
                    StereoState.tilt = tSm
                } else { smooth *= 0.9f; StereoState.pan = smooth; tSm *= 0.9f; StereoState.tilt = tSm }
                run {
                    // per-side pull strengths relative to each band's own slow peak
                    pkL = Math.max(rl, pkL * 0.998f).coerceAtLeast(200f); pkR = Math.max(rrms, pkR * 0.998f).coerceAtLeast(200f)
                    val lo = Math.sqrt(lfE / frames).toFloat(); val hi = Math.sqrt(hfE / frames).toFloat()
                    pkLo = Math.max(lo, pkLo * 0.998f).coerceAtLeast(150f); pkHi = Math.max(hi, pkHi * 0.998f).coerceAtLeast(40f)
                    fun st(v: Float, pk: Float) = (Math.max(0f, v / pk - 0.5f) / 0.5f).coerceIn(0f, 0.8f)
                    val g = e > 150f
                    StereoState.sL = if (g) st(rl, pkL) else 0f; StereoState.sR = if (g) st(rrms, pkR) else 0f
                    StereoState.sD = if (g) st(lo, pkLo) else 0f; StereoState.sU = if (g) st(hi, pkHi) else 0f
                }
                StereoState.energy = eSm
                StereoState.lastT = System.currentTimeMillis()
            }
        }.also { it.isDaemon = true; it.priority = Thread.MIN_PRIORITY; it.start() }
    }

    override fun onDestroy() {
        run = false; StereoState.active = false
        try { rec?.stop() } catch (e: Throwable) {}
        try { rec?.release() } catch (e: Throwable) {}
        try { mp?.stop() } catch (e: Throwable) {}
        rec = null; mp = null
        super.onDestroy()
    }
}
