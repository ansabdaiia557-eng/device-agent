package com.anas.deviceagent

import android.content.Context
import org.json.JSONObject

object Agent {
    private const val MAX_STEPS = 25

    val log = ArrayList<String>()
    @Volatile var listener: ((String) -> Unit)? = null
    @Volatile var running = false
    @Volatile private var stop = false
    @Volatile var onFinished: (() -> Unit)? = null

    private fun say(s: String) {
        synchronized(log) { log.add(s) }
        listener?.invoke(s)
    }

    fun stop() { stop = true }

    private const val SYSTEM = """You control an Android phone for the user through an accessibility service.
Each turn you receive the user's goal, the steps done so far, and the current screen as a numbered list of UI elements.
Reply with ONE JSON object only, with these fields:
{"thought": "short reasoning", "action": "...", "index": number, "text": "...", "direction": "up|down", "app": "...", "message": "..."}
Actions:
- open_app: open an installed app. Put its name (or package) in "app".
- tap: tap element "index".
- long_press: long press element "index".
- type: put "text" into the editable element "index".
- enter: press the keyboard Enter/search action on editable element "index".
- scroll: scroll "direction" ("down" shows content further down); optional "index" of a scrollable element.
- back, home: system buttons.
- wait: wait for the screen to load.
- done: the goal is finished. Put a short result for the user in "message".
- fail: the goal is impossible or you are stuck. Explain in "message".
Rules: use only indexes from the current screen. If the screen is not what you expect, go back or open the right app first. Never enter passwords, payment details or send money/messages unless the user's goal explicitly says to. Answer "message" in the same language as the user's goal. Be efficient."""

    fun start(ctx: Context, goal: String) {
        if (running) { say("⚠️ في مهمة شغالة، اضغط إيقاف الأول"); return }
        val svc0 = AgentService.instance
        val prefs = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val key = prefs.getString("key", "") ?: ""
        val model = (prefs.getString("model", "gemini-3.8-flash") ?: "gemini-3.8-flash").let { if (it == "gemini-2.5-flash") "gemini-3.8-flash" else it }
        if (key.isBlank()) { say("⚠️ حط مفتاح Gemini الأول من الإعدادات فوق"); return }
        if (svc0 == null) { say("⚠️ فعّل خدمة الوصول (Accessibility) الأول"); return }
        running = true; stop = false
        say("🎯 $goal")
        Thread {
            try { loop(goal, key, model) }
            catch (e: Exception) {
                val m = e.message ?: ""
                say(if (m.contains("HTTP 404")) "❌ اسم الموديل غلط أو مش متاح. غيّره من خانة الموديل فوق (جرب gemini-3.8-flash) واضغط حفظ.\n$m"
                else if (m.contains("HTTP 400") && m.contains("API key")) "❌ مفتاح Gemini غلط. راجعه واضغط حفظ.\n$m"
                else "❌ $m")
            }
            finally { running = false; onFinished?.invoke() }
        }.start()
    }

    private fun loop(goal: String, key: String, model: String) {
        val history = ArrayList<String>()
        for (step in 1..MAX_STEPS) {
            if (stop) { say("⏹ اتوقف"); return }
            val svc = AgentService.instance
            if (svc == null) { say("⚠️ خدمة الوصول اتقفلت"); return }
            val screen = svc.describeScreen()
            val prompt = "GOAL: $goal\n\nSTEPS SO FAR:\n" +
                (if (history.isEmpty()) "(none)" else history.joinToString("\n")) +
                "\n\nCURRENT SCREEN:\n$screen"
            val raw = Gemini.generate(key, model, SYSTEM, prompt)
            val j = try { JSONObject(raw.trim().removePrefix("```json").removeSuffix("```").trim()) }
            catch (e: Exception) { history.add("(model reply was not valid JSON)"); continue }
            val action = j.optString("action")
            val idx = if (j.has("index")) j.optInt("index", -1) else -1
            val text = j.optString("text")
            val thought = j.optString("thought")
            if (stop) { say("⏹ اتوقف"); return }
            var result = "ok"
            when (action) {
                "done" -> { say("✅ " + j.optString("message", "تم")); return }
                "fail" -> { say("🛑 " + j.optString("message", "مقدرتش")); return }
                "open_app" -> {
                    val name = svc.openApp(j.optString("app"))
                    result = if (name == null) "app not found" else "opened $name"
                }
                "tap" -> result = if (svc.tap(idx)) "ok" else "failed"
                "long_press" -> result = if (svc.longPress(idx)) "ok" else "failed"
                "type" -> result = if (svc.type(idx, text)) "ok" else "failed"
                "enter" -> result = if (svc.enter(idx)) "ok" else "failed"
                "scroll" -> result = if (svc.scroll(if (idx >= 0) idx else null, j.optString("direction") != "up")) "ok" else "failed"
                "back" -> svc.back()
                "home" -> svc.home()
                "wait" -> {}
                else -> result = "unknown action"
            }
            val desc = "$action" + (if (idx >= 0) " #$idx" else "") +
                (if (text.isNotEmpty()) " \"$text\"" else "") +
                (if (action == "open_app") " ${j.optString("app")}" else "")
            say("$step. $desc → $result" + (if (thought.isNotBlank()) "\n   $thought" else ""))
            history.add("$step. $desc → $result")
            Thread.sleep(if (action == "open_app") 2000 else 1200)
        }
        say("⏱ وصلت للحد الأقصى ($MAX_STEPS خطوة) ووقفت")
    }
}
