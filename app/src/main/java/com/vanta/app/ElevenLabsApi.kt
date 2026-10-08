package com.vanta.app

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

object ElevenLabsApi {
    private const val BASE_URL = "https://api.elevenlabs.io/v1/text-to-speech/"

    fun synthesize(
        context: Context,
        apiKey: String,
        voiceId: String,
        text: String
    ): File? {
        val cleanKey = apiKey.trim()
        val cleanVoice = voiceId.trim()
        if (cleanKey.isBlank() || cleanVoice.isBlank() || text.isBlank()) return null

        val connection = (URL(BASE_URL + cleanVoice).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 30000
            doOutput = true
            setRequestProperty("xi-api-key", cleanKey)
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "audio/mpeg")
        }

        return try {
            val body = JSONObject()
                .put("text", text)
                .put("model_id", "eleven_multilingual_v2")
                .put("voice_settings", JSONObject()
                    .put("stability", 0.5)
                    .put("similarity_boost", 0.75)
                    .put("style", 0.2)
                    .put("use_speaker_boost", true))
                .toString()

            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode !in 200..299) return null

            val file = File.createTempFile("vanta_voice_", ".mp3", context.cacheDir)
            connection.inputStream.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            file
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }
}
