package com.anas.deviceagent

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import android.view.animation.DecelerateInterpolator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Everything is drawn in a 720x1600 design space, scaled to cover the screen (same crop as the photo). */
object LockDesign {
    const val W = 720f
    const val H = 1600f
    fun scale(w: Int, h: Int) = Math.max(w / W, h / H)
    fun apply(c: Canvas, w: Int, h: Int) {
        val s = scale(w, h)
        c.translate((w - W * s) / 2f, (h - H * s) / 2f)
        c.scale(s, s)
    }
}

class LockPhotoView(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.FILTER_BITMAP_FLAG)
    override fun onDraw(c: Canvas) {
        val b = LockAssets.photo ?: run { c.drawColor(Color.BLACK); return }
        c.save(); LockDesign.apply(c, width, height); c.drawBitmap(b, 0f, 0f, p); c.restore()
    }
}

/** His outline glow and the cut-out. */
class LockFigureView(ctx: Context) : View(ctx) {
    private val p = Paint(Paint.FILTER_BITMAP_FLAG)
    private val gp = Paint(Paint.FILTER_BITMAP_FLAG).apply { color = Color.argb(70, 225, 232, 245) }
    override fun onDraw(c: Canvas) {
        val cut = LockAssets.cut ?: return
        c.save(); LockDesign.apply(c, width, height)
        LockAssets.glow?.let { c.drawBitmap(it, 0f, 0f, gp) }
        c.drawBitmap(cut, 0f, 0f, p)
        c.restore()
    }
}

/** The giant clock with real blurred glass, and the date above it. Static, so it is drawn once a minute. */
class LockClockView(ctx: Context) : View(ctx) {
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create("sans-serif-condensed", Typeface.NORMAL); textSize = 400f }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val inP = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN) }
    private val atop = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
    private val tint = Paint().apply { color = Color.argb(34, 255, 255, 255); xfermode = atop }
    private val grad = Paint().apply { xfermode = atop }
    private val hi = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 7f; color = Color.argb(215, 255, 255, 255); xfermode = atop }
    private val lo = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 9f; color = Color.argb(110, 0, 0, 0); xfermode = atop }
    private val hair = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.8f; color = Color.argb(150, 255, 255, 255) }
    private val datePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(235, 255, 255, 255); textSize = 36f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL); textAlign = Paint.Align.CENTER
        setShadowLayer(6f, 0f, 2f, Color.argb(120, 0, 0, 0))
    }
    private var path = Path(); private var pathFor = ""
    private val dst = RectF(0f, 0f, LockDesign.W, LockDesign.H)
    private val target = RectF(38f, 235f, 682f, 800f)

    private fun timeText(): String = SimpleDateFormat("HH:mm", Locale.US).format(Date())

    private fun buildPath(txt: String) {
        if (txt == pathFor) return
        val pth = Path(); tp.getTextPath(txt, 0, txt.length, 0f, 0f, pth)
        val b = RectF(); pth.computeBounds(b, true)
        val m = Matrix(); m.setRectToRect(b, target, Matrix.ScaleToFit.FILL); pth.transform(m)
        path = pth; pathFor = txt
        grad.shader = LinearGradient(0f, target.top, 0f, target.bottom, Color.argb(70, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
    }

    override fun onDraw(c: Canvas) {
        c.save(); LockDesign.apply(c, width, height)
        buildPath(timeText())
        val blur = LockAssets.blurP
        val sc = c.saveLayer(0f, 0f, LockDesign.W, LockDesign.H, null)
        c.drawPath(path, fill)
        if (blur != null) c.drawBitmap(blur, null, dst, inP)
        c.drawRect(dst, tint)
        c.drawRect(target, grad)
        c.save(); c.translate(2.5f, 2.5f); c.drawPath(path, hi); c.restore()
        c.save(); c.translate(-3.5f, -3.5f); c.drawPath(path, lo); c.restore()
        c.restoreToCount(sc)
        c.drawPath(path, hair)
        val d = SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date())
        c.drawText(d, LockDesign.W / 2f, 178f, datePaint)
        c.restore()
    }
}

/** Glass notification cards, torch and camera buttons. The drawer motion moves the whole view, so it costs nothing per frame.
 *  expand 0 = stack at the bottom, 1 = scrollable list. */
