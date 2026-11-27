package com.anas.deviceagent

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.media.AudioManager
import android.media.audiofx.Visualizer
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.media.MediaMetadata
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder

/** Shared state, read by the app screen to show which mode is active. */
object MusicState {
    @Volatile var mode = "idle" // idle | visualizer | cover | simple
    @Volatile var title = ""
    @Volatile var listenerOk = true
    @Volatile var art: Bitmap? = null
    @Volatile var artColor = Color.rgb(110, 70, 220)

    fun refresh(ctx: Context) {
        try {
            val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val list: List<MediaController> = msm.getActiveSessions(ComponentName(ctx, MediaListener::class.java))
            listenerOk = true
            val c = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull() ?: return
            val md = c.metadata ?: return
            title = (md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "") + " " + (md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: "")
            val bmp = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            if (bmp != null && bmp !== art) {
                val small = Bitmap.createScaledBitmap(bmp, 24, 24, true)
                art = small
                var r = 0L; var g = 0L; var b = 0L; var n = 0
                for (x in 0 until 24) for (y in 0 until 24) {
                    val p = small.getPixel(x, y); r += Color.red(p); g += Color.green(p); b += Color.blue(p); n++
                }
                val hsv = FloatArray(3)
                Color.RGBToHSV((r / n).toInt(), (g / n).toInt(), (b / n).toInt(), hsv)
                hsv[1] = Math.max(hsv[1], 0.55f); hsv[2] = Math.max(hsv[2], 0.8f)
                artColor = Color.HSVToColor(hsv)
            }
        } catch (e: SecurityException) {
            listenerOk = false
        } catch (e: Throwable) {}
    }
}

class MusicWallpaper : WallpaperService() {
    override fun onCreateEngine(): Engine = E()

