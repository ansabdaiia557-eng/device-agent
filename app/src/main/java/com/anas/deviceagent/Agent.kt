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
{"thought": "short", "action": "shell|script|set_wallpaper|wallpaper_base|set_wallpaper_style|reset_wallpaper_style|now_playing|done|fail", "path": "", "which": "home|lock|both", "dim": 0.0, "blur": 0.0, "tint": "#rrggbb", "caption": "", "command": "...", "commands": ["cmd1","cmd2"], "message": "..."}
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
Images: if the goal contains ATTACHED IMAGE, it was already analysed for you (description in the goal) and saved at the given path. Use that path for file commands (cp/mv to another folder, etc.).
Wallpaper: action "set_wallpaper" changes the real wallpaper invisibly. Optional "path" (a /sdcard image; default = the attached image), "which" home|lock|both, "dim" 0-0.8, "blur" 0-1, "tint" hex colour, "caption" short text drawn at the bottom (e.g. song title). Pick dim/blur/tint/caption to match what the user asked, e.g. tint to match the song mood.
Live music wallpaper (moves with the beat): action "wallpaper_base" (optional "path", default = attached image) makes the image the base of the live wallpaper app. Use it when the user wants a wallpaper that moves/reacts to music. "set_wallpaper" is the static one.
Wallpaper style: when the user describes how the live wallpaper should look/animate (waves, particles, lights, flicker, shake, colours, intensity, where effects sit), use action "set_wallpaper_style" (the app reads the user's full message itself, you may leave "text" empty). "reset_wallpaper_style" returns to the default cinematic style. Do not use shell for this.
Music: action "now_playing" returns the current track info (title/artist/state/app). Use it when the user mentions the current song.
Actions: shell ("command"), script ("commands"), set_wallpaper, now_playing, done ("message" = short result for the user), fail ("message" = why).
Rules: Never enter passwords or payment details, never send money or messages unless the goal says so. Answer "message" in the user's language. Be efficient, few steps."""

    fun start(ctx: Context, goal0: String, imgLocal: String? = null, imgShell: String? = null) {
        var goal = goal0
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
        say("🎯 $goal0" + (if (imgLocal != null) "\n🖼 مع صورة" else "") + "\n⏳ بفكر...")
        appCtx = ctx.applicationContext
        currentGoal = goal0
        lastImage = imgLocal ?: lastImage
        val t = Thread {
            try {
                if (imgLocal != null) {
                    say("👁 بحلل الصورة...")
                    val desc = describeImage(ctx.applicationContext, imgLocal, goal0)
                    goal += "\n\nATTACHED IMAGE" + (if (imgShell != null) " (saved on the phone at $imgShell)" else "") +
                        " — vision analysis:\n" + desc
                }
                loop(goal)
            }
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

    private fun generate(prompt: String, system: String = SYSTEM): String {
        var attempt = 0
        while (true) {
            val pv = provs[pi]
            try {
                return OpenAiCompat.generate(pv.base, pv.key, pv.model, system, prompt)
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

    @Volatile private var appCtx: Context? = null
    @Volatile private var lastImage: String? = null
    @Volatile private var currentGoal: String = ""

    private fun defaultVision(base: String) = if (base.contains("openrouter")) "meta-llama/llama-4-scout" else "meta-llama/llama-4-scout-17b-16e-instruct"

    private fun describeImage(ctx: Context, path: String, goal: String): String {
        val bytes = java.io.File(path).readBytes()
        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        val prefs = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val prompt = "The user asked their phone assistant: \"$goal\"\nDescribe this image for the assistant: main subjects, setting, mood, 3 dominant colors as hex, and transcribe any visible text exactly. If the request asks a question about the image, answer it. Max 200 words."
        var last = ""
        for ((i, pv) in provs.withIndex()) {
            val key = if (pv === provs[0] && provs[0].base == prefs.getString("base2", "")?.trim()) "vmodel2" else "vmodel3"
            val vm = (prefs.getString(key, "") ?: "").trim().ifEmpty { defaultVision(pv.base) }
            try { return OpenAiCompat.describe(pv.base, pv.key, vm, prompt, b64) }
            catch (e: Exception) { if (stop) throw e; last = e.message ?: ""; say("⚠️ تحليل الصورة فشل مع ${vm}") }
        }
        return "(image analysis failed: ${last.take(150)}. Tell the user vision needs a vision model, set it in settings.)"
    }

    private fun wallpaperBase(j: JSONObject): String {
        val ctx = appCtx ?: return "error: no context"
        val path = j.optString("path").trim()
        val bytes = try {
            if (path.isNotEmpty()) {
                if (!ShellPolicy.pathOk(path)) return "error: path must be inside /sdcard"
                Sh.readBytes(path)
            } else java.io.File(lastImage ?: return "error: no image attached and no path given").readBytes()
        } catch (e: Throwable) { return "error: ${e.message}" }
        return try {
            val bmp = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return "error: not a valid image"
            val s = Math.min(1f, 2000f / Math.max(bmp.width, bmp.height))
            val out = if (s < 1f) android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true) else bmp
            java.io.File(ctx.filesDir, "wp_base.jpg").outputStream().use { out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
            "ok: image is now the base of the music wallpaper. If the live wallpaper is not active yet, tell the user to tap the Wallpaper tab and activate it once (Android requires the user to pick it)."
        } catch (e: Throwable) { "error: ${e.message}" }
    }

    private fun setWallpaperStyle(j: JSONObject): String {
        val ctx = appCtx ?: return "error: no context"
        val prefs = ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE)
        val cur = WStyle.load(prefs)
        val text = (currentGoal + "\n" + j.optString("text")).trim()
        val raw = generate("CURRENT PARAMETERS:\n" + cur.toJson().toString() + "\n\nUSER STYLE DESCRIPTION:\n" + text.take(3500), WStyle.SCHEMA)
        val obj = try { JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1)) } catch (e: Exception) { return "error: could not parse style, ask the user to rephrase" }
        val changed = cur.merge(obj)
        cur.save(prefs)
        return "ok: wallpaper style updated (${changed.joinToString(", ")}). Live wallpaper applies it next time the screen turns on."
    }

    private fun resetWallpaperStyle(): String {
        val ctx = appCtx ?: return "error: no context"
        WStyle.reset(ctx.getSharedPreferences("prefs", Context.MODE_PRIVATE))
        return "ok: wallpaper style reset to the default cinematic style"
    }

    private fun nowPlaying(): String {
        if (!Sh.granted()) return "error: Shizuku not ready"
        val raw = try { Sh.exec(listOf("dumpsys", "media_session"), 15, 60000) } catch (e: Throwable) { return "error: ${e.message}" }
        val lines = raw.lines().map { it.trim() }.filter { it.startsWith("metadata:") || it.startsWith("state=PlaybackState") || it.startsWith("package=") }
        return if (lines.isEmpty()) "nothing found playing" else lines.take(6).joinToString("\n").take(600)
    }

    private fun setWallpaper(j: JSONObject): String {
        val ctx = appCtx ?: return "error: no context"
        val path = j.optString("path").trim()
        val bytes = try {
            if (path.isNotEmpty()) {
                if (!ShellPolicy.pathOk(path)) return "error: path must be inside /sdcard"
                Sh.readBytes(path)
            } else java.io.File(lastImage ?: return "error: no image attached and no path given").readBytes()
        } catch (e: Throwable) { return "error: ${e.message}" }
        return Wallpaper.apply(ctx, bytes, j.optString("which", "both"), j.optDouble("dim", 0.0).toFloat(),
            j.optDouble("blur", 0.0).toFloat(), j.optString("tint"), j.optString("caption"))
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
            if (action != "shell" && action != "script" && action != "set_wallpaper" && action != "now_playing" && action != "wallpaper_base" && action != "set_wallpaper_style" && action != "reset_wallpaper_style") { history.add("$step. $action → not possible: only shell commands exist. Use shell or answer fail"); say("🚫 مش قادر أعمل ده من غير الشاشة"); continue }
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
            } else if (action == "set_wallpaper") {
                result = setWallpaper(j); desc = "set_wallpaper"
            } else if (action == "wallpaper_base") {
                result = wallpaperBase(j); desc = "wallpaper_base"
            } else if (action == "set_wallpaper_style") {
                result = setWallpaperStyle(j); desc = "set_wallpaper_style"
            } else if (action == "reset_wallpaper_style") {
                result = resetWallpaperStyle(); desc = "reset_wallpaper_style"
            } else if (action == "now_playing") {
                result = nowPlaying(); desc = "now_playing"
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
