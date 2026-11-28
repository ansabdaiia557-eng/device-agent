package com.anas.deviceagent

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.app.Activity
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout

/** The custom lock screen. Sits above the system lock (system lock must be None, so swipe-up unlocks cleanly). */
object LockSetup {
    fun enabled(c: Context) = c.getSharedPreferences("prefs", Context.MODE_PRIVATE).getBoolean("lock_on", false)
    fun log(c: Context, msg: String) {
        val t = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        c.getSharedPreferences("prefs", Context.MODE_PRIVATE).edit().putString("lock_last", "$t $msg").apply()
    }
    fun status(c: Context): String {
        val p = c.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        fun yn(b: Boolean) = if (b) "✅" else "❌"
        val ov = try { android.provider.Settings.canDrawOverlays(c) } catch (e: Throwable) { false }
        return "قارئ الإشعارات: ${yn(MediaListener.inst != null)}\nShizuku: ${yn(Sh.granted())}\nالظهور فوق التطبيقات: ${yn(ov)}\nخدمة القفل شغالة: ${yn(LockService.running)}\nالتفعيل: ${yn(p.getBoolean("lock_on", false))}\nآخر إطفاء شاشة: ${p.getString("lock_last", "لسه محصلش")}"
    }
    fun startService(c: Context) {
        try { val i = Intent(c, LockService::class.java); if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i) else c.startService(i) } catch (e: Throwable) { log(c, "service start failed: ${e.javaClass.simpleName}") }
    }
    private fun viaApp(c: Context): Boolean = try {
        c.startActivity(Intent(c, LockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION).putExtra("sleep", true))
        true
    } catch (e: Throwable) { log(c, "startActivity failed: ${e.javaClass.simpleName} ${e.message?.take(70)}"); false }

    /** Tries the in-app start first (works with the overlay permission), then Shizuku "am start" as a second path. */
    fun show(c: Context, viaShizuku: Boolean) {
        val ov = try { android.provider.Settings.canDrawOverlays(c) } catch (e: Throwable) { false }
        if (ov && viaApp(c)) { log(c, "launched via app (overlay permission)"); return }
        if (viaShizuku && Sh.granted()) {
            Thread {
                try {
                    val r = Sh.exec(listOf("am", "start", "-n", "com.anas.deviceagent/.LockActivity", "--ez", "sleep", "true"), 8, 300)
                    val bad = !r.startsWith("exit=0") || r.contains("Exception") || r.contains("Error:")
                    if (bad && viaApp(c)) log(c, "shizuku failed, app start sent: " + r.replace("\n", " ").take(60))
                    else log(c, "shizuku: " + r.replace("\n", " ").take(80))
                } catch (e: Throwable) { viaApp(c); log(c, "shizuku error ${e.javaClass.simpleName}, app start sent") }
            }.start()
            return
        }
        if (viaApp(c)) log(c, "launched via app (no overlay, no shizuku)")
    }
    /** Grants everything it can through Shizuku. Returns a short report. */
    fun grant(c: Context): String {
        if (!Sh.granted()) return "Shizuku مش مفعّل"
        val p = c.packageName
        val cmds = listOf(
            listOf("appops", "set", p, "SYSTEM_ALERT_WINDOW", "allow"),
            listOf("pm", "grant", p, "android.permission.POST_NOTIFICATIONS"),
            listOf("cmd", "notification", "allow_listener", "$p/$p.MediaListener"),
            listOf("dumpsys", "deviceidle", "whitelist", "+$p"),
            listOf("appops", "set", p, "START_FOREGROUND", "allow"),
            listOf("appops", "set", p, "RUN_IN_BACKGROUND", "allow"),
            listOf("appops", "set", p, "RUN_ANY_IN_BACKGROUND", "allow")
        )
        val sb = StringBuilder()
        for (cmd in cmds) sb.append(cmd.take(3).joinToString(" ")).append(": ").append(try { Sh.exec(cmd, 8, 80).lineSequence().first() } catch (e: Throwable) { "err" }).append("\n")
        return sb.toString().trim()
    }
}

class LockActivity : Activity(), SensorEventListener {
    private lateinit var stage: FrameLayout
    private lateinit var clock: LockClockView
    private lateinit var figure: LockFigureView
    private lateinit var dim: View
    private lateinit var cards: LockCardsView
    private val h = Handler(Looper.getMainLooper())
    private var hidden = false
    private var torch = false
    private var unlocking = false
    private val dismissed = HashSet<String>()

