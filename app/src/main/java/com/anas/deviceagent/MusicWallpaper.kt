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
    @Volatile var songTitle = ""
    @Volatile var songArtist = ""
    @Volatile var listenerOk = true
    @Volatile var durMs = 0L
    @Volatile var posMs = 0L
    @Volatile var posAt = 0L          // elapsedRealtime when posMs was read
    @Volatile var speed = 1f
    @Volatile var isPlaying = false
    @Volatile var ctrl: MediaController? = null

    fun position(): Long {
        val p = if (isPlaying) posMs + ((android.os.SystemClock.elapsedRealtime() - posAt) * speed).toLong() else posMs
        return if (durMs > 0) p.coerceIn(0L, durMs) else Math.max(0L, p)
    }

    /** 0 = previous, 1 = play/pause, 2 = next */
    fun control(which: Int) {
        val c = ctrl ?: return
        try {
            val t = c.transportControls
            when (which) {
                0 -> t.skipToPrevious()
                2 -> t.skipToNext()
                else -> if (isPlaying) t.pause() else t.play()
            }
            if (which == 1) { isPlaying = !isPlaying; posAt = android.os.SystemClock.elapsedRealtime() }
        } catch (e: Throwable) {}
    }
    @Volatile var art: Bitmap? = null
    @Volatile var artColor = Color.rgb(110, 70, 220)

    fun refresh(ctx: Context) {
        try {
            val msm = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val list: List<MediaController> = msm.getActiveSessions(ComponentName(ctx, MediaListener::class.java))
            listenerOk = true
            val c = list.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING } ?: list.firstOrNull() ?: return
            ctrl = c
            c.playbackState?.let { ps ->
                posMs = ps.position; posAt = ps.lastPositionUpdateTime.takeIf { it > 0 } ?: android.os.SystemClock.elapsedRealtime()
                speed = if (ps.playbackSpeed == 0f) 1f else ps.playbackSpeed
                isPlaying = ps.state == PlaybackState.STATE_PLAYING
            }
            val md = c.metadata ?: return
            durMs = md.getLong(MediaMetadata.METADATA_KEY_DURATION)
            songTitle = md.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
            songArtist = md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""
            title = songTitle + " " + songArtist
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
    private class Dust(var x: Float, var y: Float, val s: Float, val ph: Float) { var vx = 0f; var vy = 0f }

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
        private val codeScene = CodeScene()
        private var scene = "image"
        private var beatFlag = 0f
        private var punch = 0f; private var punchV = 0f
        // moon scene state
        private val stars = FloatArray(90 * 4)
        private var moonShader: android.graphics.RadialGradient? = null
        private var glowShader: android.graphics.RadialGradient? = null
        private var shaderW = 0f
        private var bgShader: LinearGradient? = null
        private var moonBump = 0f
        private var swayAmp = 0.4f
        private fun sn(x: Float) = Math.sin(x.toDouble()).toFloat()
        private fun ex(x: Float) = Math.exp(x.toDouble()).toFloat()
        private var fallState = 0       // 0 sit, 1 wobble, 2 fall, 3 lying, 4 rise
        private var fallT0 = 0L
        private var lastFall = 0L
        private var ax = 0.5f; private var ay = 0.40f; private var inten = 1f
        private val tick = Runnable { step() }

        private fun loadSettings() {
            S = WStyle.load(getSharedPreferences("prefs", Context.MODE_PRIVATE))
            scene = getSharedPreferences("prefs", Context.MODE_PRIVATE).getString("wp_scene", "image") ?: "image"
            blackIdle = getSharedPreferences("prefs", Context.MODE_PRIVATE).getBoolean("wp_black_idle", true)
            codeScene.showSkip = getSharedPreferences("prefs", Context.MODE_PRIVATE).getBoolean("wp_skip", true)
            codeScene.flyMode = getSharedPreferences("prefs", Context.MODE_PRIVATE).getBoolean("wp_fly", true)
            if (stars[0] == 0f) { val r = java.util.Random(11); for (i in 0 until 90) { stars[i * 4] = r.nextFloat(); stars[i * 4 + 1] = r.nextFloat() * 0.75f; stars[i * 4 + 2] = r.nextFloat() * 6.28f; stars[i * 4 + 3] = 0.4f + r.nextFloat() } }
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

        private var lastTap = 0L
        private var blackIdle = true
        override fun onCreate(sh: SurfaceHolder) {
            super.onCreate(sh)
            setTouchEventsEnabled(true)
        }

        private fun tap(x: Float, y: Float) {
            if (scene != "code") return
            val now = android.os.SystemClock.uptimeMillis()
            if (now - lastTap < 350) return
            val b = codeScene.hit(x, y)
            if (b < 0) return
            lastTap = now
            codeScene.flash(b, System.currentTimeMillis())
            MusicState.control(b)
            h.removeCallbacks(tick); h.post(tick)
        }

        override fun onTouchEvent(e: android.view.MotionEvent) {
            if (e.action == android.view.MotionEvent.ACTION_UP) tap(e.x, e.y)
        }

        override fun onCommand(action: String?, x: Int, y: Int, z: Int, extras: android.os.Bundle?, resultRequested: Boolean): android.os.Bundle? {
            if (action == "android.wallpaper.tap") tap(x.toFloat(), y.toFloat())
            return null
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
                        val rawb = FloatArray(32)
                        for (i in 0 until 32) {
                            val from = 1 + i * (n - 1) / 32 / 2
                            val to = Math.max(from + 1, 1 + (i + 1) * (n - 1) / 32 / 2)
                            var s = 0f
                            for (k in from until Math.min(to, n)) {
                                val re = fft[2 * k].toFloat(); val im = fft[2 * k + 1].toFloat()
                                s += Math.hypot(re.toDouble(), im.toDouble()).toFloat()
                            }
                            rawb[i] = s / (to - from) / 60f
                            total += rawb[i]
                        }
                        VizNorm.apply(this@MusicWallpaper, rawb)
                        for (i in 0 until 32) bands[i] = rawb[i]
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

        private var wasPlaying = false; private var idleSince = 0L; private var playSince = 0L

        private fun step() {
            if (!visible) return
            val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val playing = am.isMusicActive
            val now = System.currentTimeMillis()
            if (playing && !wasPlaying) { playSince = now; idleSince = 0L }
            if (!playing && wasPlaying) idleSince = now
            wasPlaying = playing
            if (!playing) {
                stopViz()
                MusicState.mode = "idle"
                bass = 0f; mid = 0f; high = 0f; rings.clear()
                if (scene == "code" && now - lastMeta > 3000) { lastMeta = now; MusicState.refresh(this@MusicWallpaper) }
                draw(false, false, now)
                h.postDelayed(tick, if (blackIdle && idleSince > 0L && now - idleSince < 1700L) 33L else if (scene == "code" && !blackIdle && (codeScene.busy || codeScene.flashing(now))) 50L else 1000L)
                return
            }
            startViz()
            if (now - lastMeta > 3000) { lastMeta = now; MusicState.refresh(this@MusicWallpaper) }
            val vizLive = viz != null && now - lastData < 3000
            if (viz != null && !vizLive && now - vizStart > 4000) { stopViz(); vizStart = now; vizFailed = false }
            MusicState.mode = if (vizLive) "visualizer" else if (MusicState.art != null) "cover" else "simple"
            draw(true, vizLive, now)
            h.postDelayed(tick, if (WpExtras.lowBattery(this@MusicWallpaper)) 66L else 33L)
        }

        private fun avg(a: FloatArray, from: Int, to: Int): Float { var s = 0f; for (i in from until to) s += a[i]; return s / (to - from) }

        private fun updateBands(real: Boolean, now: Long) {
            val tb: Float; val tm: Float; val th: Float
            if (real) {
                tb = Math.min(1f, avg(bands, 0, 4) * 2.97f * inten)
                tm = Math.min(1f, avg(bands, 4, 16) * 4.05f * inten)
                th = Math.min(1f, avg(bands, 16, 32) * 5.13f * inten)
            } else {
                // simulated groove (~120 bpm) when real audio is not available
                val ph = (now % 500L) / 500f
                val kick = Math.pow(Math.max(0.0, 1.0 - ph * 2.2), 3.0).toFloat()
                tb = kick * 0.9f * inten
                tm = (0.35f + 0.25f * Math.sin(t * 3.0).toFloat()) * inten
                th = (0.3f + 0.3f * Math.sin(t * 9.0).toFloat()) * inten
            }
            bass = if (tb > bass) bass + (tb - bass) * 0.92f else bass * 0.82f
            mid = mid + (tm - mid) * 0.25f
            high = if (th > high) high + (th - high) * 0.6f else high * 0.85f
            bassAvg = bassAvg * 0.96f + bass * 0.04f
            val strong = bass > 0.4f && bass > bassAvg * 1.45f
            if (strong) { beatFlag = bass; punchV += 0.8f * bass; for (d in dust) { d.vx += d.x * 0.05f * bass; d.vy += (d.y * 0.04f - 0.004f) * bass } }
            punchV += -punch * 0.28f; punchV *= 0.80f; punch += punchV
            if (strong && now - lastRing > 170) {
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
            var ov = 0f
            try {
                c = holder.lockCanvas() ?: return
                val w = c.width.toFloat(); val hh = c.height.toFloat()
                if (!playing && blackIdle && (idleSince == 0L || now - idleSince >= 1600L)) { c.drawColor(Color.BLACK); return }
                ov = if (!playing && blackIdle) (now - idleSince) / 1600f else if (playing && playSince > 0L && now - playSince < 1000L) 1f - (now - playSince) / 1000f else 0f
                t += 0.033f
                loadBase(c.width, c.height)
                val bimg = baseBmp
                if (playing) updateBands(real, now)
                val art = MusicState.artColor
                if (scene == "code") { codeScene.draw(c, w, hh, playing, now, bass, punch, high, if (playing) MusicState.songTitle else "", if (playing) MusicState.songArtist else "", MusicState.position(), MusicState.durMs); return }
                if (scene == "moon") { drawMoon(c, w, hh, playing, now); return }
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
                    val s = 1f + (if (S.vibration) (0.010f * bass + 0.02f * punch) * ir else 0f)
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
                        val e = 1f - (1f - age) * (1f - age) * (1f - age)
                        val radius = (w * 0.05f + e * w * (0.2f + 0.14f * r.strength) * ir) * S.waveSize
                        val rx = cx + r.ox * w
                        val wc = WStyle.colorOf(S.waveColor, art)
                        val a = ((1f - age) * 150f * r.strength).toInt().coerceIn(0, 255)
                        glow.style = Paint.Style.STROKE; glow.strokeWidth = 14f * (1f - age) + 3f
                        glow.color = Color.argb(a / 2, Color.red(wc), Color.green(wc), Color.blue(wc))
                        c.drawCircle(rx, cy, radius, glow)
                        paint.style = Paint.Style.STROKE; paint.strokeWidth = 4f * (1f - age) + 1.2f
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
                        d.vx *= 0.92f; d.vy *= 0.92f
                        d.x += d.vx + (Math.cos((t * 1.2f * d.s + d.ph).toDouble()).toFloat()) * 0.0016f * (1f + mid * 5f) * d.s
                        d.y += d.vy - (0.0008f + 0.0042f * mid) * d.s + (Math.sin((t * 0.9f * d.s + d.ph * 2f).toDouble()).toFloat()) * 0.0010f * (1f + mid * 4f)
                        if (d.y < -0.5f || d.x < -0.7f || d.x > 0.7f || d.y > 0.7f) { d.y = 0.5f; d.x = rnd.nextFloat() - 0.5f; d.vx = 0f; d.vy = 0f }
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
                try { if (c != null && ov > 0.01f) c.drawColor(Color.argb((ov.coerceIn(0f, 1f) * 255).toInt(), 0, 0, 0)) } catch (e: Throwable) {}
                try { if (c != null && NotifState.show(this@MusicWallpaper)) NotifRender.draw(c, c.width.toFloat(), c.height.toFloat(), scene != "code", playing) } catch (e: Throwable) {}
                try { if (c != null) holder.unlockCanvasAndPost(c) } catch (e: Throwable) {}
            }
        }

        private fun ease(p: Float) = p * p * (3f - 2f * p)
        private fun backOut(p: Float): Float { val s = 1.70158f; val q = p - 1f; return 1f + q * q * ((s + 1f) * q + s) }

        private fun drawMoon(c: Canvas, w: Float, hh: Float, playing: Boolean, now: Long) {
            if (shaderW != w) {
                shaderW = w
                val mr = w * 0.34f; val mcx = w / 2; val mcy = hh * 0.66f
                bgShader = LinearGradient(0f, 0f, 0f, hh, Color.rgb(4, 5, 14), Color.rgb(12, 16, 40), Shader.TileMode.CLAMP)
                glowShader = android.graphics.RadialGradient(mcx, mcy, mr * 2.1f, intArrayOf(Color.argb(110, 200, 210, 255), Color.argb(40, 120, 140, 230), Color.argb(0, 20, 30, 80)), floatArrayOf(0.4f, 0.65f, 1f), Shader.TileMode.CLAMP)
                moonShader = android.graphics.RadialGradient(mcx - mr * 0.25f, mcy - mr * 0.25f, mr * 1.3f, Color.rgb(252, 250, 235), Color.rgb(196, 200, 214), Shader.TileMode.CLAMP)
            }
            paint.shader = bgShader; c.drawRect(0f, 0f, w, hh, paint); paint.shader = null
            // stars
            for (i in 0 until 90) {
                val tw = 0.55f + 0.3f * Math.sin((t * 1.3f * stars[i * 4 + 3] + stars[i * 4 + 2]).toDouble()).toFloat() + (if (playing) 0.25f * high else 0f)
                paint.color = Color.argb((255 * tw.coerceIn(0.1f, 1f)).toInt(), 255, 255, 240)
                c.drawCircle(stars[i * 4] * w, stars[i * 4 + 1] * hh, 1.2f + stars[i * 4 + 3] * 1.3f * (1f + 0.4f * (if (playing) high else 0f) + 0.5f * Math.max(0f, punch)), paint)
            }
            val mr = w * 0.34f * (1f + 0.02f * bass + 0.03f * punch + moonBump); val mcx = w / 2; val mcy = hh * 0.66f
            paint.shader = glowShader; c.drawCircle(mcx, mcy, mr * 2.1f, paint)
            paint.shader = moonShader; c.drawCircle(mcx, mcy, mr, paint); paint.shader = null
            paint.color = Color.argb(28, 90, 95, 120)
            c.drawCircle(mcx - mr * 0.35f, mcy - mr * 0.15f, mr * 0.16f, paint)
            c.drawCircle(mcx + mr * 0.30f, mcy + mr * 0.25f, mr * 0.22f, paint)
            c.drawCircle(mcx + mr * 0.1f, mcy - mr * 0.5f, mr * 0.09f, paint)
            c.drawCircle(mcx - mr * 0.2f, mcy + mr * 0.45f, mr * 0.12f, paint)

            // trigger
            if (playing && beatFlag > 0f && fallState == 0 && now - lastFall > 7000 && bass > 0.5f) { fallState = 1; fallT0 = now }
            beatFlag = 0f
            swayAmp += ((if (playing) 0.5f + bass else 0.3f) - swayAmp) * 0.04f
            val sAng = 1.3f * swayAmp * sn(t * 1.5f)
            val sKick = 7f * swayAmp * sn(t * 2.0f)
            val sArm = 12f + 4f * swayAmp * sn(t * 1.7f)
            val sHop = 0.5f * punch.coerceIn(0f, 1.5f)
            var ang = sAng; var kick = sKick; var armA = sArm; var thighA = 0f; var hop = sHop; var sq = 0f
            var dizzy = false
            if (fallState == 1) {
                val el = (now - fallT0).toFloat()
                val total = 5400f
                var pa = 0f; var pk = 0f; var pr = 0f; var pt = 0f; var ph = 0f; var ps = 0f
                when {
                    el < 600f -> {            // 1) anticipation: slow lean back, small wobble, arms start to wave
                        val p = el / 600f
                        pa = -9f * ease(p) + 2.5f * p * sn(el / 70f)
                        pk = 10f * p * sn(el / 60f)
                        pr = 12f + 35f * p * sn(el / 75f)
                    }
                    el < 1900f -> {           // 2+3) tip over with gravity easing, everything flailing
                        val p = (el - 600f) / 1300f
                        val g = Math.pow(p.toDouble(), 2.2).toFloat()
                        pa = -9f - 91f * g
                        pk = 28f * sn(el / 85f) * (0.4f + 0.6f * p)
                        pt = 18f * sn(el / 110f + 1f) * p
                        pr = 12f + 70f * sn(el / 65f) * (0.5f + 0.5f * p)
                    }
                    el < 2500f -> {           // 4) impact: bounce + squash
                        val q = (el - 1900f) / 600f
                        pa = -100f + 9f * ex(-q * 4.5f) * Math.abs(sn(q * 10f))
                        ps = 0.09f * ex(-q * 6f)
                        pk = 14f * sn(q * 20f) * ex(-q * 3f)
                        pr = 12f + 50f * ex(-q * 3f) * sn(q * 18f)
                        pt = 8f * sn(q * 16f) * ex(-q * 3f)
                        dizzy = true
                        if (el < 1960f) moonBump = 0.03f
                    }
                    el < 3600f -> {           // 5) stays down
                        pa = -100f + 1.2f * sn(el / 300f)
                        pk = 5f * sn(el / 260f)
                        pr = 25f + 6f * sn(el / 230f)
                        dizzy = el < 3200f
                    }
                    else -> {                 // 6) slow get-up with a push-off, then sits
                        val p = (el - 3600f) / 1800f
                        val e2 = ease(p)
                        pa = -100f * (1f - e2) + 2.5f * sn(e2 * 3.14159f)
                        pr = 12f + 55f * sn(Math.min(1f, p / 0.3f) * 3.14159f) * (1f - p)
                        pk = 10f * sn(p * 12f) * (1f - p)
                        ph = if (p > 0.75f) 2.5f * sn((p - 0.75f) / 0.25f * 3.14159f) else 0f
                    }
                }
                val wt = Math.min(ease((el / 250f).coerceIn(0f, 1f)), ease(((total - el) / 350f).coerceIn(0f, 1f)))
                ang = sAng + (pa - sAng) * wt; kick = sKick + (pk - sKick) * wt; armA = sArm + (pr - sArm) * wt
                thighA = pt * wt; hop = sHop + (ph - sHop) * wt; sq = ps * wt
                if (el >= total) { fallState = 0; lastFall = now }
            }
            moonBump *= 0.88f
            // person + chair, local unit u, origin = back foot of chair on the moon top
            val u = w * 0.0105f
            val gx = mcx - 3f * u; val gy = mcy - mr + 0.4f * u
            c.save()
            c.translate(gx, gy - hop * u)
            c.scale(1f + 0.6f * sq, 1f - sq)
            c.rotate(ang)
            val ink = Color.rgb(6, 6, 12)
            paint.color = ink
            paint.style = Paint.Style.STROKE; paint.strokeCap = Paint.Cap.ROUND
            paint.strokeWidth = 1.1f * u
            c.drawLine(0f, 0f, 0f, -8f * u, paint)
            c.drawLine(9f * u, 0f, 9f * u, -8f * u, paint)
            c.drawLine(0f, -8f * u, 0f, -18f * u, paint)
            paint.strokeWidth = 1.4f * u
            c.drawLine(-0.3f * u, -8f * u, 9.8f * u, -8f * u, paint)
            // far arm (behind torso)
            paint.strokeWidth = 1.4f * u
            c.save(); c.translate(3f * u, -15.5f * u); c.rotate(-armA * 0.8f + 40f)
            c.drawLine(0f, 0f, 0f, 6f * u, paint); c.restore()
            paint.strokeWidth = 4.4f * u
            c.drawLine(3f * u, -10.5f * u, 3f * u, -16.5f * u, paint)
            // thigh + shin
            c.save(); c.translate(3f * u, -10f * u); c.rotate(thighA)
            paint.strokeWidth = 2.2f * u
            c.drawLine(0f, 0f, 7.5f * u, 0f, paint)
            c.translate(7.5f * u, 0f); c.rotate(kick)
            paint.strokeWidth = 1.9f * u
            c.drawLine(0f, 0f, 0f, 7f * u, paint)
            c.restore()
            // near arm
            paint.strokeWidth = 1.6f * u
            c.save(); c.translate(3f * u, -15.5f * u); c.rotate(-armA + 50f)
            c.drawLine(0f, 0f, 0f, 6.2f * u, paint); c.restore()
            paint.style = Paint.Style.FILL
            c.drawCircle(3.6f * u, -20.4f * u, 2.7f * u, paint)
            if (dizzy) {
                paint.color = Color.argb(230, 255, 230, 120)
                for (k in 0 until 3) {
                    val a = t * 6f + k * 2.09f
                    c.drawCircle(3.6f * u + Math.cos(a.toDouble()).toFloat() * 4.8f * u, -24.4f * u + Math.sin(a.toDouble()).toFloat() * 1.4f * u, 0.7f * u, paint)
                }
            }
            c.restore()
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
