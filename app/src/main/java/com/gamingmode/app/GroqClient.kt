package com.gamingmode.app

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class RateLimitException(val waitSec: Long, message: String) : RuntimeException(message)

object GroqClient {
    private const val ENDPOINT = "https://api.groq.com/openai/v1/chat/completions"

    // Groq changes its model list often. Both are editable inside the app (AI Corp settings).
    const val DEFAULT_VISION = "qwen/qwen3.8-27b"
    const val DEFAULT_CODER = "openai/gpt-oss-120b"

    fun clean(s: String): String = s.replace(Regex("(?s)<think>.*?</think>"), "").trim()

    private class Resp(val code: Int, val text: String, val retryAfter: Double?)

    private fun post(key: String, body: JSONObject): Resp {
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
        return Resp(code, text, conn.getHeaderField("retry-after")?.toDoubleOrNull())
    }

    private fun waitSeconds(r: Resp): Long {
        if (r.retryAfter != null) return Math.ceil(r.retryAfter).toLong()
        val m = Regex("try again in (?:(\\d+)h)?(?:(\\d+)m)?([\\d.]+)s").find(r.text)
        if (m != null) {
            val h = m.groupValues[1].toLongOrNull() ?: 0L
            val mi = m.groupValues[2].toLongOrNull() ?: 0L
            val s = m.groupValues[3].toDoubleOrNull() ?: 5.0
            return h * 3600 + mi * 60 + Math.ceil(s).toLong()
        }
        return 20L
    }

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

        // Thinking burns lots of tokens and hits rate limits fast, so ask for as little as possible.
        val effort: String? = when {
            model.startsWith("openai/gpt-oss") -> "low"
            model.startsWith("qwen/") -> "none"
            else -> null
        }

        fun body(e: String?): JSONObject {
            val b = JSONObject()
                .put("model", model)
                .put("temperature", 0.2)
                .put("max_tokens", maxTokens)
                .put("messages", msgs)
            if (e != null) b.put("reasoning_effort", e)
            return b
        }

        var r = post(key, body(effort))
        if (r.code == 400 && effort != null && r.text.contains("reason", ignoreCase = true)) {
            r = post(key, body(null))
        }
        if (r.code == 429) {
            val w = waitSeconds(r)
            throw RateLimitException(w, "Rate limit reached for '$model'. Groq says try again in ${w}s. You can also switch the model in the gear settings.")
        }
        if (r.code !in 200..299) throw RuntimeException("HTTP ${r.code}: " + r.text.take(220))
        val msg = JSONObject(r.text).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        return clean(msg.optString("content", ""))
    }
}
