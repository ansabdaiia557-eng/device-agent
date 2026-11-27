package com.anas.deviceagent

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint

/** Black "hacker terminal" scene: typed track title, layered code rain, and a roaming mouse that pushes characters. */
class CodeScene {
    private val rnd = java.util.Random(5)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    private val glyphs = "01{}[]<>/\\=+*#$%&;:()!?_-|~abcdefxyz".toCharArray()

    private class Col(val layer: Int, val x: Float, var head: Float, var speed: Float, val len: Int) {
        val ch = CharArray(len); val ox = FloatArray(len); val oy = FloatArray(len); val vx = FloatArray(len); val vy = FloatArray(len)
    }
    private var cols = ArrayList<Col>()
    private var sizeW = 0f
    private val layerSize = floatArrayOf(0.021f, 0.030f, 0.043f)
    private val layerSpeed = floatArrayOf(90f, 190f, 330f)
    private val layerAlpha = floatArrayOf(0.35f, 0.6f, 0.9f)
    private var lastNow = 0L

    // flying-letters title mode
    @Volatile var flyMode = true
    @Volatile var showSkip = true
    private var flyPhase = 0                  // 0 settled, 1 old letters leave, 2 new letters arrive
    private var flyT0 = 0L
    private val sox = FloatArray(400); private val soy = FloatArray(400); private val sang = FloatArray(400)
    private val sdel = FloatArray(400); private val sdur = FloatArray(400)
    private var rtlFly = Pair(0f, 0f); private var rtlAng = 0f
    // typing
    private var shown = StringBuilder()
    private var target = ""
    private var nextAt = 0L
    private var typoPending = false
    val busy: Boolean get() = shown.toString() != target || flyPhase != 0
    private val tox = FloatArray(400); private val toy = FloatArray(400); private val tvx = FloatArray(400); private val tvy = FloatArray(400)

    // mouse
    private var mx = 0.5f; private var my = 0.6f          // fractions
    private var phase = 2
    private var t0 = 0L; private var dur = 1000f
    private var sx = 0f; private var sy = 0f; private var ex = 0f; private var ey = 0f; private var cpx = 0f; private var cpy = 0f
    private var finalX = 0f; private var finalY = 0f
    private var pauseUntil = 0L
    private var cvx = 0f; private var cvy = 0f
    private var ripple = -1L
    private var rx = 0f; private var ry = 0f
    private var blinkT = 0

