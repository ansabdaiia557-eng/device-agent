package com.anas.deviceagent

/** Limits what may run through Shizuku. Commands run without a shell, so no pipes or redirects. */
object ShellPolicy {
    sealed class V {
        object Ok : V()
        class Confirm(val why: String) : V()
        class No(val why: String) : V()
    }

    fun tokenize(s: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quote: Char? = null
        var has = false
        for (c in s.trim()) {
            if (quote != null) {
                if (c == quote) quote = null else cur.append(c)
            } else if (c == '\'' || c == '"') { quote = c; has = true }
            else if (c.isWhitespace()) {
                if (has || cur.isNotEmpty()) { out.add(cur.toString()); cur.clear(); has = false }
            } else cur.append(c)
        }
        if (has || cur.isNotEmpty()) out.add(cur.toString())
        return out
    }

    private val deniedWords = listOf(
        "wipe", "factory", "reboot", "shutdown", "recovery", "accessibility",
        "adb_", "development_settings", "device_provisioned", "user_setup", "password", "lock_pattern",
        "disable-user", "disable-until", "suspend", "shizuku", "deviceagent", "systemui", "rikka", "format",
        "mkfs", " dd ", "/dev/", "/data/", "/system", "/vendor", "/proc/sys", " su ", "chmod", "chown", "mount")
    private val blockedPerms = listOf("write_secure_settings", "install_packages", "delete_packages", "manage_device_admins",
        "bind_accessibility", "write_settings", "interact_across", "grant_runtime", "bind_device_admin")
    private val protectedPkgPrefixes = listOf("android", "com.android.", "com.google.android.gms", "com.google.android.gsf",
        "com.oplus", "com.coloros", "com.realme", "com.heytap", "com.qualcomm", "com.qti", "com.nearme", "com.oppo")
    private val cmdOk = setOf("connectivity", "bluetooth_manager", "media_session", "notification", "uimode", "location",
        "wifi", "vibrator_manager", "phone")
    private val amOk = setOf("start", "start-activity", "startservice", "start-service", "broadcast", "force-stop", "kill-all")
    private val pmRead = setOf("list", "path", "resolve-activity", "dump")
    private val keysOk = setOf("24", "25", "85", "86", "87", "88", "126", "127", "164", "26", "223", "224",
        "KEYCODE_SLEEP", "KEYCODE_WAKEUP", "KEYCODE_POWER", "KEYCODE_VOLUME_UP", "KEYCODE_VOLUME_DOWN", "KEYCODE_VOLUME_MUTE",
        "KEYCODE_MEDIA_PLAY_PAUSE", "KEYCODE_MEDIA_NEXT", "KEYCODE_MEDIA_PREVIOUS")
    private val settingsOk = mapOf(
        "system" to setOf("screen_brightness", "screen_brightness_mode", "accelerometer_rotation", "user_rotation", "screen_off_timeout",
            "font_scale", "haptic_feedback_enabled", "sound_effects_enabled", "vibrate_when_ringing", "show_touches"),
        "global" to setOf("airplane_mode_on", "mobile_data", "wifi_on", "bluetooth_on", "zen_mode", "auto_time", "auto_time_zone",
            "animator_duration_scale", "fixed_performance_mode_enabled", "window_animation_scale", "transition_animation_scale", "stay_on_while_plugged_in", "low_power"),
        "secure" to setOf("location_mode", "ui_night_mode", "lock_screen_show_notifications"))
    private val procOk = setOf("/proc/meminfo", "/proc/cpuinfo", "/proc/uptime", "/proc/loadavg", "/proc/version")
    private val fileReadHeads = setOf("ls", "cat", "stat", "du", "head", "tail", "wc")
    private val fileWriteHeads = setOf("cp", "mv", "mkdir", "touch")

    fun pathOk(p: String): Boolean {
        if (p.contains("..") || p.contains("*") || p.contains("?") || p.contains("~") || p.contains("$")) return false
        val q = p.trimEnd('/')
        return q == "/sdcard" || q.startsWith("/sdcard/") || q == "/storage/emulated/0" || q.startsWith("/storage/emulated/0/")
    }
    private fun deep(p: String): Boolean {
        val q = p.trimEnd('/')
        return pathOk(p) && q != "/sdcard" && q != "/storage/emulated/0" && q != "/sdcard/Android" && q != "/storage/emulated/0/Android"
    }
    private fun operands(args: List<String>) = args.drop(1).filter { !it.startsWith("-") && !it.all { c -> c.isDigit() } }

