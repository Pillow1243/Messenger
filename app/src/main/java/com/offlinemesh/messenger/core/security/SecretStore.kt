package com.offlinemesh.messenger.core.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Small encrypted key/value store for the few secrets the app must keep across restarts
 * (currently the Supabase refresh token). Values are AES-256-GCM encrypted with a
 * non-exportable Android Keystore key and only the ciphertext is written to SharedPreferences.
 *
 * This is the same construction as [com.offlinemesh.messenger.core.crypto.KeystoreHelper] but
 * with its own key alias, so rotating one never invalidates the other.
 */
class SecretStore(context: Context, private val name: String = "messenger_secrets_v1") {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(name, Context.MODE_PRIVATE)

    fun put(key: String, value: String) {
        val wrapped = runCatching { encrypt(value.toByteArray(Charsets.UTF_8)) }.getOrElse {
            Log.w(TAG, "Could not encrypt secret $key", it)
            return
        }
        prefs.edit().putString(key, Base64.encodeToString(wrapped, Base64.NO_WRAP)).apply()
    }

    fun get(key: String): String? {
        val stored = prefs.getString(key, null) ?: return null
        return runCatching {
            String(decrypt(Base64.decode(stored, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrElse {
            Log.w(TAG, "Dropping unreadable secret $key", it)
            prefs.edit().remove(key).apply()
            null
        }
    }

    fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(keyAlias(), null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            keyAlias(),
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        generator.init(spec)
        return generator.generateKey()
    }

    private fun keyAlias(): String = "$name.kek"

    private fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        return iv + cipher.doFinal(plaintext)
    }

    private fun decrypt(data: ByteArray): ByteArray {
        require(data.size > IV_SIZE) { "corrupt secret" }
        val iv = data.copyOfRange(0, IV_SIZE)
        val ciphertext = data.copyOfRange(IV_SIZE, data.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private companion object {
        const val TAG = "SecretStore"
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128
    }
}
