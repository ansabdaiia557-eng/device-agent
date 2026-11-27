package com.anas.deviceagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AgentService : AccessibilityService() {
    companion object {
        @Volatile var instance: AgentService? = null
    }

    /** 0 = real screen. Other value = id of the hidden virtual display. */
    @Volatile var targetDisplay = 0
    private var nodes: List<AccessibilityNodeInfo> = emptyList()
    private var overlay: TextView? = null

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun showOverlay() {
        Handler(Looper.getMainLooper()).post {
            if (overlay != null) return@post
            try {
                val tv = TextView(this).apply {
                    text = "🤖"
                    setTextColor(0xFF444444.toInt())
                    textSize = 28f
                    gravity = Gravity.CENTER
                    setBackgroundColor(Color.BLACK)
                }
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.OPAQUE)
                (getSystemService(WINDOW_SERVICE) as WindowManager).addView(tv, lp)
                overlay = tv
            } catch (e: Exception) {}
        }
    }

    fun hideOverlay() {
        Handler(Looper.getMainLooper()).post {
            try { overlay?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } } catch (e: Exception) {}
            overlay = null
        }
    }

    private fun roots(): List<AccessibilityNodeInfo> {
        if (targetDisplay == 0) return listOfNotNull(rootInActiveWindow)
        val all = try { getWindowsOnAllDisplays() } catch (e: Throwable) { null } ?: return emptyList()
        val wins = all.get(targetDisplay) ?: return emptyList()
        return wins.mapNotNull { try { it.root } catch (e: Exception) { null } }
    }

    /** Text description of the screen. Nodes are numbered so the model can refer to them. */
    fun describeScreen(): String {
        val rs = roots()
        if (rs.isEmpty()) return "(no windows)"
        val list = ArrayList<AccessibilityNodeInfo>()
        val sb = StringBuilder("app: ${rs[0].packageName}\n")
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || list.size >= 80 || depth > 40) return
            val text = n.text?.toString()?.take(60)
            val desc = n.contentDescription?.toString()?.take(60)
            val useful = n.isClickable || n.isEditable || n.isScrollable || n.isCheckable ||
                !text.isNullOrBlank() || !desc.isNullOrBlank()
            if (useful && n.isVisibleToUser) {
                val r = Rect(); n.getBoundsInScreen(r)
                val flags = buildList {
                    if (n.isClickable) add("click")
                    if (n.isEditable) add("edit")
                    if (n.isScrollable) add("scroll")
                    if (n.isCheckable) add(if (n.isChecked) "checked" else "unchecked")
                    if (!n.isEnabled) add("disabled")
                }.joinToString(",")
                sb.append("[${list.size}] ${n.className?.toString()?.substringAfterLast('.')}")
                if (!text.isNullOrBlank()) sb.append(" text=\"$text\"")
                if (!desc.isNullOrBlank()) sb.append(" desc=\"$desc\"")
                if (flags.isNotEmpty()) sb.append(" {$flags}")
                sb.append(" @${r.centerX()},${r.centerY()}\n")
                list.add(n)
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        for (r in rs) walk(r, 0)
        nodes = list
        return sb.toString()
    }

    private fun node(i: Int): AccessibilityNodeInfo? = nodes.getOrNull(i)

    fun tap(i: Int): Boolean {
        val n = node(i) ?: return false
        var cur: AccessibilityNodeInfo? = n
        var depth = 0
        while (cur != null && depth < 6) {
            if (cur.isClickable && cur.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            cur = cur.parent; depth++
        }
        val r = Rect(); n.getBoundsInScreen(r)
        return gesture(r.centerX().toFloat(), r.centerY().toFloat(), r.centerX().toFloat(), r.centerY().toFloat(), 60)
    }

    fun longPress(i: Int): Boolean {
        val n = node(i) ?: return false
        if (n.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) return true
        val r = Rect(); n.getBoundsInScreen(r)
        return gesture(r.centerX().toFloat(), r.centerY().toFloat(), r.centerX().toFloat(), r.centerY().toFloat(), 800)
    }

    fun type(i: Int, text: String): Boolean {
        val n = node(i) ?: return false
        n.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
        val args = Bundle()
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        return n.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun enter(i: Int): Boolean {
        val n = node(i) ?: return false
        return n.performAction(android.R.id.accessibilityActionImeEnter)
    }

    fun scroll(i: Int?, down: Boolean): Boolean {
        var cur = i?.let { node(it) }
        var d = 0
        while (cur != null && d < 6) {
            if (cur.isScrollable) {
                val a = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                if (cur.performAction(a)) return true
            }
            cur = cur.parent; d++
        }
        val (w, h) = if (targetDisplay == 0) resources.displayMetrics.let { it.widthPixels to it.heightPixels }
        else Agent.vdW to Agent.vdH
        val x = w / 2f
        val (y1, y2) = if (down) h * 0.7f to h * 0.3f else h * 0.3f to h * 0.7f
        return gesture(x, y1, x, y2, 350)
    }

    fun back(): Boolean {
        if (targetDisplay != 0) return Sh.exec(listOf("input", "-d", targetDisplay.toString(), "keyevent", "4")).startsWith("exit=0")
        return performGlobalAction(GLOBAL_ACTION_BACK)
    }
    fun home() = if (targetDisplay != 0) true else performGlobalAction(GLOBAL_ACTION_HOME)

    /** Finds an installed launcher app by label or package. Returns package to label. */
    fun findApp(query: String): Pair<String, String>? {
        val pm = packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val q = query.trim().lowercase()
        val m = apps.firstOrNull { it.activityInfo.packageName.lowercase() == q }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase() == q }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(q) }
            ?: return null
        return m.activityInfo.packageName to m.loadLabel(pm).toString()
    }

    fun openApp(query: String): String? {
        val (pkg, label) = findApp(query) ?: return null
        val launch = packageManager.getLaunchIntentForPackage(pkg) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launch)
        return label
    }

    private fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): Boolean {
        if (targetDisplay != 0) {
            val id = targetDisplay.toString()
            val cmd = if (x1 == x2 && y1 == y2) listOf("input", "-d", id, "tap", x1.toInt().toString(), y1.toInt().toString())
            else listOf("input", "-d", id, "swipe", x1.toInt().toString(), y1.toInt().toString(), x2.toInt().toString(), y2.toInt().toString(), ms.toString())
            return Sh.exec(cmd).startsWith("exit=0")
        }
        val p = Path(); p.moveTo(x1, y1); if (x1 != x2 || y1 != y2) p.lineTo(x2, y2)
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(p, 0, ms)).build()
        val latch = CountDownLatch(1)
        var ok = false
        val sent = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { ok = true; latch.countDown() }
            override fun onCancelled(d: GestureDescription?) { latch.countDown() }
        }, null)
        if (!sent) return false
        latch.await(5, TimeUnit.SECONDS)
        return ok
    }
}
