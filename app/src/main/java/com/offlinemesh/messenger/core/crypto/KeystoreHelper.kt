package com.offlinemesh.messenger.core.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Wraps the SQLCipher database passphrase with a hardware-backed Android Keystore key.
 *
 * The passphrase itself is 32 random bytes, generated on first launch and never derived from
 * user input. It is stored in SharedPreferences only in AES-GCM form, encrypted with a
 * non-exportable key living in the TEE/StrongBox-backed Android Keystore. Extracting the
 * database therefore requires the unlocked device, not just the APK or a filesystem dump.
 */
object KeystoreHelper {

    private const val TAG = "KeystoreHelper"
    private const val KEY_ALIAS = "mesh_db_kek_v1"
    private const val PREFS = "mesh_secure_prefs"
    private const val KEY_PASSPHRASE = "db_passphrase_wrapped"
    private const val ANDROID_KEYSTORE = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_SIZE = 12
    private const val TAG_BITS = 128

    @Synchronized
    fun getDatabasePassphrase(context: Context): ByteArray {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_PASSPHRASE, null)
        if (stored != null) {
            // Throws if the Keystore key was invalidated (e.g. device restored onto new hardware).
            return decrypt(Base64.decode(stored, Base64.NO_WRAP))
        }
        val fresh = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val wrapped = encrypt(fresh)
        prefs.edit()
            .putString(KEY_PASSPHRASE, Base64.encodeToString(wrapped, Base64.NO_WRAP))
            .commit()
        return fresh
    }

    /** Drops the wrapped passphrase and the wrapping key, so the next call mints a fresh pair. */
    @Synchronized
    fun rotatePassphrase(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PASSPHRASE)
            .commit()
        try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
        } catch (e: Exception) {
            Log.w(TAG, "Could not delete wrapping key", e)
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
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

    private fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        return iv + ciphertext
    }

    private fun decrypt(data: ByteArray): ByteArray {
        require(data.size > IV_SIZE) { "corrupt wrapped passphrase" }
        val iv = data.copyOfRange(0, IV_SIZE)
        val ciphertext = data.copyOfRange(IV_SIZE, data.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }
}
