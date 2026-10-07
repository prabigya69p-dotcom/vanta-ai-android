package com.vanta.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class VantaSource(val title: String, val url: String)

data class VantaReply(
    val text: String,
    val usedResearch: Boolean,
    val sources: List<VantaSource> = emptyList()
)

class VantaApi(private val apiKey: String, private val model: String) {
    private val endpoint = "https://api.openai.com/v1/responses"

    fun ask(
        prompt: String,
        instructions: String,
        research: Boolean,
        imageDataUrls: List<String> = emptyList(),
        history: List<SavedChatMessage> = emptyList()
    ): VantaReply {
        if (apiKey.isBlank()) error("Add your OpenAI API key in VANTA Settings first.")
        val connection = connection()
        val body = buildBody(prompt, instructions, research, imageDataUrls, history, false)
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        return readReply(connection, research)
    }

    fun askStreaming(
        prompt: String,
        instructions: String,
        research: Boolean,
        imageDataUrls: List<String> = emptyList(),
        history: List<SavedChatMessage> = emptyList(),
        onDelta: (String) -> Unit
    ): VantaReply {
        if (apiKey.isBlank()) error("Add your OpenAI API key in VANTA Settings first.")
        val connection = connection().apply { setRequestProperty("Accept", "text/event-stream") }
        val body = buildBody(prompt, instructions, research, imageDataUrls, history, true)
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        if (code !in 200..299) {
            val raw = stream.bufferedReader().use { it.readText() }
            val message = runCatching { JSONObject(raw).optJSONObject("error")?.optString("message") }.getOrNull()
            error(message?.takeIf { it.isNotBlank() } ?: "API request failed ($code).")
        }

        val reader = stream.bufferedReader()
        val text = StringBuilder()
        while (true) {
            val line = reader.readLine() ?: break
            if (!line.startsWith("data:")) continue
            val payload = line.removePrefix("data:").trim()
            if (payload.isBlank() || payload == "[DONE]") continue
            val json = runCatching { JSONObject(payload) }.getOrNull() ?: continue
            if (json.optString("type") == "response.output_text.delta") {
                val delta = json.optString("delta")
                if (delta.isNotEmpty()) {
                    text.append(delta)
                    onDelta(delta)
                }
            }
        }
        return VantaReply(text.toString().ifBlank { "I received an empty streamed response." }, research)
    }

    private fun connection(): HttpURLConnection =
        (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
        }

    private fun buildBody(
        prompt: String,
        instructions: String,
        research: Boolean,
        imageDataUrls: List<String>,
        history: List<SavedChatMessage>,
        stream: Boolean
    ): JSONObject {
        val body = JSONObject().apply {
            put("model", model)
            put("instructions", instructions)
            if (stream) put("stream", true)
        }

        val input = JSONArray()
        history.takeLast(10).forEach {
            input.put(
                JSONObject()
                    .put("role", if (it.user) "user" else "assistant")
                    .put("content", JSONArray().put(JSONObject().put("type", "input_text").put("text", it.text)))
            )
        }

        val content = JSONArray().put(JSONObject().put("type", "input_text").put("text", prompt))
        imageDataUrls.forEach { data ->
            content.put(JSONObject().put("type", "input_image").put("image_url", data))
        }
        input.put(JSONObject().put("role", "user").put("content", content))
        body.put("input", input)

        if (research) body.put("tools", JSONArray().put(JSONObject().put("type", "web_search")))
        return body
    }

    private fun readReply(connection: HttpURLConnection, research: Boolean): VantaReply {
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val raw = stream.bufferedReader().use { it.readText() }
        if (code !in 200..299) {
            val message = runCatching { JSONObject(raw).optJSONObject("error")?.optString("message") }.getOrNull()
            error(message?.takeIf { it.isNotBlank() } ?: "API request failed ($code).")
        }

        val json = JSONObject(raw)
        val direct = json.optString("output_text")
        if (direct.isNotBlank()) return VantaReply(direct, research, extractSources(json))
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
        return VantaReply(parts.joinToString("\n").ifBlank { "I received an empty response." }, research, extractSources(json))
    }

    private fun extractSources(json: JSONObject): List<VantaSource> {
        val result = linkedMapOf<String, VantaSource>()
        val output = json.optJSONArray("output") ?: return emptyList()
        for (i in 0 until output.length()) {
            val item = output.optJSONObject(i) ?: continue
            val content = item.optJSONArray("content")
            if (content != null) {
                for (j in 0 until content.length()) {
                    val c = content.optJSONObject(j) ?: continue
                    val annotations = c.optJSONArray("annotations") ?: continue
                    for (k in 0 until annotations.length()) {
                        val a = annotations.optJSONObject(k) ?: continue
                        val url = a.optString("url")
                        if (a.optString("type").contains("url_citation") && url.isNotBlank()) {
                            result[url] = VantaSource(a.optString("title").ifBlank { url }, url)
                        }
                    }
                }
            }
            if (item.optString("type") == "web_search_call") {
                val sources = item.optJSONObject("action")?.optJSONArray("sources")
                if (sources != null) {
                    for (j in 0 until sources.length()) {
                        val url = sources.optJSONObject(j)?.optString("url").orEmpty()
                        if (url.isNotBlank()) result[url] = VantaSource(url, url)
                    }
                }
            }
        }
        return result.values.take(6)
    }
}