class LockCardsView(ctx: Context) : View(ctx) {
    var items: List<NItem> = emptyList()
        set(v) { field = v.take(12); offX = FloatArray(12); alp = FloatArray(12) { 1f }; clampScroll(); invalidate() }
    var expand = 0f
        set(v) { field = v.coerceIn(0f, 1f); invalidate() }
    var scroll = 0f
        set(v) { field = v; clampScroll(); invalidate() }
    var torchOn = false
        set(v) { field = v; invalidate() }
    private var offX = FloatArray(12)
    private var alp = FloatArray(12) { 1f }
    private val cw = 648f; private val ch = 116f; private val gap = 14f; private val baseBottom = 1336f
    private val listTop = 330f
    private val inP = Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN) }
    private val atop = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val dark = Paint().apply { color = Color.argb(92, 12, 14, 18); xfermode = atop }
    private val sheen = Paint().apply { xfermode = atop }
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.8f; color = Color.argb(110, 255, 255, 255) }
    private val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 29f; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD) }
    private val body = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(225, 255, 255, 255); textSize = 25f }
    private val meta = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(170, 255, 255, 255); textSize = 21f; textAlign = Paint.Align.RIGHT }
    private val head = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 44f; typeface = Typeface.create("sans-serif-medium", Typeface.BOLD) }
    private val pillT = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 24f; textAlign = Paint.Align.CENTER }
    private val ip = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(130, 255, 255, 255) }
    private val ico = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(240, 245, 247, 250) }
    private val hint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 255, 255, 255); textSize = 24f; textAlign = Paint.Align.CENTER }
    private val dst = RectF(0f, 0f, LockDesign.W, LockDesign.H)
    private val btnL = RectF(46f, 1420f, 146f, 1520f)
    private val btnR = RectF(574f, 1420f, 674f, 1520f)
    private val clearAll = RectF(500f, 215f, 680f, 275f)

    private fun maxScroll() = Math.max(0f, listTop + items.size * (ch + gap) - 1400f)
    private fun clampScroll() { if (scroll > maxScroll()) scroll = maxScroll(); if (scroll < 0f) scroll = 0f }
    fun maxScrollPx() = maxScroll() * LockDesign.scale(width, height)
    fun canExpand() = items.size > 1

    private fun rectFor(i: Int): RectF {
        val x = (LockDesign.W - cw) / 2f
        val vis = Math.min(items.size, 3)
        val slot = Math.min(i, vis - 1)
        val ct = baseBottom - (vis - slot) * (ch + gap) + gap
        val et = listTop + i * (ch + gap) - scroll
        val t = ct + (et - ct) * expand
        return RectF(x, t, x + cw, t + ch)
    }
    private fun alphaFor(i: Int): Float {
        val vis = Math.min(items.size, 3)
        val base = if (i >= vis) expand else 1f
        return base * alp[i]
    }
    private fun toDesign(px: Float, py: Float): FloatArray {
        val s = LockDesign.scale(width, height)
        return floatArrayOf((px - (width - LockDesign.W * s) / 2f) / s, (py - translationY - (height - LockDesign.H * s) / 2f) / s)
    }
    fun cardAt(px: Float, py: Float): Int {
        val p = toDesign(px, py)
        if (expand > 0.5f && p[1] < 290f) return -1
        for (i in items.indices.reversed()) { if (alphaFor(i) < 0.3f) continue; if (rectFor(i).contains(p[0], p[1])) return i }
        return -1
    }
    fun buttonAt(px: Float, py: Float): Int {
        if (expand > 0.5f) return -1
        val p = toDesign(px, py)
        return if (btnL.contains(p[0], p[1])) 0 else if (btnR.contains(p[0], p[1])) 1 else -1
    }
    fun clearAllAt(px: Float, py: Float): Boolean {
        if (expand < 0.8f || items.isEmpty()) return false
        val p = toDesign(px, py); return clearAll.contains(p[0], p[1])
    }
    fun keyAt(i: Int): String? = items.getOrNull(i)?.key
    fun itemAt(i: Int): NItem? = items.getOrNull(i)
    fun designScale() = LockDesign.scale(width, height)

    fun setDrag(i: Int, dxScreen: Float) {
        if (i !in items.indices) return
        val s = LockDesign.scale(width, height)
        offX[i] = Math.max(0f, dxScreen) / s
        alp[i] = (1f - offX[i] / (cw * 0.9f)).coerceIn(0.15f, 1f)
        invalidate()
    }

    fun release(i: Int, dismiss: Boolean, onGone: () -> Unit) {
        if (i !in items.indices) return
        val from = offX[i]; val a0 = alp[i]
        val to = if (dismiss) LockDesign.W else 0f
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (dismiss) 180L else 160L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                if (i < offX.size) { offX[i] = from + (to - from) * f; alp[i] = if (dismiss) a0 * (1f - f) else a0 + (1f - a0) * f }
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) { if (dismiss) onGone() }
            })
            start()
        }
    }

    private fun ago(t: Long): String {
        val m = ((System.currentTimeMillis() - t) / 60000L).toInt()
        return if (m < 1) "now" else if (m < 60) "${m}m" else if (m < 1440) "${m / 60}h" else "${m / 1440}d"
    }

    private fun glassRR(c: Canvas, r: RectF, rad: Float, a: Int, blur: Bitmap?, extraDark: Int = 0) {
        val sc = c.saveLayerAlpha(r.left - 4, r.top - 4, r.right + 4, r.bottom + 4, a)
        c.drawRoundRect(r, rad, rad, fill)
        if (blur != null) c.drawBitmap(blur, null, dst, inP)
        c.drawRect(r, dark)
        if (extraDark > 0) { val p = Paint().apply { color = Color.argb(extraDark, 255, 255, 255); xfermode = atop }; c.drawRect(r, p) }
        sheen.shader = LinearGradient(0f, r.top, 0f, r.bottom, Color.argb(58, 255, 255, 255), Color.argb(10, 255, 255, 255), Shader.TileMode.CLAMP)
        c.drawRect(r, sheen)
        c.drawRoundRect(r, rad, rad, border)
        c.restoreToCount(sc)
    }

    override fun onDraw(c: Canvas) {
        c.save(); LockDesign.apply(c, width, height)
        val blur = LockAssets.blurPC
        // buttons
        val bl = ((1f - expand) * 255).toInt()
        if (bl > 4) {
            glassRR(c, btnL, 50f, bl, blur, if (torchOn) 120 else 0)
            glassRR(c, btnR, 50f, bl, blur)
            ico.alpha = bl
            ico.color = if (torchOn) Color.argb(bl, 30, 34, 40) else Color.argb(bl, 245, 247, 250)
            val cx = btnL.centerX(); val cy = btnL.centerY()
            c.drawRoundRect(RectF(cx - 11, cy - 28, cx + 11, cy - 10), 5f, 5f, ico)
            val tp = Path(); tp.moveTo(cx - 11, cy - 10); tp.lineTo(cx + 11, cy - 10); tp.lineTo(cx + 7, cy + 4); tp.lineTo(cx - 7, cy + 4); tp.close(); c.drawPath(tp, ico)
            c.drawRoundRect(RectF(cx - 7, cy + 4, cx + 7, cy + 30), 4f, 4f, ico)
            ico.color = Color.argb(bl, 245, 247, 250)
            val rx = btnR.centerX(); val ry = btnR.centerY()
            c.drawRoundRect(RectF(rx - 28, ry - 18, rx + 28, ry + 22), 10f, 10f, ico)
            ico.color = Color.argb(bl, 40, 44, 52); c.drawCircle(rx, ry + 2, 13f, ico)
            ico.color = Color.argb(bl, 245, 247, 250); c.drawCircle(rx, ry + 2, 8f, ico)
            hint.alpha = (bl * 0.7f).toInt()
            c.drawText("Swipe up to unlock", LockDesign.W / 2f, 1548f, hint)
            c.drawRoundRect(RectF(LockDesign.W / 2f - 70f, 1566f, LockDesign.W / 2f + 70f, 1572f), 3f, 3f, bar)
            if (items.size > 3) {
                val pr = RectF(LockDesign.W / 2f - 80f, 1352f, LockDesign.W / 2f + 80f, 1394f)
                glassRR(c, pr, 21f, bl, blur)
                pillT.alpha = bl
                c.drawText("+${items.size - 3} more", LockDesign.W / 2f, 1382f, pillT)
            }
        }
        // header of the expanded list
        if (expand > 0.05f) {
            val a = (expand * 255).toInt()
            head.alpha = a
            c.drawText("Notifications", 40f, 255f, head)
            glassRR(c, clearAll, 30f, a, blur)
            pillT.alpha = a
            c.drawText("Clear all", clearAll.centerX(), clearAll.centerY() + 9f, pillT)
        }
        c.save()
        if (expand > 0f) c.clipRect(0f, 290f * expand, LockDesign.W, LockDesign.H)
        for (i in items.indices) {
            val it = items[i]; val r = rectFor(i)
            if (r.bottom < 0f || r.top > LockDesign.H) continue
            val al = alphaFor(i)
            if (al <= 0.01f) continue
            c.save(); c.translate(offX[i], 0f)
            val a = (al * 255).toInt()
            glassRR(c, r, 42f, a, blur)
            val sc2 = c.saveLayerAlpha(r.left - 4, r.top - 4, r.right + 4, r.bottom + 4, a)
            val ic = it.icon
            val ir = RectF(r.left + 24, r.top + (ch - 62) / 2f, r.left + 24 + 62, r.top + (ch - 62) / 2f + 62)
            if (ic != null) c.drawBitmap(ic, null, ir, ip) else c.drawRoundRect(ir, 16f, 16f, bar)
            val tx = ir.right + 20f
            val maxW = r.right - 24f - tx - 130f
            val ttl = (if (it.title.isNotBlank()) it.title else it.app)
            c.drawText(TextUtils.ellipsize(ttl, title, maxW, TextUtils.TruncateAt.END).toString(), tx, r.top + 48f, title)
            val bw = r.right - 24f - tx
            c.drawText(TextUtils.ellipsize(if (it.text.isNotBlank()) it.text else it.app, body, bw, TextUtils.TruncateAt.END).toString(), tx, r.top + 88f, body)
            c.drawText(ago(it.time), r.right - 26f, r.top + 40f, meta)
            c.restoreToCount(sc2)
            c.restore()
        }
        c.restore()
        c.restore()
    }
}
