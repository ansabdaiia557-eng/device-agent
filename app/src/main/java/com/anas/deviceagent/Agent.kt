package com.anas.deviceagent

import android.content.Context
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object Agent {
    private const val MAX_STEPS = 30

    val log = ArrayList<String>()
    @Volatile var listener: ((String) -> Unit)? = null
    @Volatile var running = false
    @Volatile private var stop = false
    @Volatile var onFinished: (() -> Unit)? = null
    @Volatile var vdW = 720
    @Volatile var vdH = 1600

    private class Prov(val base: String, val model: String, val key: String) {
        fun ok() = base.isNotBlank() && model.isNotBlank() && key.isNotBlank()
    }
    @Volatile private var provs: List<Prov> = emptyList()
    @Volatile private var pi = 0
    @Volatile private var hiddenEnabled = true
    @Volatile private var mode = 0 // 0 no UI yet, 1 hidden virtual display, 2 black overlay on the real screen
    @Volatile private var vdId = -1
    @Volatile private var helper: Process? = null
    @Volatile private var thread: Thread? = null

    private fun say(s: String) {
        synchronized(log) { log.add(s) }
        listener?.invoke(s)
    }

    fun stop() {
        stop = true
        OpenAiCompat.cancel()
        thread?.interrupt()
    }

    private const val SYSTEM = """You control an Android phone for the user. Reply with ONE JSON object only:
{"thought": "short", "action": "...", "command": "...", "index": number, "text": "...", "direction": "up|down", "app": "...", "message": "..."}
PREFER "shell": it runs invisibly in the background as the Android shell user (screen does not move). Put one command in "command" (no pipes, no redirects, no && or ;). Examples:
- airplane mode: cmd connectivity airplane-mode enable|disable
- Wi-Fi: svc wifi enable|disable   (if that fails: cmd wifi set-wifi-enabled enabled|disabled)
- mobile data: svc data enable|disable
- Bluetooth: cmd bluetooth_manager enable|disable
- brightness: settings put system screen_brightness_mode 0 ; then settings put system screen_brightness 0..255 (two steps)
- volume: cmd media_session volume --stream 3 --set 0..15   (3 music, 2 ring, 4 alarm)
- do not disturb: cmd notification set_dnd on|off
- auto-rotate: settings put system accelerometer_rotation 0|1
- dark mode: cmd uimode night yes|no
- location: cmd location set-location-enabled true|false
- alarm without UI: am start -a android.intent.action.SET_ALARM --ei android.intent.extra.alarm.HOUR 7 --ei android.intent.extra.alarm.MINUTES 30 --ez android.intent.extra.alarm.SKIP_UI true
- list user apps: pm list packages -3 ; find an app: pm list packages
- media keys (play/pause 85, next 87, previous 88): input keyevent 85
- read state: settings get ... , dumpsys battery, getprop ...
Only a few services are allowed; blocked commands return an error, then try another way.
Use UI actions only when no shell command can do the job (they run on a hidden screen when possible):
- open_app ("app": name or package), tap (index), long_press (index), type (index, text), enter (index), scroll (direction, optional index), back, home, wait
- done: finished, "message" = short result for the user. fail: impossible or stuck, explain in "message".
Rules: you get the screen list only after a UI action started the UI. Use only indexes from the current screen. Never enter passwords or payment details, never send money or messages unless the goal says so. Answer "message" in the user's language. Be efficient, few steps."""

    fun start(ctx: Context, goal: String) {
        if (running) { say("⚠️ في مهمة شغالة، اضغط إيقاف الأول"); return }
        val prefs = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        fun p(a: String, b: String, c: String) = Prov(
            prefs.getString(a, "")?.trim() ?: "", prefs.getString(b, "")?.trim() ?: "", prefs.getString(c, "")?.trim() ?: "")
        provs = listOf(p("base2", "model2", "key2"), p("base3", "model3", "key3")).filter { it.ok() }
        if (provs.isEmpty()) { say("⚠️ املا خانات المزود (Base URL والموديل والمفتاح) واضغط حفظ"); return }
        pi = 0
        hiddenEnabled = prefs.getBoolean("hidden", true)
        mode = 0; vdId = -1
        running = true; stop = false
        say("🎯 $goal\n⏳ بفكر...")
        val t = Thread {
            try { loop(goal) }
            catch (e: InterruptedException) { say("⏹ اتوقف") }
            catch (e: Throwable) {
                if (stop) { say("⏹ اتوقف"); return@Thread }
                val m = e.message ?: ""
                say(if (m.contains("HTTP 404")) "❌ اسم الموديل أو الـ Base URL غلط. راجعهم واضغط حفظ.\n$m"
                else if (m.contains("HTTP 401")) "❌ المفتاح غلط. راجعه واضغط حفظ.\n$m"
                else "❌ $m")
            }
            finally { teardown(); running = false; stop = false; thread = null; onFinished?.invoke() }
        }
        thread = t
        t.start()
    }

    private fun teardown() {
        try { helper?.outputStream?.close() } catch (e: Exception) {}
        try { helper?.destroy() } catch (e: Exception) {}
        helper = null
        AgentService.instance?.let { it.targetDisplay = 0; it.hideOverlay() }
        mode = 0; vdId = -1
    }

    private fun isTemp(e: Exception): Boolean {
        val m = e.message ?: ""
        return m.contains("HTTP 503") || m.contains("HTTP 429") || m.contains("HTTP 500") ||
            e is java.net.SocketTimeoutException || e is java.net.UnknownHostException ||
            e is java.net.ConnectException || m.contains("timeout", true)
    }

    private fun generate(prompt: String): String {
        var attempt = 0
        while (true) {
            val pv = provs[pi]
            try {
                return OpenAiCompat.generate(pv.base, pv.key, pv.model, SYSTEM, prompt)
            } catch (e: Exception) {
                if (stop) throw e
                attempt++
                val next = pi + 1 < provs.size
                val rate = (e.message ?: "").contains("HTTP 429")
                if (next && (rate || attempt >= 2 || !isTemp(e))) {
                    pi++; attempt = 0
                    say("🔀 المزود التاني شغال دلوقتي (${provs[pi].model})")
                    continue
                }
                if (!isTemp(e) || attempt >= 5) throw e
                val wait = 2L shl (attempt - 1)
                say("🔁 المزود مشغول شوية، هحاول تاني بعد $wait ث ($attempt/5)")
                Thread.sleep(wait * 1000)
            }
        }
    }

    private fun shell(cmd: String): String {
        val tokens = ShellPolicy.tokenize(cmd)
        ShellPolicy.check(tokens)?.let { return "blocked: $it" }
        if (!Sh.granted()) return "Shizuku not authorized/running"
        return try { Sh.exec(tokens) } catch (e: Throwable) { "error: ${e.message}" }
    }

    /** Starts the hidden display, or the black overlay as fallback. */
    private fun ensureUi(): Boolean {
        if (mode != 0) return true
        val svc = AgentService.instance
        if (svc == null) { say("⚠️ خدمة الوصول مش مفعلة، مش هقدر أتعامل مع الشاشة"); return false }
        if (hiddenEnabled && Sh.granted()) {
            val err = startVd(svc)
            if (err == null) {
                mode = 1; svc.targetDisplay = vdId
                say("🫥 شغال على شاشة مخفية (تجريبي) رقم $vdId")
                return true
            }
            say("⚠️ الشاشة المخفية ما اشتغلتش: $err\n⬛ هكمل بشاشة سودا فوق الشاشة الحقيقية")
        } else if (hiddenEnabled) {
            say("ℹ️ Shizuku مش متصل، هستخدم شاشة سودا فوق الشاشة الحقيقية")
        }
        mode = 2; svc.targetDisplay = 0; svc.showOverlay()
        return true
    }

    private fun startVd(svc: AgentService): String? {
        return try {
            val m = svc.resources.displayMetrics
            val w = 720
            var h = Math.round(w.toFloat() * maxOf(m.widthPixels, m.heightPixels) / minOf(m.widthPixels, m.heightPixels))
            if (h % 2 == 1) h++
            vdW = w; vdH = h
            val cmd = listOf("app_process", "-Djava.class.path=" + svc.applicationInfo.sourceDir, "/system/bin",
                "com.anas.deviceagent.VdHelper", "$w", "$h", "320")
            val p = Sh.spawn(cmd)
            helper = p
            val first = arrayOfNulls<String>(1)
            val latch = CountDownLatch(1)
            val errBuf = StringBuilder()
            Thread {
                try {
                    val line = p.inputStream.bufferedReader().readLine()
                    first[0] = line
                } catch (e: Exception) {}
                latch.countDown()
            }.start()
            Thread { try { errBuf.append(p.errorStream.bufferedReader().readText().take(300)) } catch (e: Exception) {} }.start()
            if (!latch.await(20, TimeUnit.SECONDS)) { return "انتهت المهلة" }
            val line = first[0] ?: return "الـ helper وقف. ${errBuf.toString().trim()}"
            if (line.startsWith("DISPLAY ")) { vdId = line.removePrefix("DISPLAY ").trim().toInt(); null }
            else line.take(250)
        } catch (e: Throwable) { e.message ?: e.javaClass.simpleName }
    }

    private fun launchOnDisplay(pkg: String): String {
        return Sh.exec(listOf("am", "start", "--display", vdId.toString(), "-a", "android.intent.action.MAIN",
            "-c", "android.intent.category.LAUNCHER", "-p", pkg))
    }

    private val uiActions = setOf("open_app", "tap", "long_press", "type", "enter", "scroll", "back", "home", "wait")

    private fun loop(goal: String) {
        val history = ArrayList<String>()
        var bad = 0
        var emptyVd = 0
        for (step in 1..MAX_STEPS) {
            if (stop) { say("⏹ اتوقف"); return }
            val svc = AgentService.instance
            val screen = if (mode != 0 && svc != null) svc.describeScreen() else "(UI not started: use shell, or a UI action to start it)"
            if (mode == 1 && screen == "(no windows)") {
                emptyVd++
                if (emptyVd >= 3) {
                    say("⚠️ الشاشة المخفية فاضية، بحوّل للشاشة السودا")
                    try { helper?.outputStream?.close() } catch (e: Exception) {}
                    try { helper?.destroy() } catch (e: Exception) {}
                    helper = null
                    svc?.targetDisplay = 0; svc?.showOverlay(); mode = 2; vdId = -1; emptyVd = 0
                }
            } else emptyVd = 0
            val prompt = "GOAL: $goal\n\nSTEPS SO FAR:\n" +
                (if (history.isEmpty()) "(none)" else history.takeLast(8).joinToString("\n")) +
                "\n\nCURRENT SCREEN:\n$screen"
            val raw = generate(prompt)
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
            if (action == "done") { say("✅ " + j.optString("message", "تم")); return }
            if (action == "fail") { say("🛑 " + j.optString("message", "مقدرتش")); return }
            if (action in uiActions && !ensureUi()) { history.add("$step. $action → UI unavailable"); continue }
            val s = AgentService.instance
            when (action) {
                "shell" -> result = shell(j.optString("command"))
                "open_app" -> {
                    val app = j.optString("app")
                    if (mode == 1) {
                        val f = s?.findApp(app)
                        result = if (f == null) "app not found" else {
                            val r = launchOnDisplay(f.first)
                            "launched ${f.second} on hidden display: $r".take(300)
                        }
                    } else {
                        val name = s?.openApp(app)
                        result = if (name == null) "app not found" else "opened $name"
                    }
                    Thread.sleep(1500)
                }
                "tap" -> result = if (s?.tap(idx) == true) "ok" else "failed"
                "long_press" -> result = if (s?.longPress(idx) == true) "ok" else "failed"
                "type" -> result = if (s?.type(idx, text) == true) "ok" else "failed"
                "enter" -> result = if (s?.enter(idx) == true) "ok" else "failed"
                "scroll" -> result = if (s?.scroll(if (idx >= 0) idx else null, j.optString("direction") != "up") == true) "ok" else "failed"
                "back" -> s?.back()
                "home" -> s?.home()
                "wait" -> {}
                else -> result = "unknown action"
            }
            val cmdTxt = if (action == "shell") " `${j.optString("command")}`" else ""
            val desc = action + cmdTxt + (if (idx >= 0) " #$idx" else "") +
                (if (text.isNotEmpty()) " \"$text\"" else "") +
                (if (action == "open_app") " ${j.optString("app")}" else "")
            say("$step. $desc → ${result.take(160)}" + (if (thought.isNotBlank()) "\n   $thought" else ""))
            history.add("$step. $desc → ${result.take(500)}")
            Thread.sleep(if (action == "shell") 300 else 1000)
        }
        say("⏱ وصلت للحد الأقصى ($MAX_STEPS خطوة) ووقفت")
    }
}
