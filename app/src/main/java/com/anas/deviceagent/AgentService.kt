package com.anas.deviceagent

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AgentService : AccessibilityService() {
    companion object {
        @Volatile var instance: AgentService? = null
    }

    private var nodes: List<AccessibilityNodeInfo> = emptyList()

    override fun onServiceConnected() { instance = this }
    override fun onUnbind(intent: Intent?): Boolean { instance = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    /** Text description of the current screen. Nodes are numbered so the model can refer to them. */
    fun describeScreen(): String {
        val root = rootInActiveWindow ?: return "(screen not readable)"
        val list = ArrayList<AccessibilityNodeInfo>()
        val sb = StringBuilder("app: ${root.packageName}\n")
        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || list.size >= 150 || depth > 40) return
            val text = n.text?.toString()?.take(80)
            val desc = n.contentDescription?.toString()?.take(80)
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
                val id = n.viewIdResourceName?.substringAfter(":id/")
                if (!id.isNullOrBlank()) sb.append(" id=$id")
                if (flags.isNotEmpty()) sb.append(" {$flags}")
                sb.append(" @${r.centerX()},${r.centerY()}\n")
                list.add(n)
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }
        walk(root, 0)
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
        return tapXY(r.centerX().toFloat(), r.centerY().toFloat())
    }

    fun longPress(i: Int): Boolean {
        val n = node(i) ?: return false
        if (n.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) return true
        val r = Rect(); n.getBoundsInScreen(r)
        return gesture(r.centerX().toFloat(), r.centerY().toFloat(), r.centerX().toFloat(), r.centerY().toFloat(), 800)
    }

    fun tapXY(x: Float, y: Float) = gesture(x, y, x, y, 60)

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
        val n = i?.let { node(it) }
        var cur = n
        var d = 0
        while (cur != null && d < 6) {
            if (cur.isScrollable) {
                val a = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                if (cur.performAction(a)) return true
            }
            cur = cur.parent; d++
        }
        val m = resources.displayMetrics
        val x = m.widthPixels / 2f
        val (y1, y2) = if (down) m.heightPixels * 0.7f to m.heightPixels * 0.3f
        else m.heightPixels * 0.3f to m.heightPixels * 0.7f
        return gesture(x, y1, x, y2, 350)
    }

    fun back() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home() = performGlobalAction(GLOBAL_ACTION_HOME)

    fun openApp(query: String): String? {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(intent, 0)
        val q = query.trim().lowercase()
        val match = apps.firstOrNull { it.activityInfo.packageName.lowercase() == q }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase() == q }
            ?: apps.firstOrNull { it.loadLabel(pm).toString().lowercase().contains(q) }
            ?: return null
        val launch = pm.getLaunchIntentForPackage(match.activityInfo.packageName) ?: return null
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(launch)
        return match.loadLabel(pm).toString()
    }

    private fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, ms: Long): Boolean {
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