    // player UI
    private val btn = arrayOf(RectF(), RectF(), RectF())
    private var flashIdx = -1; private var flashAt = 0L
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.MONOSPACE }
    private var rtl = false
    private var bx0 = 0f; private var by0 = 0f; private var bvx = 0f; private var bvy = 0f
    fun hit(x: Float, y: Float): Int {
        for (i in 0 until 3) { val r = btn[i]; if (x >= r.left - 18f && x <= r.right + 18f && y >= r.top - 18f && y <= r.bottom + 18f) return i }
        return -1
    }
    fun flash(i: Int, now: Long) { flashIdx = i; flashAt = now }
    fun flashing(now: Long) = flashIdx >= 0 && now - flashAt < 450
    private fun isRtlChar(ch: Char) = ch.code in 0x0590..0x08FF || ch.code in 0xFB1D..0xFEFF
    private fun mmss(ms: Long): String { val s = (ms / 1000).toInt(); return String.format("%02d:%02d", s / 60, s % 60) }

    private fun build(w: Float, h: Float) {
        sizeW = w
        cols = ArrayList()
        val counts = intArrayOf(16, 11, 6)
        for (l in 0 until 3) for (i in 0 until counts[l]) {
            val len = if (l == 0) 9 else if (l == 1) 12 else 14
            val c = Col(l, (i + rnd.nextFloat() * 0.8f) / counts[l] * w, -rnd.nextFloat() * h, layerSpeed[l] * (w / 1080f) * (0.7f + rnd.nextFloat() * 0.6f), len)
            for (k in 0 until len) c.ch[k] = glyphs[rnd.nextInt(glyphs.size)]
            cols.add(c)
        }
    }

    private fun mj(p: Float) = p * p * p * (10f + p * (-15f + 6f * p))   // minimum-jerk easing
    private fun smooth(p: Float) = p * p * (3f - 2f * p)

    private fun pickTarget(w: Float, h: Float, now: Long) {
        val tx: Float; val ty: Float
        if (rnd.nextFloat() < 0.55f) { tx = (0.12f + rnd.nextFloat() * 0.76f) * w; ty = (0.30f + rnd.nextFloat() * 0.25f) * h }   // near the title
        else { tx = (0.08f + rnd.nextFloat() * 0.84f) * w; ty = (0.08f + rnd.nextFloat() * 0.84f) * h }
        sx = mx * w; sy = my * h
        val dx = tx - sx; val dy = ty - sy
        val dist = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat().coerceAtLeast(1f)
        finalX = tx; finalY = ty
        val over = if (dist > w * 0.2f) (dist * 0.05f).coerceIn(8f, 30f) else 0f
        ex = tx + dx / dist * over; ey = ty + dy / dist * over
        val bend = (rnd.nextFloat() - 0.5f) * 0.35f * dist
        cpx = (sx + ex) / 2 - dy / dist * bend; cpy = (sy + ey) / 2 + dx / dist * bend
        dur = (450f + dist / w * 900f + rnd.nextFloat() * 300f)
        t0 = now; phase = 0
    }

    private fun updateMouse(w: Float, h: Float, now: Long) {
        val ox = mx * w; val oy = my * h
        when (phase) {
            0 -> {
                val pr = ((now - t0) / dur).coerceIn(0f, 1f); val s = mj(pr)
                val a = (1 - s) * (1 - s); val b = 2 * (1 - s) * s; val c = s * s
                mx = (a * sx + b * cpx + c * ex) / w; my = (a * sy + b * cpy + c * ey) / h
                if (pr >= 1f) {
                    if (Math.abs(ex - finalX) + Math.abs(ey - finalY) > 2f) { sx = ex; sy = ey; ex = finalX; ey = finalY; t0 = now; dur = 240f; phase = 1 }
                    else { phase = 2; pauseUntil = now + 250 + rnd.nextInt(650); if (rnd.nextFloat() < 0.35f) { ripple = now; rx = finalX; ry = finalY } }
                }
            }
            1 -> {
                val pr = ((now - t0) / dur).coerceIn(0f, 1f); val s = smooth(pr)
                mx = (sx + (ex - sx) * s) / w; my = (sy + (ey - sy) * s) / h
                if (pr >= 1f) { phase = 2; pauseUntil = now + 250 + rnd.nextInt(650); if (rnd.nextFloat() < 0.35f) { ripple = now; rx = finalX; ry = finalY } }
            }
            else -> {
                if (now < pauseUntil) { if (rnd.nextFloat() < 0.15f) { mx += (rnd.nextFloat() - 0.5f) * 1.4f / w; my += (rnd.nextFloat() - 0.5f) * 1.4f / h } }
                else if (rnd.nextFloat() < 0.5f || now - pauseUntil > 400) pickTarget(w, h, now)
            }
        }
        cvx = mx * w - ox; cvy = my * h - oy
    }

    private val gpBloom = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sarc = FloatArray(400); private val sdepth = FloatArray(400)
    private var flyEnd = 1500f
    private var landedFrac = 0f; private var swp = -1f
    private val pose = FloatArray(4)
    private fun ease(q: Float): Float { val e = (1.0 - Math.pow(2.0, -8.0 * q)) / (1.0 - Math.pow(2.0, -8.0)); return (e + 0.05 * Math.sin(Math.PI * q) * (1 - q)).toFloat() }
    private fun chPose(i: Int, q: Float) {
        val f = 1f - ease(q.coerceIn(0f, 1f))
        pose[0] = sox[i] * f - soy[i] * sarc[i] * f; pose[1] = soy[i] * f + sox[i] * sarc[i] * f
        pose[2] = sang[i] * f; pose[3] = 1f + 1.6f * sdepth[i] * f
    }

    private fun scatter(n: Int, w: Float, h: Float) {
        var endMax = 0f
        for (i in 0 until Math.min(n, 400)) {
            val a = rnd.nextFloat() * 6.2832f
            val r = Math.max(w, h) * (0.7f + rnd.nextFloat() * 0.6f)
            sox[i] = Math.cos(a.toDouble()).toFloat() * r; soy[i] = Math.sin(a.toDouble()).toFloat() * r
            sang[i] = (rnd.nextFloat() - 0.5f) * 14f
            val d = Math.abs(i - (n - 1) / 2f) / Math.max(1f, n / 2f)
            sdel[i] = 350f + d * 650f + rnd.nextFloat() * 140f
            sdur[i] = 900f + rnd.nextFloat() * 500f
            sarc[i] = (rnd.nextFloat() - 0.5f) * 0.9f; sdepth[i] = 0.4f + 0.6f * rnd.nextFloat()
            endMax = Math.max(endMax, sdel[i] + sdur[i])
        }
        flyEnd = Math.max(endMax, 1200f)
        val a = rnd.nextFloat() * 6.2832f
        rtlFly = Pair(Math.cos(a.toDouble()).toFloat() * w * 0.9f, Math.sin(a.toDouble()).toFloat() * h * 0.5f); rtlAng = (rnd.nextFloat() - 0.5f) * 0.5f
    }

    private fun updateFly(now: Long, w: Float, h: Float) {
        val s = shown.toString()
        if (flyPhase == 0 && s != target) {
            if (s.isEmpty() || s.startsWith("//")) { shown.setLength(0); shown.append(target); scatter(target.length, w, h); flyPhase = 2; flyT0 = now }
            else { scatter(s.length, w, h); flyPhase = 1; flyT0 = now }
        } else if (flyPhase == 1 && now - flyT0 > 620) {
            shown.setLength(0); shown.append(target); scatter(target.length, w, h); flyPhase = 2; flyT0 = now
        } else if (flyPhase == 2 && now - flyT0 > flyEnd.toLong() + 1000) flyPhase = 0
        val tph = (now - flyT0).toFloat()
        landedFrac = if (flyPhase == 2) ((tph - 350f) / Math.max(1f, flyEnd - 350f)).coerceIn(0f, 1f) else if (flyPhase == 1) 0f else 1f
        swp = if (flyPhase == 2) (tph - flyEnd) / 1000f else -1f
    }
    private fun dimK(): Float = if (!flyMode) 1f else when (flyPhase) { 1 -> 0.35f; 2 -> 0.35f + 0.65f * landedFrac * landedFrac; else -> 1f }

    private fun updateTyping(now: Long) {
        if (now < nextAt) return
        val s = shown.toString()
        if (s == target) return
        var common = 0
        while (common < s.length && common < target.length && s[common] == target[common]) common++
        if (s.length > common) {
            shown.setLength(s.length - 1)
            nextAt = now + (if (typoPending) 0 else 32) + (if (typoPending) 0 else 0)
            typoPending = false
        } else {
            val c = target[s.length]
            if (c.isLetter() && c.code < 0x250 && !rtl && rnd.nextFloat() < 0.035f && s.length > 6) {
                shown.append(('a' + rnd.nextInt(26)))
                typoPending = true
                nextAt = now + 220 + rnd.nextInt(200)           // notices the mistake, then backspaces
                return
            }
            shown.append(c)
            var d = 55 + rnd.nextInt(80)
            if (c == ' ') d += 50; if (c == ',' || c == '-' || c == '"') d += 110
            if (rnd.nextFloat() < 0.05f) d += 250 + rnd.nextInt(300)
            nextAt = now + d
        }
    }

    fun draw(c: Canvas, w: Float, h: Float, playing: Boolean, now: Long, bass: Float, punch: Float, high: Float, title: String, artist: String, posMs: Long, durMs: Long) {
        if (sizeW != w) { build(w, h); mx = 0.5f; my = 0.6f }
        val both = title.trim() + (if (artist.isNotBlank()) " - " + artist.trim() else "")
        rtl = both.any { isRtlChar(it) }
        val wantT = if (title.isBlank() && artist.isBlank()) "// waiting for a track..." else if (rtl) both else "const track = \"" + both + "\";"
        if (wantT != target) { target = wantT; nextAt = Math.min(nextAt, now + 150) }
        if (shown.isEmpty() && nextAt == 0L) nextAt = now + 400
        if (flyMode) updateFly(now, w, h) else updateTyping(now)
        if (playing) updateMouse(w, h, now)
        val curX = mx * w; val curY = my * h
        c.drawColor(Color.BLACK)
        val lvl = (0.6f + 0.5f * bass) * dimK()
        val dt = if (lastNow == 0L) 0.033f else ((now - lastNow) / 1000f).coerceIn(0.01f, 0.1f)
        lastNow = now
        val pushR = w * 0.11f
        val speed = Math.hypot(cvx.toDouble(), cvy.toDouble()).toFloat()

        // ---- code rain
        for (col in cols) {
            val size = layerSize[col.layer] * w
            val lineH = size * 1.25f
            p.textSize = size
            if (playing) {
                col.head += col.speed * dt * (1f + 0.9f * bass)
                if (col.head - col.len * lineH > h) { col.head = -rnd.nextFloat() * h * 0.4f; col.speed = layerSpeed[col.layer] * (w / 1080f) * (0.7f + rnd.nextFloat() * 0.6f) }
                if (rnd.nextFloat() < 0.12f) col.ch[rnd.nextInt(col.len)] = glyphs[rnd.nextInt(glyphs.size)]
            }
            for (i in 0 until col.len) {
                val bx = col.x; val by = col.head - i * lineH
                if (by < -lineH || by > h + lineH) continue
                var x = bx + col.ox[i]; var y = by + col.oy[i]
                val dx = x - curX; val dy = y - curY
                val d2 = dx * dx + dy * dy
                if (playing && d2 < pushR * pushR && d2 > 1f) {
                    val d = Math.sqrt(d2.toDouble()).toFloat(); val f = 1f - d / pushR
                    val pw = f * (0.9f + 0.10f * speed)
                    col.vx[i] += dx / d * pw + cvx * 0.18f * f - dy / d * f * 0.25f
                    col.vy[i] += dy / d * pw + cvy * 0.18f * f + dx / d * f * 0.25f
                }
                col.vx[i] += -col.ox[i] * 0.055f; col.vy[i] += -col.oy[i] * 0.055f
                col.vx[i] *= 0.90f; col.vy[i] *= 0.90f
                col.ox[i] += col.vx[i]; col.oy[i] += col.vy[i]
                x = bx + col.ox[i]; y = by + col.oy[i]
                val a = Math.pow((1f - i / col.len.toFloat()).toDouble(), 1.5).toFloat() * layerAlpha[col.layer] * lvl
                if (i == 0) p.color = Color.argb((255 * a.coerceIn(0f, 1f)).toInt(), 200, 255, 220)
                else p.color = Color.argb((255 * a.coerceIn(0f, 1f)).toInt(), 0, 200, 90)
                c.drawText(col.ch, i, 1, x, y, p)
            }
        }

        // ---- title block
        val ts = w * 0.047f
        p.textSize = ts
        val cw = p.measureText("M")
        val left = w * 0.07f
        val maxC = ((w * 0.86f) / cw).toInt().coerceAtLeast(8)
        val top = h * 0.36f
        val glow = 8f + 14f * Math.max(0f, punch) + 6f * high
        if (flyMode && flyPhase == 2) {
            val b = 0.35f * landedFrac + (if (swp in 0f..1f) 0.7f * Math.sin(Math.PI * swp).toFloat() else 0f)
            if (b > 0.01f) {
                gpBloom.shader = android.graphics.RadialGradient(w / 2, top, w * 0.7f, Color.argb((90 * b).toInt(), 0, 255, 120), Color.argb(0, 0, 255, 120), android.graphics.Shader.TileMode.CLAMP)
                c.drawRect(0f, 0f, w, h, gpBloom); gpBloom.shader = null
            }
        }
        // dim prompt line
        p.setShadowLayer(0f, 0f, 0f, 0)
        p.color = Color.argb(150, 0, 170, 80); p.textSize = ts * 0.62f
        c.drawText("root@device:~# now_playing", left, top - ts * 1.7f, p)
        p.textSize = ts
        val s = shown.toString()
        val n = Math.min(s.length, 399)
        var blockBottom = top
        if (rtl) {
            tp.textSize = ts * 1.05f
            // one soft block for shaped RTL text: pushed as a whole, springs back
            val lw = (w * 0.86f).toInt()
            val lay = StaticLayout.Builder.obtain(s, 0, s.length, tp, lw)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setTextDirection(TextDirectionHeuristics.ANYRTL_LTR)
                .setLineSpacing(0f, 1.25f).build()
            val cx0 = left + lw / 2f + bx0; val cy0 = top + lay.height / 2f + by0
            val ddx = cx0 - curX; val ddy = cy0 - curY; val dd = Math.hypot(ddx.toDouble(), ddy.toDouble()).toFloat()
            if (playing && dd < pushR * 1.8f && dd > 1f) { val f = 1f - dd / (pushR * 1.8f); bvx += ddx / dd * f * 0.5f + cvx * 0.04f * f; bvy += ddy / dd * f * 0.5f + cvy * 0.04f * f }
            bvx += -bx0 * 0.08f; bvy += -by0 * 0.08f; bvx *= 0.86f; bvy *= 0.86f; bx0 += bvx; by0 += bvy
            tp.setShadowLayer(glow, 0f, 0f, Color.argb(220, 0, 255, 110))
            tp.color = Color.rgb(60, 255, 140)
            var fq = 1f; var rx0 = 0f; var ry0 = 0f; var ra = 0f; var rs = 1f; var gx = 0f
            val tphr = (now - flyT0).toFloat()
            if (flyMode && flyPhase == 1) { val q = (tphr / 620f).coerceIn(0f, 1f); val e = q * q; rx0 = rtlFly.first * e * 0.8f; ry0 = rtlFly.second * e * 0.8f; ra = rtlAng * e; rs = 1f + 0.6f * e; fq = 1f - q }
            else if (flyMode && flyPhase == 2) {
                val q = ((tphr - 250f) / 1500f).coerceIn(0f, 1f); val f = 1f - ease(q)
                rx0 = rtlFly.first * f; ry0 = rtlFly.second * f - rtlFly.first * 0.25f * f; ra = rtlAng * f; rs = 1f + 1.4f * f; fq = Math.min(1f, q * 3f); gx = 30f * f
                if (swp in 0f..1f) gx += 28f * Math.sin(Math.PI * swp).toFloat()
                else if (q >= 1f) gx += 28f * (1f - ((tphr - 1750f) / 500f).coerceIn(0f, 1f))
            }
            if (fq > 0.01f) {
                tp.alpha = (255 * fq).toInt()
                tp.setShadowLayer(glow + gx, 0f, 0f, Color.argb((220 * fq).toInt(), 0, 255, 110))
                c.save(); c.translate(left + bx0 + rx0, top - ts * 0.9f + by0 + ry0); c.scale(rs, rs, lw / 2f, lay.height / 2f); c.rotate(ra * 57f, lw / 2f, lay.height / 2f); lay.draw(c); c.restore()
                tp.alpha = 255
            }
            blockBottom = top - ts * 0.9f + lay.height
            blinkT = ((now / 530L) % 2L).toInt()
            if ((!flyMode && busy) || blinkT == 0) {
                val line = lay.getLineForOffset(s.length)
                val ex = lay.getPrimaryHorizontal(s.length)
                val ly = top - ts * 0.9f + lay.getLineTop(line) + by0
                p.setShadowLayer(glow, 0f, 0f, Color.argb(220, 0, 255, 110)); p.color = Color.argb(230, 60, 255, 140)
                val xx = left + ex + bx0
                c.drawRect(xx - cw * 0.5f, ly + ts * 0.05f, xx + cw * 0.5f, ly + ts * 1.2f, p)
            }
            tp.setShadowLayer(0f, 0f, 0f, 0)
        } else {
        for (i in 0 until n) {
            val col = i % maxC; val row = i / maxC
            val bx = left + col * cw; val by = top + row * ts * 1.45f
            val x = bx + tox[i]; val y = by + toy[i]
            val dx = x - curX; val dy = y - curY; val d2 = dx * dx + dy * dy
            if (playing && d2 < pushR * pushR && d2 > 1f) {
                val d = Math.sqrt(d2.toDouble()).toFloat(); val f = 1f - d / pushR
                tvx[i] += dx / d * f * (0.8f + 0.08f * speed) + cvx * 0.15f * f
                tvy[i] += dy / d * f * (0.8f + 0.08f * speed) + cvy * 0.15f * f
            }
            tvx[i] += -tox[i] * 0.06f; tvy[i] += -toy[i] * 0.06f
            tvx[i] *= 0.88f; tvy[i] *= 0.88f
            tox[i] += tvx[i]; toy[i] += tvy[i]
            p.setShadowLayer(glow, 0f, 0f, Color.argb(220, 0, 255, 110))
            p.color = Color.rgb(60, 255, 140)
            var al = 1f; var sc = 1f; var fa = 0f; var fx = 0f; var fy = 0f; var boost = 0f; var f2 = 0f
            val tph = (now - flyT0).toFloat()
            var q2 = 1f
            if (flyMode && flyPhase == 1) {
                val q = (tph / 620f).coerceIn(0f, 1f); val e = q * q
                fx = sox[i] * e * 0.8f; fy = soy[i] * e * 0.8f; fa = sang[i] * e * 1.5f; sc = 1f + 0.8f * e; al = 1f - Math.pow(q.toDouble(), 0.8).toFloat()
            } else if (flyMode && flyPhase == 2) {
                q2 = ((tph - sdel[i]) / sdur[i]).coerceIn(0f, 1f)
                chPose(i, q2); fx = pose[0]; fy = pose[1]; fa = pose[2]; sc = pose[3]; f2 = 1f - q2
                al = Math.min(1f, q2 * 3f)
                if (q2 >= 1f) boost = (1f - ((tph - sdel[i] - sdur[i]) / 500f).coerceIn(0f, 1f))
                if (swp in 0f..1f) { val band = cw * 4f; val ctr = left + swp * (Math.min(n, maxC) * cw + 2 * band) - band; boost = Math.max(boost, Math.max(0f, 1f - Math.abs(bx - ctr) / band)) }
            }
            if (al <= 0.01f) continue
            val gr = glow + 26f * boost + 18f * f2
            if (flyMode && flyPhase == 2 && f2 > 0.06f) {
                for (g in 3 downTo 1) {
                    val qq = q2 - g * 0.04f; if (qq <= 0f) continue
                    chPose(i, qq)
                    p.color = Color.argb((255 * al * 0.22f / g).toInt(), 90, 220, 255); p.setShadowLayer(0f, 0f, 0f, 0)
                    c.save(); c.translate(bx + tox[i] + pose[0] + cw / 2, by + toy[i] + pose[1] - ts * 0.3f); c.rotate(pose[2] * 20f); c.scale(pose[3], pose[3]); c.drawText(s, i, i + 1, -cw / 2, ts * 0.3f, p); c.restore()
                }
            }
            p.color = Color.rgb((60 + 195 * boost).toInt(), 255, (140 + 115 * boost).toInt())
            p.alpha = (255 * al).toInt()
            p.setShadowLayer(gr, 0f, 0f, Color.argb((220 * al).toInt(), 0, 255, 110))
            if (fa != 0f || sc != 1f) { c.save(); c.translate(bx + tox[i] + fx + cw / 2, by + toy[i] + fy - ts * 0.3f); c.rotate(fa * 20f); c.scale(sc, sc); c.drawText(s, i, i + 1, -cw / 2, ts * 0.3f, p); c.restore() }
            else c.drawText(s, i, i + 1, bx + tox[i], by + toy[i], p)
            p.alpha = 255
        }
        for (i in n until 400) { tox[i] = 0f; toy[i] = 0f; tvx[i] = 0f; tvy[i] = 0f }
        val endCol = n % maxC; val endRow = n / maxC
        blockBottom = top + endRow * ts * 1.45f + ts * 0.3f
        blinkT = ((now / 530L) % 2L).toInt()
        if ((!flyMode && busy) || blinkT == 0) {
            p.setShadowLayer(glow, 0f, 0f, Color.argb(220, 0, 255, 110))
            p.color = Color.argb(230, 60, 255, 140)
            c.drawRect(left + endCol * cw + 2f, top + endRow * ts * 1.45f - ts * 0.9f, left + endCol * cw + cw - 2f, top + endRow * ts * 1.45f + ts * 0.18f, p)
        }
        }
        p.setShadowLayer(0f, 0f, 0f, 0)

        // ---- player: progress + prev / play / next
        drawPlayer(c, w, h, now, posMs, durMs, playing, punch, high, Math.max(blockBottom + ts * 1.2f, h * 0.55f))

        // ---- mouse pointer
        if (ripple > 0 && now - ripple < 500) {
            val q = (now - ripple) / 500f
            p.style = Paint.Style.STROKE; p.strokeWidth = 3f
            p.color = Color.argb(((1f - q) * 200).toInt(), 120, 255, 180)
            c.drawCircle(rx, ry, 8f + q * w * 0.06f, p)
            p.style = Paint.Style.FILL
        }
        val path = Path()
        val k = w * 0.0042f
        path.moveTo(curX, curY); path.lineTo(curX, curY + 17f * k); path.lineTo(curX + 4.2f * k, curY + 13.2f * k)
        path.lineTo(curX + 7.6f * k, curY + 20f * k); path.lineTo(curX + 10f * k, curY + 18.8f * k); path.lineTo(curX + 6.6f * k, curY + 12f * k)
        path.lineTo(curX + 12f * k, curY + 12f * k); path.close()
        p.setShadowLayer(10f, 0f, 0f, Color.argb(200, 0, 255, 120))
        p.color = Color.WHITE; c.drawPath(path, p)
        p.setShadowLayer(0f, 0f, 0f, 0)
        p.style = Paint.Style.STROKE; p.strokeWidth = 1.5f; p.color = Color.BLACK; c.drawPath(path, p)
        p.style = Paint.Style.FILL
    }

    private fun drawPlayer(c: Canvas, w: Float, h: Float, now: Long, pos: Long, dur: Long, playing: Boolean, punch: Float, high: Float, y0: Float) {
        val left = w * 0.07f; val right = w * 0.93f
        val gl = 6f + 10f * Math.max(0f, punch)
        p.textSize = w * 0.032f
        p.setShadowLayer(0f, 0f, 0f, 0)
        p.color = Color.argb(190, 0, 200, 90)
        val ts = if (dur > 0) mmss(pos) else "--:--"
        val te = if (dur > 0) mmss(dur) else "--:--"
        c.drawText("t=" + ts, left, y0, p)
        val ew = p.measureText("max=" + te)
        c.drawText("max=" + te, right - ew, y0, p)
        val by = y0 + w * 0.045f
        val frac = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else ((now % 3000L) / 3000f)
        // track made of ticks
        val ticks = 48
        val tw = (right - left) / ticks
        val filled = (frac * ticks)
        for (i in 0 until ticks) {
            val on = i + 1 <= filled
            val hgt = w * (if (i % 4 == 0) 0.020f else 0.013f)
            p.color = if (on) Color.rgb(60, 255, 140) else Color.argb(90, 0, 160, 80)
            if (on) p.setShadowLayer(gl, 0f, 0f, Color.argb(200, 0, 255, 110)) else p.setShadowLayer(0f, 0f, 0f, 0)
            c.drawRect(left + i * tw + tw * 0.2f, by - hgt / 2, left + i * tw + tw * 0.75f, by + hgt / 2, p)
        }
        // playhead
        val kx = left + frac * (right - left)
        p.setShadowLayer(gl + 4f, 0f, 0f, Color.argb(230, 0, 255, 110)); p.color = Color.WHITE
        c.drawRect(kx - 3f, by - w * 0.03f, kx + 3f, by + w * 0.03f, p)
        p.setShadowLayer(0f, 0f, 0f, 0)

        // buttons
        val bw = w * 0.2f; val bh = w * 0.13f; val gap = w * 0.06f
        val total = bw * 3 + gap * 2
        val bx = (w - total) / 2; val byy = by + w * 0.09f
        for (i in 0 until 3) {
            val r = btn[i]
            if (!showSkip && i != 1) { r.set(-9999f, -9999f, -9990f, -9990f); continue }
            val x0 = if (showSkip) bx + i * (bw + gap) else (w - bw) / 2
            r.set(x0, byy, x0 + bw, byy + bh)
            val fl = if (i == flashIdx) (1f - (now - flashAt) / 450f).coerceIn(0f, 1f) else 0f
            if (fl > 0f) { p.color = Color.argb((fl * 150).toInt(), 60, 255, 140); p.setShadowLayer(18f * fl, 0f, 0f, Color.argb((fl * 255).toInt(), 0, 255, 110)); c.drawRoundRect(r, 8f, 8f, p) }
            p.setShadowLayer(gl, 0f, 0f, Color.argb(200, 0, 255, 110))
            p.style = Paint.Style.STROKE; p.strokeWidth = 2.5f
            p.color = Color.argb(230, 60, 255, 140)
            c.drawRoundRect(r, 8f, 8f, p)
            // corner brackets feel
            p.style = Paint.Style.FILL
            p.color = if (fl > 0.3f) Color.BLACK else Color.rgb(60, 255, 140)
            val cx = r.centerX(); val cy = r.centerY(); val u = bh * 0.22f
            val path = Path()
            when (i) {
                0 -> { path.moveTo(cx + u * 1.6f, cy - u * 1.4f); path.lineTo(cx - u * 0.2f, cy); path.lineTo(cx + u * 1.6f, cy + u * 1.4f); path.close()
                       c.drawPath(path, p); c.drawRect(cx - u * 1.9f, cy - u * 1.4f, cx - u * 1.3f, cy + u * 1.4f, p) }
                2 -> { path.moveTo(cx - u * 1.6f, cy - u * 1.4f); path.lineTo(cx + u * 0.2f, cy); path.lineTo(cx - u * 1.6f, cy + u * 1.4f); path.close()
                       c.drawPath(path, p); c.drawRect(cx + u * 1.3f, cy - u * 1.4f, cx + u * 1.9f, cy + u * 1.4f, p) }
                else -> if (MusicState.isPlaying) { c.drawRect(cx - u * 1.1f, cy - u * 1.4f, cx - u * 0.35f, cy + u * 1.4f, p); c.drawRect(cx + u * 0.35f, cy - u * 1.4f, cx + u * 1.1f, cy + u * 1.4f, p) }
                        else { path.moveTo(cx - u * 0.9f, cy - u * 1.5f); path.lineTo(cx + u * 1.5f, cy); path.lineTo(cx - u * 0.9f, cy + u * 1.5f); path.close(); c.drawPath(path, p) }
            }
        }
        p.setShadowLayer(0f, 0f, 0f, 0)
    }
}