    inner class E : Engine() {
        private val h = Handler(Looper.getMainLooper())
        private var visible = false
        private var viz: Visualizer? = null
        private var vizFailed = false
        private var vizStart = 0L
        private var lastData = 0L
        private val bands = FloatArray(32)
        private var lastMeta = 0L
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var t = 0f
        private var base: Bitmap? = null
        private var baseW = 0
        private var baseH = 0
        private var pulse = 0f

        private fun loadBase(w: Int, h: Int) {
            if (base != null && baseW == w && baseH == h) return
            try {
                val f = java.io.File(filesDir, "wp_base.jpg")
                if (!f.exists()) { base = null; return }
                val src = android.graphics.BitmapFactory.decodeFile(f.absolutePath) ?: return
                val sc = Math.max(w.toFloat() / src.width, h.toFloat() / src.height) * 1.08f
                val bw = (src.width * sc).toInt(); val bh = (src.height * sc).toInt()
                base = Bitmap.createScaledBitmap(src, bw, bh, true); baseW = w; baseH = h
            } catch (e: Throwable) { base = null }
        }
        private val tick = Runnable { step() }

        override fun onVisibilityChanged(v: Boolean) {
            visible = v
            if (v) { base = null }
            h.removeCallbacks(tick)
            if (v) h.post(tick) else stopViz()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            if (visible) { h.removeCallbacks(tick); h.post(tick) }
        }

        override fun onDestroy() {
            visible = false
            h.removeCallbacks(tick)
            stopViz()
            super.onDestroy()
        }

        private fun stopViz() {
            try { viz?.enabled = false; viz?.release() } catch (e: Throwable) {}
            viz = null
        }

        private fun startViz() {
            if (viz != null || vizFailed) return
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { return }
            try {
                val v = Visualizer(0)
                v.enabled = false
                v.captureSize = Visualizer.getCaptureSizeRange()[0].coerceAtLeast(256).coerceAtMost(Visualizer.getCaptureSizeRange()[1])
                v.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(vz: Visualizer?, w: ByteArray?, r: Int) {}
                    override fun onFftDataCapture(vz: Visualizer?, fft: ByteArray?, r: Int) {
                        if (fft == null) return
                        val n = fft.size / 2
                        var total = 0f
                        for (i in 0 until 32) {
                            val from = 1 + i * (n - 1) / 32 / 2
                            val to = Math.max(from + 1, 1 + (i + 1) * (n - 1) / 32 / 2)
                            var s = 0f
                            for (k in from until Math.min(to, n)) {
                                val re = fft[2 * k].toFloat(); val im = fft[2 * k + 1].toFloat()
                                s += Math.hypot(re.toDouble(), im.toDouble()).toFloat()
                            }
                            val m = Math.min(1f, s / (to - from) / 60f)
                            bands[i] = bands[i] * 0.6f + m * 0.4f
                            total += m
                        }
                        if (total > 0.05f) lastData = System.currentTimeMillis()
                    }
                }, Visualizer.getMaxCaptureRate() / 2, false, true)
                v.enabled = true
                viz = v
                vizStart = System.currentTimeMillis()
            } catch (e: Throwable) {
                vizFailed = true
                stopViz()
            }
        }

        private fun step() {
            if (!visible) return
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val playing = am.isMusicActive
            val now = System.currentTimeMillis()
            if (!playing) {
                stopViz()
                MusicState.mode = "idle"
                draw(false, 0f)
                h.postDelayed(tick, 1000)
                return
            }
            startViz()
            if (now - lastMeta > 3000) { lastMeta = now; MusicState.refresh(this@MusicWallpaper) }
            val vizLive = viz != null && now - lastData < 3000
            // visualizer produced nothing for 4s: drop it and retry later
            if (viz != null && !vizLive && now - vizStart > 4000) { stopViz(); vizStart = now; vizFailed = false }
            MusicState.mode = if (vizLive) "visualizer" else if (MusicState.art != null) "cover" else "simple"
            draw(true, if (vizLive) 1f else 0f)
            h.postDelayed(tick, 33)
        }

        private fun draw(playing: Boolean, real: Float) {
            val holder = surfaceHolder
            var c: Canvas? = null
            try {
                c = holder.lockCanvas() ?: return
                val w = c.width.toFloat(); val hh = c.height.toFloat()
                t += 0.033f
                val base = MusicState.artColor
                val top = if (playing) Color.argb(255, Color.red(base) / 4, Color.green(base) / 4, Color.blue(base) / 4) else Color.rgb(8, 8, 14)
                paint.shader = LinearGradient(0f, 0f, 0f, hh, top, Color.BLACK, Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, w, hh, paint)
                paint.shader = null
                loadBase(c.width, c.height)
                val bimg = base
                if (bimg != null) {
                    var e = 0f
                    for (i in 0 until 32) e += if (real > 0f) bands[i] else (0.35f + 0.3f * Math.sin((t * 4f + i * 0.45f).toDouble()).toFloat())
                    e /= 32f
                    pulse = pulse * 0.7f + (if (playing) e else 0f) * 0.3f
                    val z = 1f + 0.05f * pulse
                    c.save()
                    c.scale(z, z, w / 2, hh / 2)
                    c.drawBitmap(bimg, (w - bimg.width) / 2f, (hh - bimg.height) / 2f, null)
                    c.restore()
                    if (playing) {
                        paint.color = MusicState.artColor
                        paint.alpha = (40 + 90 * pulse).toInt().coerceIn(0, 140)
                        c.drawRect(0f, 0f, w, hh, paint)
                        paint.alpha = 255
                    }
                }
                if (playing) {
                    val art = MusicState.art
                    if (art != null && real == 0f && bimg == null) {
                        paint.alpha = 70
                        c.drawBitmap(art, null, RectF(0f, 0f, w, hh), paint)
                        paint.alpha = 255
                    }
                    var avg = 0f
                    val bw = w / 32f
                    for (i in 0 until 32) {
                        val v = if (real > 0f) bands[i]
                        else (0.35f + 0.3f * Math.sin((t * 4f + i * 0.45f).toDouble()).toFloat() + 0.2f * Math.sin((t * 7f - i * 0.8f).toDouble()).toFloat()).coerceIn(0.05f, 1f)
                        avg += v
                        val bh = v * hh * 0.35f
                        paint.color = base
                        paint.alpha = 200
                        c.drawRoundRect(RectF(i * bw + bw * 0.15f, hh * 0.78f - bh, (i + 1) * bw - bw * 0.15f, hh * 0.78f + bh * 0.4f), bw / 3, bw / 3, paint)
                    }
                    avg /= 32f
                    paint.color = base
                    paint.alpha = 60
                    c.drawCircle(w / 2, hh * 0.35f, w * (0.18f + 0.22f * avg), paint)
                    paint.alpha = 255
                }
            } catch (e: Throwable) {
            } finally {
                try { if (c != null) holder.unlockCanvasAndPost(c) } catch (e: Throwable) {}
            }
        }
    }
}
