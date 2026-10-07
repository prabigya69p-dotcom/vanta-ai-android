package com.vanta.app

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class VantaImageApi(private val apiKey: String) {
    fun generate(prompt: String): String {
        if (apiKey.isBlank()) error("Add your OpenAI API key in VANTA Settings first.")
        val connection = (URL("https://api.openai.com/v1/images/generations").openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 20_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Authorization", "Bearer " + apiKey)
            setRequestProperty("Content-Type", "application/json")
        }
        val body = JSONObject()
            .put("model", "gpt-image-2")
            .put("prompt", prompt)
            .put("size", "1024x1024")
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val raw = stream.bufferedReader().use { it.readText() }
        if (code !in 200..299) {
            val message = runCatching { JSONObject(raw).optJSONObject("error")?.optString("message") }.getOrNull()
            error(message?.takeIf { it.isNotBlank() } ?: "Image generation failed (" + code + ").")
        }

        val item = JSONObject(raw).optJSONArray("data")?.optJSONObject(0)
            ?: error("No image was returned.")
        item.optString("b64_json").takeIf { it.isNotBlank() }?.let {
            return "data:image/png;base64," + it
        }
        item.optString("url").takeIf { it.isNotBlank() }?.let { return it }
        error("The image service returned no usable image.")
    }
}