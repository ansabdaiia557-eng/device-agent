package com.anas.deviceagent

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Any OpenAI-compatible /chat/completions endpoint (Groq, Cerebras, OpenRouter, ...). */
object OpenAiCompat {
    @Volatile private var current: HttpURLConnection? = null
    fun cancel() { try { current?.disconnect() } catch (e: Exception) {} }

    fun generate(baseUrl: String, apiKey: String, model: String, system: String, user: String): String {
        val url = baseUrl.trim().trimEnd('/') + "/chat/completions"
        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0.2)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", system))
                .put(JSONObject().put("role", "user").put("content", user)))
        val conn = URL(url).openConnection() as HttpURLConnection
        current = conn
        conn.requestMethod = "POST"
        conn.connectTimeout = 20000
        conn.readTimeout = 45000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw RuntimeException("Provider HTTP $code: ${text.take(300)}")
        return JSONObject(text).getJSONArray("choices").getJSONObject(0)
            .getJSONObject("message").getString("content")
    }

    fun describe(baseUrl: String, apiKey: String, model: String, prompt: String, jpegB64: String): String {
        val url = baseUrl.trim().trimEnd('/') + "/chat/completions"
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
            .put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", "data:image/jpeg;base64,$jpegB64")))
        val body = JSONObject().put("model", model).put("temperature", 0.2).put("max_tokens", 700)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        val conn = URL(url).openConnection() as HttpURLConnection
        current = conn
        conn.requestMethod = "POST"
        conn.connectTimeout = 20000
        conn.readTimeout = 60000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $apiKey")
        conn.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = conn.responseCode
        val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
            ?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw RuntimeException("Vision HTTP $code: ${text.take(300)}")
        return JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message").getString("content")
    }
}
