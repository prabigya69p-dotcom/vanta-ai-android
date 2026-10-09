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

class VantaApi(
    private val apiKey: String,
    private val model: String
) {
    private val endpoint = "https://integrate.api.nvidia.com/v1/chat/completions"

    fun ask(
        prompt: String,
        instructions: String,
        research: Boolean,
        imageDataUrls: List<String> = emptyList(),
        history: List<SavedChatMessage> = emptyList()
    ): VantaReply {
        require(apiKey.isNotBlank()) {
            "Add your NVIDIA NIM API key in VANTA Settings first."
        }

        val connection = connection(stream = false)
        val body = buildBody(
            prompt = prompt,
            instructions = instructions,
            research = research,
            imageDataUrls = imageDataUrls,
            history = history,
            stream = false
        )

        connection.outputStream.use {
            it.write(body.toString().toByteArray(Charsets.UTF_8))
        }

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
        require(apiKey.isNotBlank()) {
            "Add your NVIDIA NIM API key in VANTA Settings first."
        }

        val connection = connection(stream = true)
        val body = buildBody(
            prompt = prompt,
            instructions = instructions,
            research = research,
            imageDataUrls = imageDataUrls,
            history = history,
            stream = true
        )

        connection.outputStream.use {
            it.write(body.toString().toByteArray(Charsets.UTF_8))
        }

        val code = connection.responseCode
        val stream = if (code in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream ?: connection.inputStream
        }

        if (code !in 200..299) {
            val raw = stream.bufferedReader().use { it.readText() }
            val message = runCatching {
                JSONObject(raw).optJSONObject("error")?.optString("message")
            }.getOrNull()
            error(message?.takeIf { it.isNotBlank() } ?: "NVIDIA NIM request failed ($code).")
        }

        val reader = stream.bufferedReader()
        val text = StringBuilder()

        while (true) {
            val line = reader.readLine() ?: break
            if (!line.startsWith("data:")) continue

            val payload = line.removePrefix("data:").trim()
            if (payload.isBlank() || payload == "[DONE]") continue

            val json = runCatching { JSONObject(payload) }.getOrNull() ?: continue
            val delta = json.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("delta")
                ?.optString("content")
                .orEmpty()

            if (delta.isNotEmpty()) {
                text.append(delta)
                onDelta(delta)
            }
        }

        val streamedText = text.toString().trim()
        if (streamedText.isBlank()) {
            // Some model responses can complete the SSE stream without usable text deltas.
            // Retry once without streaming instead of showing a false empty-response message.
            connection.disconnect()
            return ask(prompt, instructions, research, imageDataUrls, history)
        }

        return VantaReply(
            text = streamedText,
            usedResearch = research
        )
    }

    private fun connection(stream: Boolean): HttpURLConnection =
        (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", if (stream) "text/event-stream" else "application/json")
        }

    private fun buildBody(
        prompt: String,
        instructions: String,
        research: Boolean,
        imageDataUrls: List<String>,
        history: List<SavedChatMessage>,
        stream: Boolean
    ): JSONObject {
        val body = JSONObject()
            .put("model", model)
            .put("temperature", 0.2)
            .put("max_tokens", 4096)
            .put("stream", stream)

        val messages = JSONArray()

        if (instructions.isNotBlank()) {
            messages.put(
                JSONObject()
                    .put("role", "system")
                    .put(
                        "content",
                        if (research) {
                            "$instructions\n\nResearch mode is enabled. Prefer careful, evidence-aware answers. Do not invent web sources, URLs, citations, or claims of live browsing."
                        } else {
                            instructions
                        }
                    )
            )
        }

        history.takeLast(10).forEach {
            messages.put(
                JSONObject()
                    .put("role", if (it.user) "user" else "assistant")
                    .put("content", it.text)
            )
        }

        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))

        imageDataUrls.take(8).forEach { data ->
            content.put(
                JSONObject()
                    .put("type", "image_url")
                    .put("image_url", JSONObject().put("url", data))
            )
        }

        messages.put(
            JSONObject()
                .put("role", "user")
                .put("content", content)
        )

        body.put("messages", messages)
        return body
    }

    private fun readReply(
        connection: HttpURLConnection,
        research: Boolean
    ): VantaReply {
        val code = connection.responseCode
        val stream = if (code in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream ?: connection.inputStream
        }

        val raw = stream.bufferedReader().use { it.readText() }

        if (code !in 200..299) {
            val message = runCatching {
                JSONObject(raw).optJSONObject("error")?.optString("message")
            }.getOrNull()
            error(message?.takeIf { it.isNotBlank() } ?: "NVIDIA NIM request failed ($code).")
        }

        val json = JSONObject(raw)
        val choice = json.optJSONArray("choices")?.optJSONObject(0)
            ?: error("NVIDIA NIM returned no choices.")

        val text = choice.optJSONObject("message")
            ?.optString("content")
            .orEmpty()

        return VantaReply(
            text = text.ifBlank { "I received an empty response from NVIDIA NIM." },
            usedResearch = research
        )
    }
}
