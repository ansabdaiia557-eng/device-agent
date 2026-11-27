package com.anas.deviceagent

import android.content.SharedPreferences
import android.graphics.Color
import org.json.JSONObject

/** Parameters of the music wallpaper. The default is the cinematic "sound waves coming to life" style. */
class WStyle {
    var waves = true
    var waveOrigin = "speakers"   // speakers (two origins left/right of the anchor) | center
    var waveSize = 1f             // 0.4..2.0
    var waveSpeed = 1f            // 0.5..2.0
    var waveColor = "art"         // "art" (album colour) or #rrggbb
    var vibration = true          // soft speaker vibration on bass
    var particles = true
    var particleCount = 36        // 0..80
    var particleColor = "#ffffff"
    var lights = true
    var lightCount = 5            // 0..12
    var lightColor = "art"
    var flicker = true
    var flickerStrength = 0.5f    // 0..1
    var shake = true              // short cinematic shake + ghost distortion on heavy bass hits
    var shakeStrength = 0.5f      // 0..1
    var intensity = 1f            // 0.3..1.6
    var ax = 0.51f                // anchor (speakers), fraction of width
    var ay = 0.485f               // fraction of height

    fun toJson(): JSONObject = JSONObject()
        .put("waves", waves).put("wave_origin", waveOrigin).put("wave_size", waveSize.toDouble()).put("wave_speed", waveSpeed.toDouble())
        .put("wave_color", waveColor).put("vibration", vibration).put("particles", particles).put("particle_count", particleCount)
        .put("particle_color", particleColor).put("lights", lights).put("light_count", lightCount).put("light_color", lightColor)
        .put("flicker", flicker).put("flicker_strength", flickerStrength.toDouble()).put("shake", shake)
        .put("shake_strength", shakeStrength.toDouble()).put("intensity", intensity.toDouble())
        .put("anchor_x", ax.toDouble()).put("anchor_y", ay.toDouble())

    /** Merges the keys present in j, clamping every value. Returns a short list of what changed. */
    fun merge(j: JSONObject): List<String> {
        val ch = ArrayList<String>()
        fun f(k: String, lo: Float, hi: Float, set: (Float) -> Unit) { if (j.has(k)) { set(j.optDouble(k).toFloat().coerceIn(lo, hi)); ch.add(k) } }
        fun b(k: String, set: (Boolean) -> Unit) { if (j.has(k)) { set(j.optBoolean(k)); ch.add(k) } }
        fun col(k: String, set: (String) -> Unit) {
            if (!j.has(k)) return
            val s = j.optString(k).trim()
            if (s == "art" || (try { Color.parseColor(s); true } catch (e: Exception) { false })) { set(s); ch.add(k) }
        }
        b("waves") { waves = it }
        if (j.has("wave_origin")) { val o = j.optString("wave_origin"); if (o == "speakers" || o == "center") { waveOrigin = o; ch.add("wave_origin") } }
        f("wave_size", 0.4f, 2f) { waveSize = it }; f("wave_speed", 0.5f, 2f) { waveSpeed = it }
        col("wave_color") { waveColor = it }
        b("vibration") { vibration = it }
        b("particles") { particles = it }
        if (j.has("particle_count")) { particleCount = j.optInt("particle_count").coerceIn(0, 80); ch.add("particle_count") }
        col("particle_color") { particleColor = it }
        b("lights") { lights = it }
        if (j.has("light_count")) { lightCount = j.optInt("light_count").coerceIn(0, 12); ch.add("light_count") }
        col("light_color") { lightColor = it }
        b("flicker") { flicker = it }; f("flicker_strength", 0f, 1f) { flickerStrength = it }
        b("shake") { shake = it }; f("shake_strength", 0f, 1f) { shakeStrength = it }
        f("intensity", 0.3f, 1.6f) { intensity = it }
        f("anchor_x", 0f, 1f) { ax = it }; f("anchor_y", 0f, 1f) { ay = it }
        return ch
    }

    fun save(p: SharedPreferences) { p.edit().putString("wp_style", toJson().toString()).apply() }

    companion object {
        fun load(p: SharedPreferences): WStyle {
            val s = WStyle()
            try { p.getString("wp_style", null)?.let { s.merge(JSONObject(it)) } } catch (e: Exception) {}
            return s
        }
        fun reset(p: SharedPreferences) { p.edit().remove("wp_style").apply() }
        fun colorOf(spec: String, art: Int): Int = if (spec == "art") art else try { Color.parseColor(spec) } catch (e: Exception) { art }

        const val SCHEMA = """You translate a user's natural-language description of a live music wallpaper into JSON parameters. Reply with ONE JSON object containing ONLY the keys that should change (omit the rest). Keys:
waves (bool), wave_origin ("speakers" = two wave sources left and right of the anchor, "center"), wave_size (0.4-2), wave_speed (0.5-2), wave_color ("art" = album colour, or "#rrggbb"),
vibration (bool: soft speaker vibration on bass), particles (bool), particle_count (0-80), particle_color ("#rrggbb"),
lights (bool: small pulsing light dots), light_count (0-12), light_color ("art" or "#rrggbb"),
flicker (bool: lighting flicker on strong beats), flicker_strength (0-1), shake (bool: brief shake and subtle distortion on heavy bass), shake_strength (0-1),
intensity (0.3-1.6 overall), anchor_x (0-1, horizontal position of the sound source in the image), anchor_y (0-1 vertical, from the top).
Bass drives waves/vibration, mids drive particles, highs drive lights. The base image is never changed. Phrases like "calmer/subtle" lower intensity and strengths, "stronger/crazy" raise them. "Remove X" sets X's bool to false. Only anchor_x/anchor_y change where effects sit, and only if the user says where."""
    }
}
