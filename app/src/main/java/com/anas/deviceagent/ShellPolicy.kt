package com.anas.deviceagent

/** Limits what the model may run through Shizuku. Commands run without a shell, so no pipes or redirects. */
object ShellPolicy {
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
        "uninstall", "install", "wipe", "factory", "reboot", "shutdown", "recovery", "accessibility",
        "adb_", "development_settings", "device_provisioned", "user_setup", "password", "lock_pattern",
        "disable-user", "disable-until", "suspend", "shizuku", "deviceagent", "systemui", "rikka", "delete", "format")
    private val cmdOk = setOf("connectivity", "bluetooth_manager", "media_session", "notification", "uimode", "location", "wifi", "vibrator_manager", "phone")
    private val amOk = setOf("start", "start-activity", "startservice", "start-service", "broadcast", "force-stop")
    private val pmOk = setOf("list", "path", "resolve-activity")
    private val keysOk = setOf("24", "25", "85", "86", "87", "88", "126", "127", "164", "26", "223", "224", "KEYCODE_SLEEP", "KEYCODE_WAKEUP", "KEYCODE_POWER", "KEYCODE_VOLUME_UP", "KEYCODE_VOLUME_DOWN", "KEYCODE_VOLUME_MUTE", "KEYCODE_MEDIA_PLAY_PAUSE", "KEYCODE_MEDIA_NEXT", "KEYCODE_MEDIA_PREVIOUS")
    private val settingsOk = mapOf(
        "system" to setOf("screen_brightness", "screen_brightness_mode", "accelerometer_rotation", "user_rotation", "screen_off_timeout",
            "font_scale", "haptic_feedback_enabled", "sound_effects_enabled", "vibrate_when_ringing", "show_touches"),
        "global" to setOf("airplane_mode_on", "mobile_data", "wifi_on", "bluetooth_on", "zen_mode", "auto_time", "auto_time_zone",
            "animator_duration_scale", "window_animation_scale", "transition_animation_scale", "stay_on_while_plugged_in", "low_power"),
        "secure" to setOf("location_mode", "ui_night_mode"))

    /** Returns an error text, or null when the command may run. */
    fun check(args: List<String>): String? {
        if (args.isEmpty()) return "empty command"
        val joined = args.joinToString(" ").lowercase()
        deniedWords.firstOrNull { joined.contains(it) }?.let { return "blocked word: $it" }
        val head = args[0]
        val sub = args.getOrNull(1) ?: ""
        return when (head) {
            "settings" -> when (sub) {
                "get", "list" -> null
                "put" -> if (args.size == 5 && settingsOk[args[2]]?.contains(args[3]) == true) null
                    else "settings put: key not allowed. Allowed: " + settingsOk.entries.joinToString("; ") { it.key + " " + it.value.joinToString(",") }
                else -> "settings: only get/put/list"
            }
            "svc", "dumpsys", "getprop", "monkey" -> null
            "cmd" -> if (sub in cmdOk) null else "cmd: service not allowed ($sub)"
            "am" -> if (sub in amOk) null else "am: subcommand not allowed ($sub)"
            "pm" -> if (sub in pmOk) null else "pm: only list/path/resolve-activity"
            "content" -> if (sub == "query") null else "content: only query"
            "input" -> if (sub == "keyevent" && args.drop(2).all { it in keysOk }) null
                else "input: only volume, media, power/sleep/wakeup keyevents"
            else -> "command not allowed: $head"
        }
    }
}
