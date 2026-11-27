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

    private class Prov(val base: String, val model: String, val key: String) {
        fun ok() = base.isNotBlank() && model.isNotBlank() && key.isNotBlank()
    }
    @Volatile private var provs: List<Prov> = emptyList()
    @Volatile private var pi = 0
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
{"thought": "short", "action": "shell|script|done|fail", "command": "...", "commands": ["cmd1","cmd2"], "message": "..."}
PREFER "shell": it runs invisibly in the background as the Android shell user (screen does not move). Put one command in "command" (no pipes, no redirects, no && or ;). Examples:
- airplane mode: cmd connectivity airplane-mode enable|disable
- Wi-Fi: svc wifi enable|disable   (if that fails: cmd wifi set-wifi-enabled enabled|disabled)
- mobile data: svc data enable|disable   (fallback: cmd phone data enable|disable, or settings put global mobile_data 1|0)
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
If a shell command fails or is blocked, try the fallback or another shell variant (at least 3 different tries) before giving up with "fail". Never open Settings and tap for these.
Only a few services are allowed; blocked commands return an error, then try another way.
There is NO screen control: you cannot tap, type, scroll or open the UI. If the goal has no shell route, answer fail and say it cannot be done in the background.
More: lock screen: input keyevent 223 (sleep) ; wake: input keyevent 224 ; open an app: am start -a android.intent.action.MAIN -c android.intent.category.LAUNCHER -p <package> (this shows the app on screen) ; read notifications: dumpsys notification --noredact (long, only if asked).
Files (only inside /sdcard, no .. or wildcards): ls -l /sdcard/Download ; cat /sdcard/x.txt ; stat ; du -s ; head/tail -n 20 ; cp ; mv ; mkdir -p ; touch ; rm -r /sdcard/dir/sub (asks the user to confirm).
Packages: pm list packages -3 ; pm path <pkg> ; pm install -r /sdcard/x.apk, pm uninstall <pkg>, pm grant|revoke <pkg> <perm> (these ask the user to confirm first; say what you are doing).
Health: dumpsys battery ; dumpsys meminfo ; dumpsys cpuinfo ; cat /proc/meminfo ; ps -A ; top -b -n 1 ; df ; uptime ; getprop ro.build.version.release. Summarise results nicely in "message" (short, user's language).
Several commands in one task: action "script" with "commands": [up to 8 commands, run in order, stops on first error/block].
Actions: shell ("command"), script ("commands"), done ("message" = short result for the user), fail ("message" = why).
Rules: Never enter passwords or payment details, never send money or messages unless the goal says so. Answer "message" in the user's language. Be efficient, few steps."""

    fun start(ctx: Context, goal: String) {
        if (running) { say("⚠️ في مهمة شغالة، اضغط إيقاف الأول"); return }
        val prefs = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        fun p(a: String, b: String, c: String) = Prov(
            prefs.getString(a, "")?.trim() ?: "", prefs.getString(b, "")?.trim() ?: "", prefs.getString(c, "")?.trim() ?: "")
        provs = listOf(p("base2", "model2", "key2"), p("base3", "model3", "key3")).filter { it.ok() }
        if (provs.isEmpty()) { say("⚠️ املا خانات المزود (Base URL والموديل والمفتاح) واضغط حفظ"); return }
        if (!Sh.binderReady()) { say("⚠️ شغّل Shizuku-Next الأول (بيقف بعد إعادة تشغيل الموبايل) وارجع جرّب"); return }
        if (!Sh.granted()) { say("⚠️ دوس \"ربط Shizuku\" وصرّح للتطبيق"); return }
        pi = 0
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

    private fun teardown() {}

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
                if (attempt == 3) say("🔁 المزود مشغول، بحاول تاني...")
                Thread.sleep(wait * 1000)
            }
        }
    }

    @Volatile var confirmer: ((String) -> Boolean)? = null

    /** Runs one command through the policy. Used by the agent and by the terminal screen. */
    fun runShell(cmd: String, maxChars: Int = 1200): String {
        val tokens = ShellPolicy.tokenize(cmd)
        when (val v = ShellPolicy.check(tokens)) {
            is ShellPolicy.V.No -> return "blocked: ${v.why}"
            is ShellPolicy.V.Confirm -> {
                val c = confirmer
                if (c == null || !c(v.why + "\n\n" + cmd)) return "denied: the user did not confirm"
            }
            else -> {}
        }
        if (!Sh.binderReady()) return "error: Shizuku is not running"
        if (!Sh.granted()) return "error: Shizuku not authorized"
        val slow = tokens[0] == "pm" && tokens.getOrNull(1) == "install"
        return try { Sh.exec(tokens, if (slow) 120 else 15, maxChars) } catch (e: Throwable) { "error: ${e.message}" }
    }

    private fun shell(cmd: String): String = runShell(cmd)

    private fun loop(goal: String) {
        val history = ArrayList<String>()
        var bad = 0
        for (step in 1..MAX_STEPS) {
            if (stop) { say("⏹ اتوقف"); return }
            val prompt = "GOAL: $goal\n\nSTEPS SO FAR:\n" +
                (if (history.isEmpty()) "(none)" else history.takeLast(6).joinToString("\n"))
            val raw = generate(prompt)
            val j = try { JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1)) }
            catch (e: Exception) {
                bad++
                if (bad >= 3) { say("❌ الموديل رجّع رد مش مفهوم 3 مرات، وقفت"); return }
                history.add("(model reply was not valid JSON)"); continue
            }
            val action = j.optString("action")
            val thought = j.optString("thought")
            if (stop) { say("⏹ اتوقف"); return }
            if (action == "done") { say("✅ " + j.optString("message", "تم")); return }
            if (action == "fail") { say("🛑 " + j.optString("message", "مقدرتش")); return }
            if (action != "shell" && action != "script") { history.add("$step. $action → not possible: only shell commands exist. Use shell or answer fail"); say("🚫 مش قادر أعمل ده من غير الشاشة"); continue }
            val result: String
            val desc: String
            if (action == "script") {
                val arr = j.optJSONArray("commands")
                val sb = StringBuilder()
                var n = 0
                if (arr != null) while (n < arr.length() && n < 8) {
                    if (stop) break
                    val c = arr.optString(n); n++
                    val r = runShell(c, 600)
                    sb.append("[$n] $c → ${r.take(400)}\n")
                    if (r.startsWith("blocked") || r.startsWith("denied") || r.startsWith("error")) break
                }
                result = sb.toString().trim().ifEmpty { "no commands" }
                desc = "script (${n} commands)"
            } else {
                result = shell(j.optString("command"))
                desc = "shell `${j.optString("command")}`"
            }
            say("$step. $desc → ${result.take(160)}" + (if (thought.isNotBlank()) "\n   $thought" else ""))
            history.add("$step. $desc → ${result.take(700)}")
            Thread.sleep(300)
        }
        say("⏱ وصلت للحد الأقصى ($MAX_STEPS خطوة) ووقفت")
    }
}
