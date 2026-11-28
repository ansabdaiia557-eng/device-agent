package com.anas.deviceagent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Paint

/** Decoded once per process: the photo, the cut-out of him, a glow around his outline and two blurred copies for the glass. */
object LockAssets {
    @Volatile var photo: Bitmap? = null
    @Volatile var cut: Bitmap? = null
    @Volatile var glow: Bitmap? = null
    @Volatile var blurP: Bitmap? = null   // photo only, for the clock digits (they sit behind him)
    @Volatile var blurPC: Bitmap? = null  // photo plus him, for the notification cards (they sit in front)

    @Synchronized fun load(ctx: Context) {
        if (photo != null && blurPC != null) return
        try {
            val p = ctx.assets.open("lock_photo.jpg").use { BitmapFactory.decodeStream(it) }
            val c = ctx.assets.open("lock_cut.png").use { BitmapFactory.decodeStream(it) }
            val comp = p.copy(Bitmap.Config.ARGB_8888, true)
            Canvas(comp).drawBitmap(c, 0f, 0f, null)
            blurP = blur(p, 6, 5)
            blurPC = blur(comp, 6, 5)
            comp.recycle()
            val a = c.extractAlpha(Paint().apply { maskFilter = BlurMaskFilter(16f, BlurMaskFilter.Blur.NORMAL) }, null)
            glow = a
            cut = c
            photo = p
        } catch (e: Throwable) {}
    }

    private fun blur(src: Bitmap, scale: Int, r: Int): Bitmap {
        val w = src.width / scale; val h = src.height / scale
        val s = Bitmap.createScaledBitmap(src, w, h, true)
        var a = IntArray(w * h); s.getPixels(a, 0, w, 0, 0, w, h)
        var b = IntArray(w * h)
        for (i in 0 until 2) {
            pass(a, b, w, h, r, true); pass(b, a, w, h, r, false)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(a, 0, w, 0, 0, w, h)
        if (s !== src) s.recycle()
        return out
    }

    private fun pass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horiz: Boolean) {
        val n = 2 * r + 1
        val lines = if (horiz) h else w
        val len = if (horiz) w else h
        for (l in 0 until lines) {
            for (i in 0 until len) {
                var sr = 0; var sg = 0; var sb = 0
                for (k in -r..r) {
                    val j = (i + k).coerceIn(0, len - 1)
                    val px = if (horiz) src[l * w + j] else src[j * w + l]
                    sr += (px shr 16) and 255; sg += (px shr 8) and 255; sb += px and 255
                }
                val o = (0xFF shl 24) or ((sr / n) shl 16) or ((sg / n) shl 8) or (sb / n)
                if (horiz) dst[l * w + i] = o else dst[i * w + l] = o
            }
        }
    }
}
