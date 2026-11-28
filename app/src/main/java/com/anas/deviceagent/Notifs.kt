package com.anas.deviceagent

import android.app.KeyguardManager
import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.service.notification.NotificationListenerService
import android.text.Layout
import android.text.StaticLayout
import android.text.TextDirectionHeuristics
import android.text.TextPaint
import android.text.TextUtils

class NItem(val key: String, val app: String, val title: String, val text: String, val time: Long, val icon: Bitmap?, val pkg: String = "", val pi: android.app.PendingIntent? = null)

/** Notifications read through the notification listener, drawn by the wallpaper on the lock screen only. */
object NotifState {
    @Volatile var items: List<NItem> = emptyList()
    @Volatile var version = 0
    private val iconCache = HashMap<String, Bitmap?>()
    private var lockAt = 0L
    private var lockVal = false

    fun enabled(ctx: Context): Boolean = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE).getBoolean("wp_lock_notifs", true)

    fun locked(ctx: Context): Boolean {
        val now = System.currentTimeMillis()
        if (now - lockAt > 400) {
            lockAt = now
            lockVal = try { (ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked } catch (e: Throwable) { false }
        }
        return lockVal
    }

    fun show(ctx: Context): Boolean = enabled(ctx) && locked(ctx)

    private fun iconFor(ctx: Context, pkg: String): Bitmap? {
        if (iconCache.containsKey(pkg)) return iconCache[pkg]
        var out: Bitmap? = null
        try {
            val d = ctx.packageManager.getApplicationIcon(pkg)
            val b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
            val cv = Canvas(b)
            d.setBounds(0, 0, 96, 96); d.draw(cv)
            out = b
        } catch (e: Throwable) {}
        iconCache[pkg] = out
        return out
    }

    fun rebuild(l: NotificationListenerService) {
        try {
            val arr = l.activeNotifications ?: return
            val list = ArrayList<NItem>()
            for (sbn in arr) {
                if (sbn.packageName == l.packageName) continue
                val n = sbn.notification ?: continue
                val f = n.flags
                if (f and Notification.FLAG_GROUP_SUMMARY != 0) continue
                if (f and Notification.FLAG_ONGOING_EVENT != 0 || f and Notification.FLAG_FOREGROUND_SERVICE != 0) continue
                val ex = n.extras ?: continue
                val tpl = ex.getString(Notification.EXTRA_TEMPLATE) ?: ""
                if (tpl.contains("MediaStyle") || ex.containsKey(Notification.EXTRA_MEDIA_SESSION)) continue
                if (n.category == Notification.CATEGORY_TRANSPORT || n.category == Notification.CATEGORY_PROGRESS) continue
                val title = (ex.getCharSequence(Notification.EXTRA_TITLE) ?: "").toString().trim()
                val text = (ex.getCharSequence(Notification.EXTRA_TEXT) ?: ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: "").toString().trim()
                if (title.isEmpty() && text.isEmpty()) continue
                val app = try { l.packageManager.getApplicationLabel(l.packageManager.getApplicationInfo(sbn.packageName, 0)).toString() } catch (e: Throwable) { sbn.packageName }
                list.add(NItem(sbn.key, app, title, text, sbn.postTime, iconFor(l, sbn.packageName), sbn.packageName, n.contentIntent))
            }
            list.sortByDescending { it.time }
            items = list.take(12)
            version++
        } catch (e: Throwable) {}
    }
}

object NotifRender {
    private fun tp(size: Float, color: Int, bold: Boolean): TextPaint {
        val p = TextPaint(Paint.ANTI_ALIAS_FLAG)
        p.textSize = size; p.color = color
        p.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        return p
    }

    private fun lay(text: String, p: TextPaint, width: Int): StaticLayout {
        val t = if (text.isEmpty()) " " else text
        return StaticLayout.Builder.obtain(t, 0, t.length, p, Math.max(10, width))
            .setMaxLines(1).setEllipsize(TextUtils.TruncateAt.END)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setTextDirection(TextDirectionHeuristics.FIRSTSTRONG_LTR).build()
    }

    private fun text(c: Canvas, t: String, p: TextPaint, x: Float, y: Float, width: Float) {
        val l = lay(t, p, width.toInt())
        c.save(); c.translate(x, y); l.draw(c); c.restore()
    }

    private fun right(c: Canvas, t: String, p: TextPaint, xr: Float, y: Float) {
        val w = p.measureText(t)
        c.drawText(t, xr - w, y, p)
    }

