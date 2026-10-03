package com.gamingmode.app

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class RateLimitException(val waitSec: Long, message: String) : RuntimeException(message)

object GroqClient {
    private const val GROQ = "https://api.groq.com/openai/v1/chat/completions"
    private const val OPENROUTER = "https://openrouter.ai/api/v1/chat/completions"
    private const val GEMINI = "https://generativelanguage.googleapis.com/v1beta/openai/chat/completions"
    private const val ANTHROPIC = "https://api.anthropic.com/v1/messages"

    // Comma separated lists: when one model hits its limit the app switches to the next one and carries on.
    // Prefixes choose the provider:  (none) = Groq   g: = Gemini   a: = Claude   or: = OpenRouter
    const val DEFAULT_VISION = "qwen/qwen3.8-27b, qwen/qwen3.6-27b"
    const val DEFAULT_CODER = "openai/gpt-oss-120b, openai/gpt-oss-20b, llama-3.3-70b-versatile"

    fun clean(s: String): String = s.replace(Regex("(?s)<think>.*?</think>"), "").trim()

    private class Resp(val code: Int, val text: String, val retryAfter: Double?)

    private fun post(endpoint: String, headers: Map<String, String>, body: JSONObject): Resp {
        val conn = URL(endpoint).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 20000
        conn.readTimeout = 120000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        for ((k, v) in headers) conn.setRequestProperty(k, v)
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

    private fun b64(j: ByteArray) = Base64.encodeToString(j, Base64.NO_WRAP)

    private fun claude(key: String, model: String, system: String, user: String, jpegs: List<ByteArray>, maxTokens: Int): String {
        val content = JSONArray()
        for (j in jpegs) {
            content.put(
                JSONObject().put("type", "image").put(
                    "source",
                    JSONObject().put("type", "base64").put("media_type", "image/jpeg").put("data", b64(j))
                )
            )
        }
        content.put(JSONObject().put("type", "text").put("text", user))
        val body = JSONObject()
            .put("model", model)
            .put("max_tokens", maxTokens)
            .put("system", system)
            .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", content)))
        val r = post(ANTHROPIC, mapOf("x-api-key" to key.trim(), "anthropic-version" to "2023-06-01"), body)
        if (r.code == 429) {
            val w = waitSeconds(r)
            throw RateLimitException(w, "Rate limit reached for 'a:$model'. Try again in ${w}s.")
        }
        if (r.code !in 200..299) throw RuntimeException("HTTP ${r.code}: " + r.text.take(220))
        val arr = JSONObject(r.text).getJSONArray("content")
        val sb = StringBuilder()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("type") == "text") sb.append(o.optString("text"))
        }
        return clean(sb.toString())
    }

    fun chat(
        key: String,
        model: String,
        system: String,
        user: String,
        jpegs: List<ByteArray>,
        maxTokens: Int = 1500
    ): String {
        val prov = when {
            model.startsWith("or:") -> "or"
            model.startsWith("g:") -> "g"
            model.startsWith("a:") -> "a"
            else -> "groq"
        }
        val realModel = if (prov == "groq") model else model.substring(2)
        val authKey = when (prov) {
            "or" -> Keys.openrouter
            "g" -> Keys.gemini
            "a" -> Keys.anthropic
            else -> key
        }
        if (authKey.isBlank()) {
            val who = when (prov) { "or" -> "OpenRouter"; "g" -> "Gemini"; "a" -> "Claude"; else -> "Groq" }
            throw RuntimeException("Add your $who key in the gear settings.")
        }
        if (prov == "a") return claude(authKey, realModel, system, user, jpegs, maxTokens)

        val endpoint = when (prov) { "or" -> OPENROUTER; "g" -> GEMINI; else -> GROQ }
        val content = JSONArray()
        content.put(JSONObject().put("type", "text").put("text", user))
        for (jpeg in jpegs) {
            content.put(
                JSONObject().put("type", "image_url")
                    .put("image_url", JSONObject().put("url", "data:image/jpeg;base64," + b64(jpeg)))
            )
        }
        val msgs = JSONArray()
            .put(JSONObject().put("role", "system").put("content", system))
            .put(JSONObject().put("role", "user").put("content", content))

        // Thinking burns lots of tokens and hits rate limits fast, so ask for as little as possible.
        val effort: String? = when {
            prov != "groq" -> null
            realModel.startsWith("openai/gpt-oss") -> "low"
            realModel.startsWith("qwen/") -> "none"
            else -> null
        }

        fun body(e: String?): JSONObject {
            val b = JSONObject()
                .put("model", realModel)
                .put("temperature", 0.2)
                .put("max_tokens", maxTokens)
                .put("messages", msgs)
            if (e != null) b.put("reasoning_effort", e)
            return b
        }

        val headers = mapOf("Authorization" to "Bearer " + authKey.trim())
        var r = post(endpoint, headers, body(effort))
        if (r.code == 400 && effort != null && r.text.contains("reason", ignoreCase = true)) {
            r = post(endpoint, headers, body(null))
        }
        if (r.code == 429) {
            val w = waitSeconds(r)
            throw RateLimitException(w, "Rate limit reached for '$model'. Try again in ${w}s.")
        }
        if (r.code !in 200..299) throw RuntimeException("HTTP ${r.code}: " + r.text.take(220))
        val msg = JSONObject(r.text).getJSONArray("choices").getJSONObject(0).getJSONObject("message")
        return clean(msg.optString("content", ""))
    }
}
