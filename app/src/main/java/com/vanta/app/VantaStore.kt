package com.vanta.app

import android.content.Context
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONArray
import org.json.JSONObject

data class SavedChatMessage(val user: Boolean, val text: String)

class VantaStore(context: Context) {
    private val prefs = context.getSharedPreferences("vanta", Context.MODE_PRIVATE)
    private val alias = "vanta_api_key_v2"

    private fun key(): javax.crypto.SecretKey {
        var ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(alias)) {
            val generator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
            generator.init(256)
            generator.generateKey()
            // Reload the keystore after generation so the new alias is visible
            // on Android implementations that cache the loaded keystore.
            ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        }
        return (ks.getEntry(alias, null) as KeyStore.SecretKeyEntry).secretKey
    }

    fun saveApiKey(value: String): Boolean {
        val normalized = value.trim()
        if (normalized.isBlank()) {
            prefs.edit().remove("api_v2").commit()
            return true
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val combined = cipher.iv + cipher.doFinal(normalized.toByteArray(Charsets.UTF_8))
        val saved = prefs.edit()
            .putString("api_v2", Base64.encodeToString(combined, Base64.NO_WRAP))
            .commit()
        return saved && loadApiKey() == normalized
    }

    fun loadApiKey(): String {
        val encoded = prefs.getString("api_v2", null)
        if (!encoded.isNullOrBlank()) {
            val decoded = runCatching {
                val bytes = Base64.decode(encoded, Base64.NO_WRAP)
                require(bytes.size > 12)
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    key(),
                    GCMParameterSpec(128, bytes.copyOfRange(0, 12))
                )
                String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
            }.getOrNull()
            if (!decoded.isNullOrBlank()) return decoded
        }

        // Recover keys saved by the pre-v2 store when possible.
        val legacy = prefs.getString("api", null).orEmpty().trim()
        if (legacy.isNotBlank()) return legacy
        return BuildConfig.NVIDIA_API_KEY.trim()
    }

    private fun saveEncrypted(prefKey: String, value: String): Boolean {
        val normalized = value.trim()
        if (normalized.isBlank()) {
            return prefs.edit().remove(prefKey).commit()
        }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val combined = cipher.iv + cipher.doFinal(normalized.toByteArray(Charsets.UTF_8))
        val saved = prefs.edit()
            .putString(prefKey, Base64.encodeToString(combined, Base64.NO_WRAP))
            .commit()
        return saved && loadEncrypted(prefKey) == normalized
    }

    private fun loadEncrypted(prefKey: String): String {
        val encoded = prefs.getString(prefKey, null) ?: return ""
        return runCatching {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            require(bytes.size > 12)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                key(),
                GCMParameterSpec(128, bytes.copyOfRange(0, 12))
            )
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        }.getOrDefault("")
    }

    fun saveGitHubToken(value: String): Boolean = saveEncrypted("github_code_dev_token_v1", value)

    fun loadGitHubToken(): String = loadEncrypted("github_code_dev_token_v1")

    fun saveGoogleAccessToken(value: String): Boolean = saveEncrypted("google_workspace_access_token_v1", value)
    fun loadGoogleAccessToken(): String = loadEncrypted("google_workspace_access_token_v1")
    fun saveNotionToken(value: String): Boolean = saveEncrypted("notion_integration_token_v1", value)
    fun loadNotionToken(): String = loadEncrypted("notion_integration_token_v1")
    fun saveNotionDatabaseId(value: String) { prefs.edit().putString("notion_database_id_v1", value.trim()).apply() }
    fun loadNotionDatabaseId(): String = prefs.getString("notion_database_id_v1", "").orEmpty()
    fun saveFishAudioApiKey(value: String): Boolean =
        saveEncrypted("fish_audio_api_v1", value)

    fun loadFishAudioApiKey(): String =
        loadEncrypted("fish_audio_api_v1").ifBlank { BuildConfig.FISH_AUDIO_API_KEY.trim() }

    fun saveFishAudioReferenceId(value: String): Boolean =
        saveEncrypted("fish_audio_reference_v1", value)

    fun loadFishAudioReferenceId(): String =
        loadEncrypted("fish_audio_reference_v1").ifBlank { BuildConfig.FISH_AUDIO_REFERENCE_ID.trim() }

    private fun defaultModel(): String =
        BuildConfig.NVIDIA_MODEL.trim().ifBlank { "z-ai/glm-5.3-flash" }

    fun loadModel(): String {
        val saved = prefs.getString("model", null)?.trim().orEmpty()
        // Migrate the previous hard-coded default to the model exercised by CI.
        val resolved = if (saved.isBlank() || saved == "qwen/qwen3.5-122b-a10b") {
            defaultModel()
        } else {
            saved
        }
        if (saved != resolved) prefs.edit().putString("model", resolved).apply()
        return resolved
    }

    fun saveModel(value: String) {
        prefs.edit()
            .putString("model", value.trim().ifBlank { defaultModel() })
            .apply()
    }

    fun loadPersonality(): String =
        prefs.getString("personality", "direct, warm, futuristic, honest")
            ?: "direct, warm, futuristic, honest"

    fun savePersonality(value: String) {
        prefs.edit().putString("personality", value.trim().take(300)).apply()
    }

    fun loadProject(): String =
        prefs.getString("project", "General") ?: "General"

    fun saveProject(value: String) {
        prefs.edit().putString("project", value.trim().ifBlank { "General" }).apply()
    }

    fun loadVoiceSpeed(): Float = prefs.getFloat("voice_speed", 1.0f)

    fun saveVoiceSpeed(value: Float) {
        prefs.edit().putFloat("voice_speed", value.coerceIn(0.65f, 1.5f)).apply()
    }

    fun loadVoicePitch(): Float = prefs.getFloat("voice_pitch", 1.0f)

    fun saveVoicePitch(value: Float) {
        prefs.edit().putFloat("voice_pitch", value.coerceIn(0.75f, 1.3f)).apply()
    }

    fun loadTemporaryChat(): Boolean = prefs.getBoolean("temporary_chat", false)

    fun saveTemporaryChat(value: Boolean) {
        prefs.edit().putBoolean("temporary_chat", value).apply()
    }

    fun loadWakeEnabled(): Boolean = prefs.getBoolean("wake_enabled", false)

    fun saveWakeEnabled(value: Boolean) {
        prefs.edit().putBoolean("wake_enabled", value).apply()
    }

    fun saveChat(messages: List<SavedChatMessage>) {
        if (loadTemporaryChat()) return
        val arr = JSONArray()
        messages.takeLast(40).forEach {
            arr.put(JSONObject().put("user", it.user).put("text", it.text))
        }
        prefs.edit().putString("chat", arr.toString()).apply()
    }

    fun loadChat(): List<SavedChatMessage> {
        val raw = prefs.getString("chat", null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            List(arr.length()) {
                val obj = arr.getJSONObject(it)
                SavedChatMessage(obj.optBoolean("user"), obj.optString("text"))
            }
        }.getOrDefault(emptyList())
    }

    fun clearChat() = prefs.edit().remove("chat").apply()


}