    private fun rel(ms: Long): String {
        val m = (System.currentTimeMillis() - ms) / 60000
        return if (m < 1) "now" else if (m < 60) "${m}m" else if (m < 1440) "${m / 60}h" else "${m / 1440}d"
    }

    private fun card(c: Canvas, r: RectF, rad: Float, s: Float) {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG); fill.color = Color.argb(215, 50, 53, 60)
        c.drawRoundRect(r, rad, rad, fill)
        val sheen = Paint(Paint.ANTI_ALIAS_FLAG)
        sheen.shader = LinearGradient(0f, r.top, 0f, r.bottom, Color.argb(48, 255, 255, 255), Color.argb(0, 255, 255, 255), Shader.TileMode.CLAMP)
        c.drawRoundRect(r, rad, rad, sheen)
        val b = Paint(Paint.ANTI_ALIAS_FLAG); b.style = Paint.Style.STROKE; b.strokeWidth = 1.3f * s
        b.color = Color.argb(95, 235, 240, 250)
        c.drawRoundRect(r, rad, rad, b)
    }

    /** Draws the cards in the lower part of a w x h screen. */
    fun draw(c: Canvas, w: Float, h: Float, music: Boolean, playing: Boolean) {
        val s = w / 720f
        val items = NotifState.items
        val showMusic = music && playing && (MusicState.songTitle.isNotBlank() || MusicState.songArtist.isNotBlank())
        if (!showMusic && items.isEmpty()) return
        val nH = 112f * s; val gap = 16f * s; val mH = 210f * s
        val maxN = if (showMusic) 3 else 4
        val nShow = Math.min(items.size, maxN)
        val more = items.size - nShow
        val yb = h * 0.945f
        val total = nShow * nH + Math.max(0, nShow - 1) * gap
        var y = yb - total
        val x0 = 36f * s; val x1 = w - 36f * s
        if (showMusic) {
            val top = y - (if (nShow > 0) 22f * s else 0f) - mH
            drawMusic(c, RectF(x0, top, x1, top + mH), s)
        }
        for (i in 0 until nShow) {
            drawItem(c, items[i], RectF(x0, y, x1, y + nH), s)
            y += nH + gap
        }
        if (more > 0) {
            val p = tp(20f * s, Color.argb(255, 120, 128, 140), false)
            val t = "+$more more"
            c.drawText(t, (w - p.measureText(t)) / 2f, yb + 34f * s, p)
        }
    }

    private fun drawItem(c: Canvas, it: NItem, r: RectF, s: Float) {
        card(c, r, 30f * s, s)
        val cx = r.left + 54f * s; val cy = r.top + 56f * s; val rad = 30f * s
        val disc = Paint(Paint.ANTI_ALIAS_FLAG); disc.color = Color.rgb(40, 43, 49)
        c.drawCircle(cx, cy, rad, disc)
        val ic = it.icon
        if (ic != null) {
            c.save()
            val path = Path(); path.addCircle(cx, cy, rad, Path.Direction.CW); c.clipPath(path)
            c.drawBitmap(ic, null, RectF(cx - rad, cy - rad, cx + rad, cy + rad), Paint(Paint.FILTER_BITMAP_FLAG))
            c.restore()
        } else {
            val lp = tp(26f * s, Color.rgb(232, 238, 246), true)
            val ch = (it.app.firstOrNull() ?: '?').toString().uppercase()
            c.drawText(ch, cx - lp.measureText(ch) / 2f, cy + 9f * s, lp)
        }
        val ring = Paint(Paint.ANTI_ALIAS_FLAG); ring.style = Paint.Style.STROKE; ring.strokeWidth = 1.2f * s; ring.color = Color.argb(120, 200, 208, 220)
        c.drawCircle(cx, cy, rad, ring)
        val tx = r.left + 104f * s; val tw = r.right - 24f * s - tx
        text(c, it.app, tp(19f * s, Color.rgb(135, 143, 155), false), tx, r.top + 12f * s, tw - 90f * s)
        right(c, rel(it.time), tp(19f * s, Color.rgb(135, 143, 155), false), r.right - 24f * s, r.top + 30f * s)
        text(c, it.title, tp(25f * s, Color.rgb(240, 244, 250), true), tx, r.top + 40f * s, tw)
        text(c, it.text, tp(22f * s, Color.rgb(170, 178, 190), false), tx, r.top + 72f * s, tw)
    }

    private fun drawMusic(c: Canvas, r: RectF, s: Float) {
        card(c, r, 34f * s, s)
        val cr = RectF(r.left + 24f * s, r.top + 28f * s, r.left + 174f * s, r.top + 178f * s)
        val art = MusicState.art
        c.save()
        val path = Path(); path.addRoundRect(cr, 26f * s, 26f * s, Path.Direction.CW); c.clipPath(path)
        if (art != null) {
            c.drawBitmap(art, null, cr, Paint(Paint.FILTER_BITMAP_FLAG))
        } else {
            val g = Paint(); g.shader = LinearGradient(0f, cr.top, 0f, cr.bottom, Color.rgb(24, 26, 30), Color.rgb(54, 58, 66), Shader.TileMode.CLAMP)
            c.drawRect(cr, g)
            val rp = Paint(Paint.ANTI_ALIAS_FLAG); rp.style = Paint.Style.STROKE
            rp.strokeWidth = 7f * s; rp.color = Color.rgb(200, 208, 220)
            c.drawCircle(cr.centerX(), cr.centerY(), 45f * s, rp)
            rp.strokeWidth = 3f * s; rp.color = Color.rgb(110, 116, 126)
            c.drawCircle(cr.centerX(), cr.centerY() - 3f * s, 31f * s, rp)
        }
        c.restore()
        val tx = r.left + 198f * s; val tw = r.right - 24f * s - tx
        text(c, MusicState.songTitle, tp(30f * s, Color.rgb(240, 244, 250), true), tx, r.top + 20f * s, tw)
        text(c, MusicState.songArtist, tp(24f * s, Color.rgb(165, 172, 184), false), tx, r.top + 64f * s, tw)
        // progress
        val by = r.top + 134f * s
        val bp = Paint(Paint.ANTI_ALIAS_FLAG)
        bp.color = Color.argb(60, 255, 255, 255)
        c.drawRoundRect(RectF(tx, by, tx + tw, by + 4f * s), 2f * s, 2f * s, bp)
        val dur = MusicState.durMs; val pos = MusicState.position()
        val fr = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
        bp.color = Color.argb(235, 225, 232, 242)
        c.drawRoundRect(RectF(tx, by, tx + tw * fr, by + 4f * s), 2f * s, 2f * s, bp)
        c.drawCircle(tx + tw * fr, by + 2f * s, 6.5f * s, bp.apply { color = Color.rgb(245, 248, 252) })
        val tpp = tp(18f * s, Color.rgb(130, 138, 150), false)
        fun fmt(ms: Long): String { val t = ms / 1000; return "${t / 60}:${(t % 60).toString().padStart(2, '0')}" }
        c.drawText(fmt(pos), tx, by + 30f * s, tpp)
        if (dur > 0) right(c, fmt(dur), tpp, tx + tw, by + 30f * s)
        // buttons (display only)
        val cy = r.top + 182f * s
        val col = Paint(Paint.ANTI_ALIAS_FLAG); col.color = Color.rgb(225, 232, 242)
        val mx = tx + tw / 2f
        fun tri(px: Float, dir: Int) {
            val p = Path()
            p.moveTo(px + dir * 10f * s, cy - 9f * s); p.lineTo(px - dir * 4f * s, cy); p.lineTo(px + dir * 10f * s, cy + 9f * s); p.close()
            c.drawPath(p, col)
            c.drawRect(minOf(px - dir * 8f * s, px - dir * 5f * s), cy - 9f * s, maxOf(px - dir * 8f * s, px - dir * 5f * s), cy + 9f * s, col)
        }
        tri(mx - 110f * s, 1); tri(mx + 110f * s, -1)
        val wp = Paint(Paint.ANTI_ALIAS_FLAG); wp.color = Color.rgb(238, 242, 248)
        c.drawCircle(mx, cy, 24f * s, wp)
        val dk = Paint(Paint.ANTI_ALIAS_FLAG); dk.color = Color.rgb(18, 20, 24)
        if (MusicState.isPlaying) {
            c.drawRect(mx - 9f * s, cy - 10f * s, mx - 5f * s, cy + 10f * s, dk); c.drawRect(mx + 5f * s, cy - 10f * s, mx + 9f * s, cy + 10f * s, dk)
        } else {
            val p = Path(); p.moveTo(mx - 6f * s, cy - 11f * s); p.lineTo(mx + 11f * s, cy); p.lineTo(mx - 6f * s, cy + 11f * s); p.close(); c.drawPath(p, dk)
        }
    }
}