    private val rx = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.action) {
                Intent.ACTION_SCREEN_ON -> { if (hidden) playEntrance() }
                Intent.ACTION_SCREEN_OFF -> { setHidden() }
            }
        }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Build.VERSION.SDK_INT >= 27) { setShowWhenLocked(true) } else {
            @Suppress("DEPRECATION") window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        }
        window.statusBarColor = Color.TRANSPARENT; window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 28) window.attributes = window.attributes.also { it.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES }
        LockAssets.load(this)
        stage = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        stage.addView(LockPhotoView(this))
        clock = LockClockView(this).also { stage.addView(it) }
        figure = LockFigureView(this).also { stage.addView(it) }
        dim = View(this).apply { setBackgroundColor(Color.BLACK); alpha = 0f }
        stage.addView(dim)
        cards = LockCardsView(this).also { stage.addView(it) }
        val root = FrameLayout(this); root.addView(stage, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        immersive()
        cards.items = visibleItems()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        registerReceiver(rx, IntentFilter(Intent.ACTION_SCREEN_ON).also { it.addAction(Intent.ACTION_SCREEN_OFF) })
        if (!pm.isInteractive) setHidden() else playEntrance()
        MediaListener.onChange = { h.post { cards.items = visibleItems() } }
    }

    private fun visibleItems() = NotifState.items.filter { it.key !in dismissed }

    private fun immersive() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }
    override fun onWindowFocusChanged(f: Boolean) { super.onWindowFocusChanged(f); if (f) immersive() }
    override fun onNewIntent(i: Intent?) { super.onNewIntent(i); setIntent(i); cards.items = visibleItems(); val pm = getSystemService(Context.POWER_SERVICE) as PowerManager; if (pm.isInteractive && hidden) playEntrance() }
    @Deprecated("no back") override fun onBackPressed() {}

    private fun resetStage() {
        stage.animate().cancel(); stage.translationY = 0f; stage.alpha = 1f; stage.scaleX = 1f; stage.scaleY = 1f
        cards.expand = 0f; cards.scroll = 0f; applyExpand(0f); unlocking = false
    }

    private fun setHidden() {
        hidden = true; resetStage()
        cards.alpha = 0f; clock.alpha = 0f
    }

    /** Drawer entrance: all cards rise together as one block, the clock fades in. No resize. */
    private fun playEntrance() {
        hidden = false; resetStage()
        val H = stage.height.toFloat().let { if (it <= 0f) resources.displayMetrics.heightPixels.toFloat() else it }
        cards.alpha = 0f; cards.translationY = H * 0.18f; clock.alpha = 0f
        cards.animate().translationY(0f).alpha(1f).setDuration(420).setInterpolator(DecelerateInterpolator(2f)).start()
        clock.animate().alpha(1f).setDuration(380).start()
    }

    // ---- clock parallax ----
    private var px = 0f; private var py = 0f
    private var sm: SensorManager? = null
    override fun onResume() {
        super.onResume()
        try { sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager; sm?.registerListener(this, sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_UI) } catch (e: Throwable) {}
    }
    override fun onPause() { super.onPause(); try { sm?.unregisterListener(this) } catch (e: Throwable) {} }
    override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    override fun onSensorChanged(e: SensorEvent) {
        val dp = resources.displayMetrics.density
        val tx = (-e.values[0] / 9.8f).coerceIn(-0.6f, 0.6f) * 14f * dp
        val ty = ((e.values[1] / 9.8f) - 0.75f).coerceIn(-0.5f, 0.5f) * 10f * dp
        px += (tx - px) * 0.12f; py += (ty - py) * 0.12f
        if (cards.expand < 0.05f) { clock.translationX = px; clock.translationY = py }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(rx) } catch (e: Throwable) {}
        if (MediaListener.onChange != null) MediaListener.onChange = null
    }

    // ---- expand ----
    private fun applyExpand(e: Float) {
        cards.expand = e
        dim.alpha = e * 0.6f
        val dp = resources.displayMetrics.density
        clock.pivotX = clock.width / 2f; clock.pivotY = clock.height * 0.15f
        clock.scaleX = 1f - 0.5f * e; clock.scaleY = clock.scaleX
        clock.alpha = 1f - 0.2f * e
        if (e > 0.05f) { clock.translationX = 0f; clock.translationY = -40f * dp * e }
    }
    private fun animExpand(to: Boolean) {
        val from = cards.expand
        ValueAnimator.ofFloat(from, if (to) 1f else 0f).apply {
            duration = 320; interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { applyExpand(it.animatedValue as Float) }
            start()
        }
    }

    // ---- touch ----
    private var downX = 0f; private var downY = 0f
    private var mode = 0 // 0 undecided, 1 card drag, 2 unlock drag, 3 expand drag, 4 scroll, 5 collapse drag, 9 ignore
    private var hitCard = -1; private var hitBtn = -1; private var hitClear = false
    private var scroll0 = 0f
    private var vt: VelocityTracker? = null

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean { onTouch(ev); return true }

    private fun onTouch(ev: MotionEvent) {
        if (unlocking) return
        val slop = ViewConfiguration.get(this).scaledTouchSlop.toFloat()
        val H = stage.height.toFloat().let { if (it <= 0f) resources.displayMetrics.heightPixels.toFloat() else it }
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; mode = 0
                hitCard = cards.cardAt(ev.x, ev.y); hitBtn = cards.buttonAt(ev.x, ev.y); hitClear = cards.clearAllAt(ev.x, ev.y)
                scroll0 = cards.scroll
                vt?.recycle(); vt = VelocityTracker.obtain(); vt?.addMovement(ev)
                stage.animate().cancel()
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(ev)
                val dx = ev.x - downX; val dy = ev.y - downY
                if (mode == 0 && (Math.abs(dx) > slop || Math.abs(dy) > slop)) {
                    val e = cards.expand > 0.5f
                    mode = if (Math.abs(dx) > Math.abs(dy)) { if (hitCard >= 0 && dx > 0) 1 else 9 }
                    else if (dy < 0) {
                        if (e) { if (downY > H * 0.93f) 2 else 4 }
                        else if (hitCard >= 0 && cards.canExpand()) 3 else 2
                    } else { if (e && cards.scroll <= 1f) 5 else if (e) 4 else 9 }
                }
                when (mode) {
                    1 -> cards.setDrag(hitCard, dx)
                    2 -> { val d = Math.max(0f, -dy); val p = (d / (H * 0.7f)).coerceAtMost(1f); stage.translationY = -d; stage.alpha = 1f - p * 0.85f; stage.scaleX = 1f - 0.08f * p; stage.scaleY = stage.scaleX }
                    3 -> applyExpand((-dy / (H * 0.3f)).coerceIn(0f, 1f))
                    4 -> cards.scroll = scroll0 - dy / cards.designScale()
                    5 -> applyExpand((1f - dy / (H * 0.3f)).coerceIn(0f, 1f))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vt?.addMovement(ev); vt?.computeCurrentVelocity(1000)
                val vy = vt?.yVelocity ?: 0f
                val dx = ev.x - downX; val dy = ev.y - downY
                val up = ev.actionMasked == MotionEvent.ACTION_UP
                when (mode) {
                    0 -> if (up) tap(ev.x, ev.y)
                    1 -> { val k = hitCard; val key = cards.keyAt(k); val go = dx > ev.x.let { stage.width * 0.3f } || (vt?.xVelocity ?: 0f) > 1400f
                        cards.release(k, go) { if (key != null) { dismissed.add(key); MediaListener.dismiss(key); cards.items = visibleItems(); if (cards.items.size < 2 && cards.expand > 0.5f) animExpand(false) } } }
                    2 -> { val d = -dy; if (d > H * 0.22f || vy < -1500f) unlock(null) else {
                        stage.animate().translationY(0f).alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(DecelerateInterpolator()).start() } }
                    3 -> animExpand(cards.expand > 0.4f || vy < -1200f)
                    5 -> animExpand(!(cards.expand < 0.6f || vy > 1200f))
                    4 -> fling(vy)
                }
                vt?.recycle(); vt = null
            }
        }
    }

    private fun fling(vy: Float) {
        val dist = vy * 0.25f / cards.designScale()
        val s0 = cards.scroll
        ValueAnimator.ofFloat(0f, 1f).apply { duration = 450; interpolator = DecelerateInterpolator(2f)
            addUpdateListener { cards.scroll = s0 - dist * (it.animatedValue as Float) }; start() }
    }

    private fun tap(x: Float, y: Float) {
        if (hitClear) { val keys = cards.items.map { it.key }; for (k in keys) { dismissed.add(k); MediaListener.dismiss(k) }; cards.items = visibleItems(); animExpand(false); return }
        if (hitBtn == 0) { toggleTorch(); return }
        if (hitBtn == 1) { unlock { try { startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Throwable) {} }; return }
        if (hitCard >= 0) {
            val it = cards.itemAt(hitCard) ?: return
            unlock {
                try { if (it.pi != null) it.pi.send() else packageManager.getLaunchIntentForPackage(it.pkg)?.let { li -> startActivity(li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
                catch (e: Throwable) { try { packageManager.getLaunchIntentForPackage(it.pkg)?.let { li -> startActivity(li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } } catch (e2: Throwable) {} }
            }
            return
        }
        if (cards.expand > 0.5f) animExpand(false)
    }

    private fun toggleTorch() {
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true } ?: return
            torch = !torch; cm.setTorchMode(id, torch); cards.torchOn = torch
        } catch (e: Throwable) { torch = false; cards.torchOn = false }
    }

    /** Slide the whole lock screen up and away, dismiss the system lock, run [after], close. */
    private fun unlock(after: (() -> Unit)?) {
        if (unlocking) return
        unlocking = true
        val H = stage.height.toFloat().let { if (it <= 0f) resources.displayMetrics.heightPixels.toFloat() else it }
        val fin = Runnable { try { if (torch) { torch = false; toggleTorchOffQuiet() } } catch (e: Throwable) {}; after?.invoke(); finish(); overridePendingTransition(0, 0) }
        stage.animate().translationY(-H * 0.55f).alpha(0f).scaleX(0.9f).scaleY(0.9f).setDuration(260).setInterpolator(DecelerateInterpolator(1.4f))
            .setListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: Animator) {
                    stage.animate().setListener(null)
                    val km = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                    if (!km.isKeyguardLocked) { fin.run(); return }
                    var done = false
                    km.requestDismissKeyguard(this@LockActivity, object : KeyguardManager.KeyguardDismissCallback() {
                        override fun onDismissSucceeded() { if (!done) { done = true; fin.run() } }
                        override fun onDismissCancelled() { if (!done) { done = true; unlocking = false; resetStage() } }
                        override fun onDismissError() { if (!done) { done = true; unlocking = false; resetStage() } }
                    })
                }
            }).start()
    }
    private fun toggleTorchOffQuiet() { try { val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager; for (id in cm.cameraIdList) try { cm.setTorchMode(id, false) } catch (e: Throwable) {} } catch (e: Throwable) {} }
}


