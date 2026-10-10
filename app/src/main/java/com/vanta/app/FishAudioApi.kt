package com.vanta.app

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

object FishAudioApi {
    private const val TTS_URL = "https://api.fish.audio/v1/tts"
    private const val MODEL = "s2.1-pro-free"

    fun synthesize(
        context: Context,
        apiKey: String,
        referenceId: String,
        text: String
    ): File? {
        val cleanKey = apiKey.trim()
        val cleanReference = referenceId.trim()
        if (cleanKey.isBlank() || text.isBlank()) return null

        val connection = (URL(TTS_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15000
            readTimeout = 60000
            doOutput = true
            setRequestProperty("Authorization", "Bearer $cleanKey")
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "audio/mpeg")
            setRequestProperty("model", MODEL)
        }

        return try {
            val body = JSONObject()
                .put("text", text)
                .put("format", "mp3")
                .apply {
                    if (cleanReference.isNotBlank()) put("reference_id", cleanReference)
                }
                .toString()

            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (connection.responseCode !in 200..299) return null

            val file = File.createTempFile("vanta_fish_voice_", ".mp3", context.cacheDir)
            connection.inputStream.use { input ->
                file.outputStream().use { output -> input.copyTo(output) }
            }
            if (file.length() == 0L) {
                file.delete()
                null
            } else {
                file
            }
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }
}
