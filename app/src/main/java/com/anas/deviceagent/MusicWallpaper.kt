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

    private class Ring(val born: Long, val strength: Float, val ox: Float)
    private class Dust(var x: Float, var y: Float, val s: Float, val ph: Float)

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
        private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { maskFilter = android.graphics.BlurMaskFilter(14f, android.graphics.BlurMaskFilter.Blur.NORMAL) }
        private var t = 0f
        private var baseBmp: Bitmap? = null
        private var baseW = 0
        private var baseH = 0
        // beat / band envelopes
        private var bass = 0f; private var mid = 0f; private var high = 0f
        private var bassAvg = 0f
        private var flash = 0f
        private var shake = 0f
        private var lastRing = 0L
        private val rings = ArrayList<Ring>()
        private val dust = ArrayList<Dust>()
        private val rnd = java.util.Random(7)
        // user settings
        private var S = WStyle()
        private var ax = 0.5f; private var ay = 0.40f; private var inten = 1f
        private val tick = Runnable { step() }

        private fun loadSettings() {
            S = WStyle.load(getSharedPreferences("prefs", Context.MODE_PRIVATE))
            ax = S.ax; ay = S.ay; inten = S.intensity
            while (dust.size < S.particleCount) dust.add(Dust(rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f, 0.3f + rnd.nextFloat(), rnd.nextFloat() * 6.28f))
            while (dust.size > S.particleCount) dust.removeAt(dust.size - 1)
        }

        private fun loadBase(w: Int, h: Int) {
            if (baseBmp != null && baseW == w && baseH == h) return
            try {
                val f = java.io.File(filesDir, "wp_base.jpg")
                if (!f.exists()) { baseBmp = null; return }
                val src = android.graphics.BitmapFactory.decodeFile(f.absolutePath) ?: return
                val sc = Math.max(w.toFloat() / src.width, h.toFloat() / src.height)
                val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val cv = Canvas(out)
                val bw = src.width * sc; val bh = src.height * sc
                cv.drawBitmap(src, null, RectF((w - bw) / 2, (h - bh) / 2, (w + bw) / 2, (h + bh) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
                baseBmp = out; baseW = w; baseH = h
            } catch (e: Throwable) { baseBmp = null }
        }

        override fun onVisibilityChanged(v: Boolean) {
            visible = v
            h.removeCallbacks(tick)
            if (v) { baseBmp = null; loadSettings(); h.post(tick) } else stopViz()
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
                            bands[i] = m
                            total += m
                        }
                        if (total > 0.05f) lastData = System.currentTimeMillis()
                    }
                }, Visualizer.getMaxCaptureRate(), false, true)
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
                bass = 0f; mid = 0f; high = 0f; rings.clear()
                draw(false, false, now)
                h.postDelayed(tick, 1000)
                return
            }
            startViz()
            if (now - lastMeta > 3000) { lastMeta = now; MusicState.refresh(this@MusicWallpaper) }
            val vizLive = viz != null && now - lastData < 3000
            if (viz != null && !vizLive && now - vizStart > 4000) { stopViz(); vizStart = now; vizFailed = false }
            MusicState.mode = if (vizLive) "visualizer" else if (MusicState.art != null) "cover" else "simple"
            draw(true, vizLive, now)
            h.postDelayed(tick, 33)
        }

        private fun avg(a: FloatArray, from: Int, to: Int): Float { var s = 0f; for (i in from until to) s += a[i]; return s / (to - from) }

        private fun updateBands(real: Boolean, now: Long) {
            val tb: Float; val tm: Float; val th: Float
            if (real) {
                tb = Math.min(1f, avg(bands, 0, 4) * 1.6f * inten)
                tm = Math.min(1f, avg(bands, 4, 16) * 2.2f * inten)
                th = Math.min(1f, avg(bands, 16, 32) * 3f * inten)
            } else {
                // simulated groove (~120 bpm) when real audio is not available
                val ph = (now % 500L) / 500f
                val kick = Math.pow(Math.max(0.0, 1.0 - ph * 2.2), 3.0).toFloat()
                tb = kick * 0.9f * inten
                tm = (0.35f + 0.25f * Math.sin(t * 3.0).toFloat()) * inten
                th = (0.3f + 0.3f * Math.sin(t * 9.0).toFloat()) * inten
            }
            bass = if (tb > bass) bass + (tb - bass) * 0.7f else bass * 0.86f
            mid = mid + (tm - mid) * 0.25f
            high = if (th > high) high + (th - high) * 0.6f else high * 0.85f
            bassAvg = bassAvg * 0.96f + bass * 0.04f
            val strong = bass > 0.4f && bass > bassAvg * 1.45f
            if (strong && now - lastRing > 260) {
                lastRing = now
                if (S.waves) {
                    if (S.waveOrigin == "speakers") { rings.add(Ring(now, bass.coerceIn(0.3f, 1f), -0.095f)); rings.add(Ring(now, bass.coerceIn(0.3f, 1f), 0.095f)) }
                    else rings.add(Ring(now, bass.coerceIn(0.3f, 1f), 0f))
                }
                while (rings.size > 8) rings.removeAt(0)
                if (S.flicker) flash = 1f
                if (S.shake && bass > 0.7f) shake = 1f
            }
            flash *= 0.84f
            shake *= 0.80f
        }

        private fun draw(playing: Boolean, real: Boolean, now: Long) {
            val holder = surfaceHolder
            var c: Canvas? = null
            try {
                c = holder.lockCanvas() ?: return
                val w = c.width.toFloat(); val hh = c.height.toFloat()
                t += 0.033f
                loadBase(c.width, c.height)
                val bimg = baseBmp
                if (playing) updateBands(real, now)
                val art = MusicState.artColor
                if (bimg == null) { drawClassic(c, w, hh, playing, real, art); return }

                val cx = ax * w; val cy = ay * hh
                val ir = inten.coerceIn(0.3f, 1.6f)
                // global micro shake on heavy hits only
                c.save()
                if (shake > 0.05f) c.translate((rnd.nextFloat() - 0.5f) * 10f * S.shakeStrength * shake * ir, (rnd.nextFloat() - 0.5f) * 10f * S.shakeStrength * shake * ir)
                c.drawBitmap(bimg, 0f, 0f, null)
                if (playing) {
                    val rad = w * 0.2f
                    // 1) speaker vibration: only a soft round patch around the radio
                    val s = 1f + (if (S.vibration) 0.014f * bass * ir else 0f)
                    c.save()
                    val path = android.graphics.Path(); path.addCircle(cx, cy, rad, android.graphics.Path.Direction.CW)
                    c.clipPath(path)
                    if (S.vibration) c.translate((rnd.nextFloat() - 0.5f) * 2.5f * bass * ir, (rnd.nextFloat() - 0.5f) * 2.5f * bass * ir)
                    c.scale(s, s, cx, cy)
                    c.drawBitmap(bimg, 0f, 0f, null)
                    c.restore()
                    // 6) heavy-hit distortion: faint offset ghost around the radio
                    if (shake > 0.1f) {
                        c.save(); c.clipPath(path)
                        paint.alpha = (60 * shake).toInt()
                        c.drawBitmap(bimg, 6f * shake, 0f, paint)
                        paint.alpha = 255
                        c.restore()
                    }
                    // 2) sound waves
                    val it = rings.iterator()
                    while (it.hasNext()) {
                        val r = it.next()
                        val age = (now - r.born) / (1300f / S.waveSpeed)
                        if (age >= 1f) { it.remove(); continue }
                        val e = 1f - (1f - age) * (1f - age)
                        val radius = (w * 0.05f + e * w * (0.2f + 0.14f * r.strength) * ir) * S.waveSize
                        val rx = cx + r.ox * w
                        val wc = WStyle.colorOf(S.waveColor, art)
                        val a = ((1f - age) * 150f * r.strength).toInt().coerceIn(0, 255)
                        glow.style = Paint.Style.STROKE; glow.strokeWidth = 10f
                        glow.color = Color.argb(a / 2, Color.red(wc), Color.green(wc), Color.blue(wc))
                        c.drawCircle(rx, cy, radius, glow)
                        paint.style = Paint.Style.STROKE; paint.strokeWidth = 2.5f
                        paint.color = Color.argb(a, 235, 245, 255)
                        c.drawCircle(rx, cy, radius, paint)
                        paint.style = Paint.Style.FILL
                    }
                    // 3) small lights, driven by highs
                    val ln = if (S.lights) S.lightCount else 0
                    val lc = WStyle.colorOf(S.lightColor, art)
                    for (i in 0 until ln) {
                        val lx = cx + (i - (ln - 1) / 2f) * w * (0.28f / Math.max(ln, 4))
                        val ly = cy + hh * 0.055f
                        val pulse = (high * (0.6f + 0.4f * Math.sin(t * 6.0 + i * 1.7).toFloat())).coerceIn(0f, 1f)
                        glow.style = Paint.Style.FILL
                        glow.color = Color.argb((40 + 160 * pulse).toInt().coerceIn(0, 220), Color.red(lc), Color.green(lc), Color.blue(lc))
                        c.drawCircle(lx, ly, 5f + 7f * pulse, glow)
                        paint.color = Color.argb((60 + 190 * pulse).toInt().coerceIn(0, 255), 255, 255, 255)
                        c.drawCircle(lx, ly, 2f + 2f * pulse, paint)
                    }
                    // 4) dust, drifting faster with mids
                    val pc = WStyle.colorOf(S.particleColor, art)
                    for (d in dust) {
                        d.y -= (0.0006f + 0.0022f * mid) * d.s
                        d.x += Math.sin((t * d.s + d.ph).toDouble()).toFloat() * 0.0006f * (1f + mid * 3f)
                        if (d.y < -0.5f) { d.y = 0.5f; d.x = rnd.nextFloat() - 0.5f }
                        val px = cx + d.x * w * 0.9f
                        val py = cy + 0.15f * hh + d.y * hh * 0.6f
                        paint.color = Color.argb((25 + 70 * mid * d.s).toInt().coerceIn(0, 120), Color.red(pc), Color.green(pc), Color.blue(pc))
                        c.drawCircle(px, py, 1.2f + d.s * 1.6f, paint)
                    }
                    // 5) lighting flicker on strong beats
                    if (flash > 0.05f) {
                        paint.color = Color.argb((76 * S.flickerStrength * flash * ir).toInt().coerceIn(0, 90), Color.red(art), Color.green(art), Color.blue(art))
                        c.drawRect(0f, 0f, w, hh * 0.55f, paint)
                    }
                }
                c.restore()
            } catch (e: Throwable) {
            } finally {
                try { if (c != null) holder.unlockCanvasAndPost(c) } catch (e: Throwable) {}
            }
        }

        private fun drawClassic(c: Canvas, w: Float, hh: Float, playing: Boolean, real: Boolean, base: Int) {
            val top = if (playing) Color.argb(255, Color.red(base) / 4, Color.green(base) / 4, Color.blue(base) / 4) else Color.rgb(8, 8, 14)
            paint.shader = LinearGradient(0f, 0f, 0f, hh, top, Color.BLACK, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w, hh, paint)
            paint.shader = null
            if (!playing) return
            val bw = w / 32f
            for (i in 0 until 32) {
                val v = if (real) bands[i].coerceIn(0.05f, 1f)
                else (0.2f + 0.6f * bass * (1f - i / 40f) + 0.15f * Math.sin((t * 4f + i * 0.45f).toDouble()).toFloat()).coerceIn(0.05f, 1f)
                val bh = v * hh * 0.3f
                paint.color = base; paint.alpha = 200
                c.drawRoundRect(RectF(i * bw + bw * 0.15f, hh * 0.78f - bh, (i + 1) * bw - bw * 0.15f, hh * 0.78f + bh * 0.4f), bw / 3, bw / 3, paint)
            }
            paint.alpha = 255
        }
    }
}
