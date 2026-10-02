package com.gamingmode.app

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object GroqClient {
    private const val ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"

    // Groq changes its model list often. Both are editable inside the app (AI Corp settings).
    const val DEFAULT_VISION = "qwen/qwen3.8-27b"
    const val DEFAULT_CODER = "openai/gpt-oss-120b"

    fun clean(s: String): String = s.replace(Regex("(?s)<think>.*?</think>"), "").trim()

    fun chat(
        key: String,
        model: String,
        system: String,
        user: String,
        jpegs: List<ByteArray>,
        maxTokens: Int = 1500
    ): String {
        val content = JSONArray()
        content.put(JSONObject().put("type", "text").put("text", user))
        for (jpeg in jpegs) {
            val url = "data:image/jpeg;base64," + Base64.encodeToString(jpeg, Base64.NO_WRAP)
            content.put(
                JSONObject().put("type", "image_url")
                    .put("image_url", JSONObject().put("url", url))
            )
        }
        val msgs = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", content))
        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0.2)
            .put("max_tokens", maxTokens)
            .put("messages", msgs)

        val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 20000
        conn.readTimeout = 120000
        conn.doOutput = true
        conn.setRequestProperty("Authorization", "Bearer " + key.trim())
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(body.toString().toByteArray()) }

        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) throw RuntimeException("HTTP $code: " + text.take(220))
        val msg = JSONObject(text).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        return clean(msg.optString("content", ""))
    }
}
