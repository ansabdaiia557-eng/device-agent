package com.anas.deviceagent

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper

/**
 * Started with app_process as the shell user (through Shizuku). Creates a virtual display and keeps it
 * alive until stdin closes. First stdout line: "DISPLAY <id>" or "ERROR <reason>".
 */
object VdHelper {
    @JvmStatic
    fun main(args: Array<String>) {
        try {
            val w = args[0].toInt(); val h = args[1].toInt(); val dpi = args[2].toInt()
            try { Looper.prepareMainLooper() } catch (e: Throwable) {}
            val atc = Class.forName("android.app.ActivityThread")
            val at = atc.getDeclaredMethod("systemMain").invoke(null)
            // Workarounds taken from scrcpy's Workarounds.java (Android 12: ConfigurationController, app info as com.android.shell).
            try {
                val cc = Class.forName("android.app.ConfigurationController")
                val ati = Class.forName("android.app.ActivityThreadInternal")
                val ctor = cc.getDeclaredConstructor(ati); ctor.isAccessible = true
                val f = atc.getDeclaredField("mConfigurationController"); f.isAccessible = true
                f.set(at, ctor.newInstance(at))
            } catch (e: Throwable) {}
            try {
                val abd = Class.forName("android.app.ActivityThread\$AppBindData")
                val ctor = abd.getDeclaredConstructor(); ctor.isAccessible = true
                val bind = ctor.newInstance()
                val ai = android.content.pm.ApplicationInfo(); ai.packageName = "com.android.shell"
                val f1 = abd.getDeclaredField("appInfo"); f1.isAccessible = true; f1.set(bind, ai)
                val f2 = atc.getDeclaredField("mBoundApplication"); f2.isAccessible = true; f2.set(at, bind)
            } catch (e: Throwable) {}
            val sysCtx = atc.getDeclaredMethod("getSystemContext").invoke(at) as Context
            val shellCtx = sysCtx.createPackageContext("com.android.shell", Context.CONTEXT_IGNORE_SECURITY)
            try {
                val f = Class.forName("android.app.ContextImpl").getDeclaredField("mOpPackageName")
                f.isAccessible = true
                f.set(shellCtx, "com.android.shell")
            } catch (e: Throwable) {}
            val dm = shellCtx.getSystemService(DisplayManager::class.java)
            val ht = HandlerThread("vd-reader"); ht.start()
            val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            reader.setOnImageAvailableListener({ r ->
                try { r.acquireLatestImage()?.close() } catch (e: Throwable) {}
            }, Handler(ht.looper))
            // Same flag set scrcpy uses below Android 13: PUBLIC | PRESENTATION | OWN_CONTENT_ONLY | SUPPORTS_TOUCH | ROTATES_WITH_CONTENT | DESTROY_CONTENT_ON_REMOVAL
            val flags = 1 or 2 or 8 or 64 or 128 or 256
            val vd = dm.createVirtualDisplay("deviceagent", w, h, dpi, reader.surface, flags)
            if (vd == null) { println("ERROR createVirtualDisplay returned null"); System.out.flush(); System.exit(1); return }
            println("DISPLAY ${vd.display.displayId}")
            System.out.flush()
            while (System.`in`.read() != -1) { }
            vd.release()
            System.exit(0)
        } catch (e: Throwable) {
            val c = e.cause
            println("ERROR ${e.javaClass.simpleName}: ${e.message}" + (if (c != null) " / ${c.javaClass.simpleName}: ${c.message}" else ""))
            System.out.flush()
            System.exit(1)
        }
    }
}
