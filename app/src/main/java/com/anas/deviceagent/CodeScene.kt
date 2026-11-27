package com.anas.deviceagent

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface

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
            if (c.isLetter() && rnd.nextFloat() < 0.035f && s.length > 6) {
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

    fun draw(c: Canvas, w: Float, h: Float, playing: Boolean, now: Long, bass: Float, punch: Float, high: Float, title: String, artist: String) {
        if (sizeW != w) { build(w, h); mx = 0.5f; my = 0.6f }
        val wantT = if (title.isBlank() && artist.isBlank()) "// waiting for a track..." else "const track = \"" + (title.trim() + (if (artist.isNotBlank()) " - " + artist.trim() else "")) + "\";"
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
        // dim prompt line
        p.setShadowLayer(0f, 0f, 0f, 0)
        p.color = Color.argb(150, 0, 170, 80); p.textSize = ts * 0.62f
        c.drawText("root@device:~# now_playing", left, top - ts * 1.7f, p)
        p.textSize = ts
        val s = shown.toString()
        val n = Math.min(s.length, 399)
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
            c.drawText(s, i, i + 1, bx + tox[i], by + toy[i], p)
        }
        for (i in n until 400) { tox[i] = 0f; toy[i] = 0f; tvx[i] = 0f; tvy[i] = 0f }
        // blinking block cursor at the end of the text
        val endCol = n % maxC; val endRow = n / maxC
        blinkT = ((now / 530L) % 2L).toInt()
        if (busy || blinkT == 0) {
            p.setShadowLayer(glow, 0f, 0f, Color.argb(220, 0, 255, 110))
            p.color = Color.argb(230, 60, 255, 140)
            c.drawRect(left + endCol * cw + 2f, top + endRow * ts * 1.45f - ts * 0.9f, left + endCol * cw + cw - 2f, top + endRow * ts * 1.45f + ts * 0.18f, p)
        }
        p.setShadowLayer(0f, 0f, 0f, 0)

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
}
