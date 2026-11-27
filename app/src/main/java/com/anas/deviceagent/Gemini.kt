package com.anas.deviceagent

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object Gemini {
    @Volatile private var current: HttpURLConnection? = null
    fun cancel() { try { current?.disconnect() } catch (e: Exception) {} }

    fun generate(apiKey: String, model: String, system: String, user: String): String {
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", user)))))
            .put("generationConfig", JSONObject()
                .put("temperature", 0.2)
                .put("responseMimeType", "application/json"))
        val conn = URL("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent")
            .openConnection() as HttpURLConnection
        current = conn
        conn.requestMethod = "POST"
        conn.connectTimeout = 20000
        conn.readTimeout = 45000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("x-goog-api-key", apiKey)
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = try { conn.responseCode } finally { }
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw RuntimeException("Gemini HTTP $code: ${text.take(300)}")
        val parts = JSONObject(text).getJSONArray("candidates").getJSONObject(0)
            .getJSONObject("content").getJSONArray("parts")
        val sb = StringBuilder()
        for (i in 0 until parts.length()) sb.append(parts.getJSONObject(i).optString("text"))
        return sb.toString()
    }
}
