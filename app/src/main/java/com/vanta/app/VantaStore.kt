package com.vanta.app
import android.content.Context
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

class VantaStore(context: Context) {
    private val prefs = context.getSharedPreferences("vanta", Context.MODE_PRIVATE)
    private val alias = "vanta_api_key"
    private fun key(): javax.crypto.SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(alias)) {
            val generator = KeyGenerator.getInstance("AES", "AndroidKeyStore")
            generator.init(256)
            generator.generateKey()
        }
        return (ks.getEntry(alias, null) as KeyStore.SecretKeyEntry).secretKey
    }
    fun saveApiKey(value: String) {
        if (value.isBlank()) { prefs.edit().remove("api").apply(); return }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val combined = cipher.iv + cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        prefs.edit().putString("api", Base64.encodeToString(combined, Base64.NO_WRAP)).apply()
    }
    fun loadApiKey(): String {
        val encoded = prefs.getString("api", null) ?: return ""
        return runCatching {
            val bytes = Base64.decode(encoded, Base64.NO_WRAP)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
            String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), Charsets.UTF_8)
        }.getOrDefault("")
    }
    fun loadModel(): String = prefs.getString("model", "gpt-6-luna") ?: "gpt-6-luna"
    fun saveModel(value: String) { prefs.edit().putString("model", value.trim().ifBlank { "gpt-6-luna" }).apply() }
}
