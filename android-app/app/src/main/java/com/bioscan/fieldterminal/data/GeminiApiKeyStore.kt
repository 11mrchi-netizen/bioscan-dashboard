package com.bioscan.fieldterminal.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

// Step 13: local, per-device storage for the user's own Gemini API key --
// never synced to Supabase (unlike the Google Calendar refresh token, which
// has to live server-side for an Edge Function to use; this key is only ever
// used for a direct client -> Gemini call, so there's no server-side reason
// to store it there, and every reason not to hand a raw secret to a shared
// table).
//
// `androidx.security:security-crypto` (EncryptedSharedPreferences) would be
// the obvious library, but its 1.1.0 stable release (confirmed directly
// against the AndroidX release notes, not assumed) deprecated the entire API
// in favor of using Android Keystore directly -- so this does that directly:
// an AES-GCM key generated inside the hardware-backed AndroidKeyStore
// (non-exportable; a backed-up ciphertext is unreadable after a restore to a
// different device, which is correct here) encrypts the key before it touches
// plain SharedPreferences.
object GeminiApiKeyStore {
    private const val PREFS_NAME = "gemini_settings"
    private const val PREF_CIPHERTEXT = "api_key_ciphertext"
    private const val PREF_IV = "api_key_iv"
    private const val KEYSTORE_ALIAS = "gemini_api_key_aes"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"

    fun save(context: Context, apiKey: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val ciphertext = cipher.doFinal(apiKey.toByteArray(Charsets.UTF_8))

        prefs(context).edit()
            .putString(PREF_CIPHERTEXT, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .putString(PREF_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .apply()
    }

    fun get(context: Context): String? {
        val prefs = prefs(context)
        val ciphertextB64 = prefs.getString(PREF_CIPHERTEXT, null) ?: return null
        val ivB64 = prefs.getString(PREF_IV, null) ?: return null

        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(ciphertextB64, Base64.NO_WRAP)), Charsets.UTF_8)
        } catch (e: Exception) {
            // Key unreadable (e.g. restored onto a new device where the
            // hardware-backed Keystore entry doesn't exist) -- treat as "not
            // set" rather than crash; the user just re-pastes it.
            null
        }
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().apply()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey)?.let { return it }

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        keyGenerator.init(
            KeyGenParameterSpec.Builder(KEYSTORE_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return keyGenerator.generateKey()
    }
}
