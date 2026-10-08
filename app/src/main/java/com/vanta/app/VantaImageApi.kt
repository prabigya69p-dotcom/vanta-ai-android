package com.vanta.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class VantaImageApi(private val apiKey: String) {
    fun generate(prompt: String): String {
        if (apiKey.isBlank()) {
            error("Add your NVIDIA NIM API key in VANTA Settings first.")
        }

        val connection = (URL("https://ai.api.nvidia.com/v1/genai/black-forest-labs/flux.2-klein-4b")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
        }

        val body = JSONObject()
            .put("mode", "Image Generation")
            .put("prompt", prompt)
            .put("height", 1024)
            .put("width", 1024)
            .put("samples", 1)
            .put("steps", 4)
            .put("seed", 0)

        connection.outputStream.use {
            it.write(body.toString().toByteArray(Charsets.UTF_8))
        }

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
            error(message?.takeIf { it.isNotBlank() } ?: "NVIDIA image generation failed ($code).")
        }

        val json = JSONObject(raw)
        val data = json.optJSONArray("data")
        val first = data?.optJSONObject(0)

        first?.optString("b64_json")?.takeIf { it.isNotBlank() }?.let {
            return "data:image/png;base64,$it"
        }

        first?.optString("url")?.takeIf { it.isNotBlank() }?.let { return it }
        json.optString("image").takeIf { it.isNotBlank() }?.let { return it }

        error("NVIDIA image generation returned no usable image.")
    }
}
