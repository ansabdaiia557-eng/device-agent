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

    private class Fallback(val base: String, val model: String, val key: String) {
        fun usable() = base.isNotBlank() && model.isNotBlank() && key.isNotBlank()
    }
    @Volatile private var fallback = Fallback("", "", "")
    @Volatile private var useFallback = false
    @Volatile private var thread: Thread? = null
    fun stop() {
        stop = true
        OpenAiCompat.cancel()
        thread?.interrupt()
    }

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
        fallback = Fallback(
            prefs.getString("base2", "")?.trim() ?: "",
            prefs.getString("model2", "")?.trim() ?: "",
            prefs.getString("key2", "")?.trim() ?: "")
        val key = ""; val model = ""
        if (!fallback.usable()) { say("⚠️ املا خانات المزود (Base URL والموديل والمفتاح) واضغط حفظ"); return }
        if (svc0 == null) { say("⚠️ فعّل خدمة الوصول (Accessibility) الأول"); return }
        running = true; stop = false; useFallback = false
        say("🎯 $goal\n⏳ بفكر...")
        val t = Thread {
            try { loop(goal, key, model) }
            catch (e: InterruptedException) { say("⏹ اتوقف") }
            catch (e: Throwable) {
                if (stop) { say("⏹ اتوقف"); return@Thread }
                val m = e.message ?: ""
                say(if (m.contains("HTTP 404")) "❌ اسم الموديل أو الـ Base URL غلط. راجعهم واضغط حفظ.\n$m"
                else if (m.contains("HTTP 401")) "❌ المفتاح غلط. راجعه واضغط حفظ.\n$m"
                else "❌ $m")
            }
            finally { running = false; stop = false; thread = null; onFinished?.invoke() }
        }
        thread = t
        t.start()
    }

    private fun isTemp(e: Exception): Boolean {
        val m = e.message ?: ""
        return m.contains("HTTP 503") || m.contains("HTTP 429") || m.contains("HTTP 500") ||
            e is java.net.SocketTimeoutException || e is java.net.UnknownHostException ||
            e is java.net.ConnectException || m.contains("timeout", true)
    }

    private fun generateWithRetry(key: String, model: String, prompt: String): String {
        var attempt = 0
        while (true) {
            try {
                return OpenAiCompat.generate(fallback.base, fallback.key, fallback.model, SYSTEM, prompt)
            } catch (e: Exception) {
                if (stop) throw e
                attempt++
                if (!isTemp(e) || attempt >= 5) throw e
                val wait = 2L shl (attempt - 1)
                say("🔁 المزود مشغول شوية، هحاول تاني بعد ${wait} ث ($attempt/5)")
                Thread.sleep(wait * 1000)
            }
        }
    }

    private fun loop(goal: String, key: String, model: String) {
        val history = ArrayList<String>()
        var bad = 0
        for (step in 1..MAX_STEPS) {
            if (stop) { say("⏹ اتوقف"); return }
            val svc = AgentService.instance
            if (svc == null) { say("⚠️ خدمة الوصول اتقفلت"); return }
            val screen = svc.describeScreen()
            val prompt = "GOAL: $goal\n\nSTEPS SO FAR:\n" +
                (if (history.isEmpty()) "(none)" else history.joinToString("\n")) +
                "\n\nCURRENT SCREEN:\n$screen"
            val raw = generateWithRetry(key, model, prompt)
            val j = try { JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1)) }
            catch (e: Exception) {
                bad++
                if (bad >= 3) { say("❌ الموديل رجّع رد مش مفهوم 3 مرات، وقفت"); return }
                history.add("(model reply was not valid JSON)"); continue
            }
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