/** Keeps the process alive and owns the SCREEN_OFF receiver. */
class LockService : android.app.Service() {
    companion object { @Volatile var running = false }
    private var reg = false
    private val rx = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            if (i?.action == Intent.ACTION_SCREEN_OFF) {
                if (!LockSetup.enabled(this@LockService)) { LockSetup.log(this@LockService, "screen off, lock_on is off"); return }
                LockSetup.log(this@LockService, "screen off, launching")
                Thread { LockAssets.load(this@LockService) }.start()
                LockSetup.show(this@LockService, true)
            }
        }
    }
    override fun onBind(i: Intent?): android.os.IBinder? = null
    override fun onStartCommand(i: Intent?, f: Int, id: Int): Int {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(android.app.NotificationChannel("lock", "Lock screen", android.app.NotificationManager.IMPORTANCE_MIN))
            val n = android.app.Notification.Builder(this, "lock").setSmallIcon(android.R.drawable.ic_lock_idle_lock).setContentTitle("Lock screen active").setOngoing(true).build()
            startForeground(77, n)
        } catch (e: Throwable) { LockSetup.log(this, "startForeground failed: ${e.javaClass.simpleName}") }
        if (!reg) { try { registerReceiver(rx, IntentFilter(Intent.ACTION_SCREEN_OFF)); reg = true } catch (e: Throwable) {} }
        running = true
        return START_STICKY
    }
    override fun onDestroy() { running = false; if (reg) try { unregisterReceiver(rx) } catch (e: Throwable) {}; super.onDestroy() }
}

class LockBoot : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent?) { if (LockSetup.enabled(c)) LockSetup.startService(c) }
}
