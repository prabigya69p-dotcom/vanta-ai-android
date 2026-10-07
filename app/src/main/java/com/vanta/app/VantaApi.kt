package com.vanta.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

data class VantaReply(val text: String, val usedResearch: Boolean)

class VantaApi(private val apiKey: String, private val model: String) {
    fun ask(prompt: String, research: Boolean, imageDataUrl: String? = null): VantaReply {
        if (apiKey.isBlank()) error("Add your OpenAI API key in VANTA Settings first.")
        val connection = (URL("https://api.openai.com/v1/responses").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
        }

        val body = JSONObject().apply {
            put("model", model)
            put("instructions", "You are VANTA, a concise, capable Android AI assistant. Be useful, honest and direct.")
            if (imageDataUrl == null) {
                put("input", prompt)
            } else {
                val content = JSONArray()
                    .put(JSONObject().put("type", "input_text").put("text", prompt))
                    .put(JSONObject().put("type", "input_image").put("image_url", imageDataUrl))
                put("input", JSONArray().put(JSONObject()
                    .put("role", "user")
                    .put("content", content)))
            }
            if (research) put("tools", JSONArray().put(JSONObject().put("type", "web_search")))
        }

        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val raw = stream.bufferedReader().use { it.readText() }
        if (code !in 200..299) {
            val message = runCatching { JSONObject(raw).optJSONObject("error")?.optString("message") }.getOrNull()
            error(message?.takeIf { it.isNotBlank() } ?: "API request failed ($code).")
        }

        val json = JSONObject(raw)
        val direct = json.optString("output_text")
        if (direct.isNotBlank()) return VantaReply(direct, research)
        val output = json.optJSONArray("output") ?: return VantaReply("I received an empty response.", research)
        val parts = mutableListOf<String>()
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            val content = item.optJSONArray("content") ?: continue
            for (j in 0 until content.length()) {
                val text = content.optJSONObject(j)?.optString("text").orEmpty()
                if (text.isNotBlank()) parts += text
            }
        }
        return VantaReply(parts.joinToString("\n").ifBlank { "I received an empty response." }, research)
    }
}
