package com.anas.deviceagent

import android.app.WallpaperManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

/** Sets the real phone wallpaper from an image. No screen interaction. */
object Wallpaper {
    fun apply(ctx: Context, bytes: ByteArray, which: String, dim: Float, blur: Float, tint: String, caption: String): String {
        val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return "error: not a valid image"
        val dm = ctx.resources.displayMetrics
        val w = dm.widthPixels; val h = dm.heightPixels
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val scale = Math.max(w.toFloat() / src.width, h.toFloat() / src.height)
        val sw = src.width * scale; val sh = src.height * scale
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        c.drawBitmap(src, null, android.graphics.RectF((w - sw) / 2, (h - sh) / 2, (w + sw) / 2, (h + sh) / 2), paint)
        var bmp = out
        if (blur > 0f) {
            val f = 1 + (blur.coerceIn(0f, 1f) * 30).toInt()
            val small = Bitmap.createScaledBitmap(bmp, Math.max(8, w / f), Math.max(8, h / f), true)
            bmp = Bitmap.createScaledBitmap(small, w, h, true)
        }
        val cv = Canvas(bmp)
        val tc = try { Color.parseColor(tint) } catch (e: Exception) { 0 }
        if (tint.isNotBlank() && tc != 0) { paint.color = tc; paint.alpha = 80; cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint); paint.alpha = 255 }
        if (dim > 0f) { paint.color = Color.BLACK; paint.alpha = (dim.coerceIn(0f, 0.85f) * 255).toInt(); cv.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint); paint.alpha = 255 }
        if (caption.isNotBlank()) {
            val tp = Paint(Paint.ANTI_ALIAS_FLAG)
            tp.color = Color.WHITE; tp.textSize = h / 30f; tp.textAlign = Paint.Align.CENTER
            tp.typeface = Typeface.DEFAULT_BOLD; tp.setShadowLayer(8f, 0f, 2f, Color.BLACK)
            cv.drawText(caption.take(60), w / 2f, h * 0.88f, tp)
        }
        val flags = when (which) { "lock" -> WallpaperManager.FLAG_LOCK; "home" -> WallpaperManager.FLAG_SYSTEM
            else -> WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK }
        return try {
            WallpaperManager.getInstance(ctx).setBitmap(bmp, null, true, flags)
            "ok: wallpaper set ($which)"
        } catch (e: Throwable) { "error: ${e.message}" }
    }
}