    fun check(args: List<String>): V {
        if (args.isEmpty()) return V.No("empty command")
        val joined = " " + args.joinToString(" ").lowercase() + " "
        deniedWords.firstOrNull { joined.contains(it) }?.let { return V.No("blocked word: ${it.trim()}") }
        val head = args[0]
        val sub = args.getOrNull(1) ?: ""
        fun no(s: String) = V.No(s)
        return when (head) {
            "settings" -> when (sub) {
                "get", "list" -> V.Ok
                "put" -> if (args.size == 5 && settingsOk[args[2]]?.contains(args[3]) == true) V.Ok
                    else no("settings put: key not allowed. Allowed: " + settingsOk.entries.joinToString("; ") { it.key + " " + it.value.joinToString(",") })
                else -> no("settings: only get/put/list")
            }
            "svc", "dumpsys", "getprop", "monkey", "uptime", "date", "id", "whoami", "df" -> V.Ok
            "ps" -> V.Ok
            "top" -> if (args.contains("-n") && args.contains("-b")) V.Ok else no("top: use top -b -n 1")
            "cmd" -> if (sub in cmdOk) V.Ok else if (sub == "power" && args.getOrNull(2) == "set-fixed-performance-mode-enabled" && args.size == 4 && (args[3] == "true" || args[3] == "false")) V.Ok else no("cmd: service not allowed ($sub)")
            "am" -> if (sub in amOk) V.Ok else no("am: subcommand not allowed ($sub)")
            "pm" -> when {
                sub in pmRead -> V.Ok
                sub == "install" -> {
                    val f = args.last()
                    if (args.size >= 3 && pathOk(f) && f.lowercase().endsWith(".apk")) V.Confirm("تثبيت تطبيق: $f") else no("pm install: path must be an .apk inside /sdcard")
                }
                sub == "uninstall" -> {
                    val pkg = args.last()
                    if (args.size < 3) no("pm uninstall: package name needed")
                    else if (protectedPkgPrefixes.any { pkg.lowercase().startsWith(it) }) no("system package is protected: $pkg")
                    else V.Confirm("حذف التطبيق: $pkg")
                }
                sub == "grant" || sub == "revoke" -> {
                    if (args.size < 4) no("pm $sub <package> <permission>")
                    else if (blockedPerms.any { joined.contains(it) }) no("this permission is protected")
                    else if (protectedPkgPrefixes.any { args[2].lowercase().startsWith(it) }) no("system package is protected")
                    else V.Confirm((if (sub == "grant") "منح" else "سحب") + " صلاحية ${args[3]} للتطبيق ${args[2]}")
                }
                else -> no("pm: allowed list/path/resolve-activity/dump/install/uninstall/grant/revoke")
            }
            "content" -> if (sub == "query") V.Ok else no("content: only query")
            "input" -> if (sub == "keyevent" && args.drop(2).isNotEmpty() && args.drop(2).all { it in keysOk }) V.Ok
                else no("input: only volume, media, power/sleep/wakeup keyevents")
            in fileReadHeads -> {
                val ops = operands(args)
                if (head == "cat" && ops.isNotEmpty() && ops.all { it in procOk || pathOk(it) }) V.Ok
                else if (ops.isNotEmpty() && ops.all { pathOk(it) }) V.Ok
                else no("$head: paths must be inside /sdcard (no .. or wildcards)")
            }
            in fileWriteHeads -> {
                val ops = operands(args)
                if (ops.isNotEmpty() && ops.all { deep(it) }) V.Ok else no("$head: paths must be inside /sdcard subfolders")
            }
            "rm" -> {
                val ops = operands(args)
                if (ops.isNotEmpty() && ops.all { deep(it) }) V.Confirm("حذف ملفات: " + ops.joinToString(" ")) else no("rm: only paths deeper than /sdcard")
            }
            else -> no("command not allowed: $head")
        }
    }
}
