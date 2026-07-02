package sh.margot.dash.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts service login credentials with an AES-256-GCM key held in the
 * Android Keystore (non-exportable, no user-authentication requirement so
 * background auto-reconnect can run silently) and stores the ciphertext in a
 * plain SharedPreferences file. Superseded EncryptedSharedPreferences
 * (androidx.security:security-crypto, deprecated 2025) by talking to the
 * Keystore directly per Google's current guidance.
 */
object CredentialStore {

    data class Credentials(val email: String, val password: String)

    private const val PREFS_NAME = "dash_credentials"
    private const val KEY_ALIAS = "dash_credential_key"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    /** For services authenticated with a username/password pair (e.g. Koodo). */
    fun save(context: Context, serviceId: String, email: String, password: String) {
        saveSecret(context, serviceId, JSONObject().put("email", email).put("password", password).toString())
    }

    fun get(context: Context, serviceId: String): Credentials? {
        val json = getSecret(context, serviceId)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        return Credentials(json.optString("email"), json.optString("password"))
    }

    /** For services authenticated with a single static secret (e.g. an API key). */
    fun saveSecret(context: Context, serviceId: String, secret: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val ciphertext = cipher.doFinal(secret.toByteArray(Charsets.UTF_8))
        val encoded = Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" +
            Base64.encodeToString(ciphertext, Base64.NO_WRAP)
        prefs(context).edit().putString(serviceId, encoded).apply()
    }

    fun getSecret(context: Context, serviceId: String): String? {
        val raw = prefs(context).getString(serviceId, null) ?: return null
        return try {
            val (ivPart, ciphertextPart) = raw.split(":", limit = 2)
            val iv = Base64.decode(ivPart, Base64.NO_WRAP)
            val ciphertext = Base64.decode(ciphertextPart, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            }
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: Exception) {
            // Key missing/invalidated (e.g. prefs restored to a different device) or corrupt entry.
            null
        }
    }

    fun has(context: Context, serviceId: String) = getSecret(context, serviceId) != null

    fun clear(context: Context, serviceId: String) {
        prefs(context).edit().remove(serviceId).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return generator.generateKey()
    }
}
