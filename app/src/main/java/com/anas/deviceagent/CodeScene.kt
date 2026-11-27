package com.anas.deviceagent

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Bitmap
import android.graphics.LinearGradient
import android.graphics.Shader
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

    // typing
    private var shown = StringBuilder()
    private var target = ""
    private var nextAt = 0L
    private var typoPending = false
    val busy: Boolean get() = shown.toString() != target
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

    // glass backdrop buffers
    private var off: Bitmap? = null; private var offC: Canvas? = null
    private var mid: Bitmap? = null; private var midC: Canvas? = null
    private var small: Bitmap? = null; private var smallC: Canvas? = null
    private val filt = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private var pulseV = 0f
    private val eq = FloatArray(28)
    private var lastPunch = 0f
    private var kick = false
    private val gp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gpath = Path()
    private var cardBottomY = 0f
    private var cardKey = ""; private var cardH = 0

    private fun glass(c: Canvas, r0: RectF, rad: Float, pulse: Float, w: Float, h: Float, alphaBoost: Int = 0) {
        val pp = pulse.coerceIn(0f, 1.2f)
        val r = RectF(r0); r.inset(-pp * 4f, -pp * 3f)
        val sm = small
        gp.style = Paint.Style.FILL
        gp.color = Color.argb(60, 0, 0, 0)
        gp.setShadowLayer(16f + 22f * pp, 0f, 6f, Color.argb(90 + (130 * pp).toInt(), 0, 255, 130))
        c.drawRoundRect(r, rad, rad, gp)
        gp.setShadowLayer(0f, 0f, 0f, 0)
        c.save()
        gpath.reset(); gpath.addRoundRect(r, rad, rad, Path.Direction.CW)
        c.clipPath(gpath)
        if (sm != null) c.drawBitmap(sm, null, RectF(0f, 0f, w, h), filt)
        gp.color = Color.argb(34 + alphaBoost + (40 * pp).toInt(), 235, 255, 245)
        c.drawRect(r, gp)
        gp.shader = LinearGradient(0f, r.top, 0f, r.top + r.height() * 0.55f, Color.argb(95 + (60 * pp).toInt(), 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
        c.drawRect(r.left, r.top, r.right, r.top + r.height() * 0.55f, gp)
        gp.shader = LinearGradient(0f, r.bottom - r.height() * 0.3f, 0f, r.bottom, Color.argb(0, 120, 255, 190), Color.argb(50, 120, 255, 190), Shader.TileMode.CLAMP)
        c.drawRect(r.left, r.bottom - r.height() * 0.3f, r.right, r.bottom, gp)
        gp.shader = null
        c.restore()
        gp.style = Paint.Style.STROKE; gp.strokeWidth = 2.4f
        gp.shader = LinearGradient(r.left, r.top, r.right, r.bottom, intArrayOf(Color.argb(240, 255, 255, 255), Color.argb(50, 180, 255, 220), Color.argb(140 + (80 * pp).toInt(), 60, 255, 150), Color.argb(190, 255, 255, 255)), floatArrayOf(0f, 0.4f, 0.75f, 1f), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, rad, rad, gp)
        gp.shader = null
        gp.strokeWidth = 1f; gp.color = Color.argb(60 + (110 * pp).toInt(), 60, 255, 150)
        val ri = RectF(r); ri.inset(3.5f, 3.5f)
        c.drawRoundRect(ri, Math.max(1f, rad - 3.5f), Math.max(1f, rad - 3.5f), gp)
        gp.style = Paint.Style.FILL
    }

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

    fun draw(c0: Canvas, w: Float, h: Float, playing: Boolean, now: Long, bass: Float, punch: Float, high: Float, title: String, artist: String, posMs: Long, durMs: Long, bands: FloatArray, midLvl: Float) {
        val cv = c0
        val wi = w.toInt(); val hi = h.toInt()
        if (off == null || off!!.width != wi || off!!.height != hi) {
            off = Bitmap.createBitmap(wi, hi, Bitmap.Config.ARGB_8888); offC = Canvas(off!!)
            mid = Bitmap.createBitmap(wi / 4, hi / 4, Bitmap.Config.ARGB_8888); midC = Canvas(mid!!)
            small = Bitmap.createBitmap(Math.max(8, wi / 14), Math.max(8, hi / 14), Bitmap.Config.ARGB_8888); smallC = Canvas(small!!)
        }
        var c: Canvas = offC!!
        if (sizeW != w) { build(w, h); mx = 0.5f; my = 0.6f }
        val both = title.trim() + (if (artist.isNotBlank()) " - " + artist.trim() else "")
        rtl = both.any { isRtlChar(it) }
        val wantT = if (title.isBlank() && artist.isBlank()) "// waiting for a track..." else if (rtl) both else "const track = \"" + both + "\";"
        if (wantT != target) { target = wantT; nextAt = Math.min(nextAt, now + 150) }
        if (shown.isEmpty() && nextAt == 0L) nextAt = now + 400
        updateTyping(now)
        if (playing) updateMouse(w, h, now)
        val curX = mx * w; val curY = my * h
        c.drawColor(Color.BLACK)
        val lvl = 0.6f + 0.5f * bass
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
                if (rnd.nextFloat() < 0.08f + 0.25f * high) col.ch[rnd.nextInt(col.len)] = glyphs[rnd.nextInt(glyphs.size)]
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

        // ---- commit rain, build blurred backdrop for the glass
        cv.drawBitmap(off!!, 0f, 0f, null)
        midC!!.drawBitmap(off!!, null, RectF(0f, 0f, mid!!.width.toFloat(), mid!!.height.toFloat()), filt)
        smallC!!.drawBitmap(mid!!, null, RectF(0f, 0f, small!!.width.toFloat(), small!!.height.toFloat()), filt)
        c = cv
        kick = punch > 0.35f && lastPunch <= 0.35f; lastPunch = punch
        var any = 0f; for (b in bands) any += b
        for (i in 0 until 28) {
            val tgt = if (!playing) 0.02f else if (any > 0.05f) Math.min(1f, bands[Math.min(31, i * 32 / 28)] * 2.6f + 0.03f)
                else (bass + (high - bass) * i / 27f + midLvl * 0.3f) * (0.55f + 0.45f * Math.sin(now / 130.0 + i * 0.9).toFloat()) + 0.03f
            eq[i] += (tgt.coerceIn(0f, 1f) - eq[i]) * (if (tgt > eq[i]) 0.6f else 0.18f)
        }
        pulseV = Math.max(Math.max(0f, punch), pulseV * 0.9f)

        // ---- title block
        val ts = w * 0.047f
        p.textSize = ts
        val cw = p.measureText("M")
        val left = w * 0.07f
        val maxC = ((w * 0.86f) / cw).toInt().coerceAtLeast(8)
        val top = h * 0.36f
        val glow = 8f + 14f * Math.max(0f, punch) + 6f * high
        // glass card behind the title
        run {
            val rows = Math.max(1, (target.length + maxC - 1) / maxC)
            val cardTop = top - ts * 2.4f
            var cardBottom = top + (rows - 1) * ts * 1.45f + ts * 0.75f
            if (rtl) {
                if (cardKey != target) {
                    tp.textSize = ts * 1.05f
                    cardH = StaticLayout.Builder.obtain(target, 0, target.length, tp, (w * 0.86f).toInt()).setAlignment(Layout.Alignment.ALIGN_NORMAL).setTextDirection(TextDirectionHeuristics.ANYRTL_LTR).setLineSpacing(0f, 1.25f).build().height
                    cardKey = target
                }
                cardBottom = top - ts * 0.9f + cardH + ts * 0.5f
            }
            cardBottomY = cardBottom
            glass(c, RectF(w * 0.035f, cardTop, w * 0.965f, cardBottom), ts * 1.1f, punch, w, h)
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
            if (kick) bvy -= 3.5f
            bvx += -bx0 * 0.08f; bvy += -by0 * 0.08f; bvx *= 0.86f; bvy *= 0.86f; bx0 += bvx; by0 += bvy
            tp.setShadowLayer(glow, 0f, 0f, Color.argb(220, 0, 255, 110))
            tp.color = Color.rgb(60, 255, 140)
            c.save(); c.translate(left + bx0, top - ts * 0.9f + by0); lay.draw(c); c.restore()
            blockBottom = top - ts * 0.9f + lay.height
            blinkT = ((now / 530L) % 2L).toInt()
            if (busy || blinkT == 0) {
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
            if (kick) tvy[i] -= 2.5f + rnd.nextFloat() * 3.5f
            tvx[i] += -tox[i] * 0.06f; tvy[i] += -toy[i] * 0.06f
            tvx[i] *= 0.88f; tvy[i] *= 0.88f
            tox[i] += tvx[i]; toy[i] += tvy[i]
            p.setShadowLayer(glow, 0f, 0f, Color.argb(220, 0, 255, 110))
            p.color = Color.rgb(60, 255, 140)
            c.drawText(s, i, i + 1, bx + tox[i], by + toy[i], p)
        }
        for (i in n until 400) { tox[i] = 0f; toy[i] = 0f; tvx[i] = 0f; tvy[i] = 0f }
        val endCol = n % maxC; val endRow = n / maxC
        blockBottom = top + endRow * ts * 1.45f + ts * 0.3f
        blinkT = ((now / 530L) % 2L).toInt()
        if (busy || blinkT == 0) {
            p.setShadowLayer(glow, 0f, 0f, Color.argb(220, 0, 255, 110))
            p.color = Color.argb(230, 60, 255, 140)
            c.drawRect(left + endCol * cw + 2f, top + endRow * ts * 1.45f - ts * 0.9f, left + endCol * cw + cw - 2f, top + endRow * ts * 1.45f + ts * 0.18f, p)
        }
        }
        p.setShadowLayer(0f, 0f, 0f, 0)

        // ---- player: progress + prev / play / next
        drawPlayer(c, w, h, now, posMs, durMs, playing, punch, high, cardBottomY + ts * 2.2f)

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
        glass(c, RectF(left - w * 0.03f, y0 - w * 0.065f, right + w * 0.03f, by + w * 0.055f), w * 0.05f, punch, w, h)
        val frac = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else ((now % 3000L) / 3000f)
        // track made of ticks
        val ticks = 48
        val tw = (right - left) / ticks
        val filled = (frac * ticks)
        for (i in 0 until ticks) {
            val on = i + 1 <= filled
            val hgt = w * (if (i % 4 == 0) 0.020f else 0.013f) * (if (on) 1f + 0.9f * eq[i * 28 / 48] else 1f)
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
            r.set(bx + i * (bw + gap), byy, bx + i * (bw + gap) + bw, byy + bh)
            val fl = if (i == flashIdx) (1f - (now - flashAt) / 450f).coerceIn(0f, 1f) else 0f
            
            glass(c, r, bh / 2f, punch, w, h, (fl * 90).toInt())
            p.setShadowLayer(0f, 0f, 0f, 0)
            // corner brackets feel
            p.style = Paint.Style.FILL
            p.color = Color.rgb(235, 255, 245); p.setShadowLayer(8f + 10f * Math.max(0f, punch), 0f, 0f, Color.argb(200, 60, 255, 150))
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
        // spectrum strip: the song's frequencies, in glass
        val ey = byy + bh + w * 0.05f; val eh = w * 0.17f
        glass(c, RectF(left - w * 0.03f, ey, right + w * 0.03f, ey + eh), w * 0.05f, punch, w, h)
        val n = eq.size; val aw = (right - left) / n
        for (i in 0 until n) {
            val bhh = (eh * 0.78f * eq[i]).coerceAtLeast(4f)
            val x0 = left + i * aw + aw * 0.18f; val x1 = left + (i + 1) * aw - aw * 0.18f
            val yb = ey + eh * 0.89f
            p.shader = LinearGradient(0f, yb - bhh, 0f, yb, Color.argb(255, 235, 255, 245), Color.argb(230, 40, 230, 120), Shader.TileMode.CLAMP)
            p.setShadowLayer(6f + 10f * eq[i], 0f, 0f, Color.argb(200, 60, 255, 150))
            c.drawRoundRect(RectF(x0, yb - bhh, x1, yb), aw * 0.3f, aw * 0.3f, p)
            p.shader = null
        }
        p.setShadowLayer(0f, 0f, 0f, 0)
    }
}
