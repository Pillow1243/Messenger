package com.offlinemesh.messenger.core.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Encrypts attachments (voice notes, files) at rest with an AES-256-GCM key held in the
 * Android Keystore, so nothing decryptable is left in the app's private storage.
 *
 * File format: `IV(12) | AES-GCM ciphertext`. The plaintext is only ever written to the
 * transient cache when the user explicitly opens a file with another app.
 */
class FileVault {

    companion object {
        private const val ALIAS = "mesh_file_vault_v1"
        private const val KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_SIZE = 12
        private const val TAG_BITS = 128
    }

    private fun key(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    private fun encryptingStream(output: OutputStream): Pair<Cipher, OutputStream> {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        output.write(cipher.iv)
        return cipher to CipherOutputStream(output, cipher)
    }

    private fun decryptingStream(input: InputStream): InputStream {
        val iv = ByteArray(IV_SIZE)
        var read = 0
        while (read < IV_SIZE) {
            val n = input.read(iv, read, IV_SIZE - read)
            if (n <= 0) throw IllegalStateException("truncated vault file")
            read += n
        }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        return CipherInputStream(input, cipher)
    }

    /** Streams [source] into [destination] as ciphertext (both closed). */
    fun seal(source: InputStream, destination: File) {
        destination.parentFile?.mkdirs()
        FileOutputStream(destination, false).use { fileOut ->
            val (_, cipherOut) = encryptingStream(fileOut)
            cipherOut.use { out -> source.use { it.copyTo(out, DEFAULT_BUFFER_SIZE) } }
        }
    }

    fun sealFile(source: File, destination: File) = seal(FileInputStream(source), destination)

    /** Streams a sealed [source] back to plaintext in [destination] (both closed). */
    fun open(source: File, destination: File) {
        destination.parentFile?.mkdirs()
        FileInputStream(source).use { fileIn ->
            decryptingStream(fileIn).use { plain ->
                FileOutputStream(destination, false).use { out -> plain.copyTo(out) }
            }
        }
    }

    fun sealBytes(plaintext: ByteArray, destination: File) = seal(plaintext.inputStream(), destination)

    fun openBytes(source: File): ByteArray =
        FileInputStream(source).use { fileIn ->
            decryptingStream(fileIn).use { it.readBytes() }
        }
}
