package com.offlinemesh.messenger.core.crypto

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.DigestInputStream
import java.security.DigestOutputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class MediaIntegrityException(message: String, cause: Throwable? = null) : IOException(message, cause)

class MediaTransferCancelledException : IOException("transfer_cancelled")

/**
 * Ephemeral AES-256-GCM streaming attachment cipher.
 *
 * Security invariants:
 *  - Every uploaded attachment uses a fresh 256-bit random AES key and 96-bit random IV.
 *  - Both encryption and decryption stream in fixed [DEFAULT_BUFFER_SIZE] (64 KiB) chunks,
 *    keeping memory usage O(1) even for large videos and documents.
 *  - Key + IV + plaintext SHA-256 + ciphertext SHA-256 travel exclusively inside the Signal E2EE
 *    envelope; only the opaque ciphertext blob is uploaded to private Storage (`mm-media`).
 *  - On download, ciphertext SHA-256, the 128-bit GCM authentication tag, and plaintext SHA-256
 *    are all verified before exposing decrypted output; any mismatch deletes the partial output
 *    and throws [MediaIntegrityException].
 */
object MediaCipher {

    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_BYTES = 32
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    const val DEFAULT_BUFFER_SIZE = 64 * 1024

    private val rng = SecureRandom()

    data class EncryptedMediaMetadata(
        val keyBase64: String,
        val ivBase64: String,
        val plaintextSize: Long,
        val plaintextSha256: String,
        val cipherSize: Long,
        val cipherSha256: String
    )

    fun encryptStream(
        plaintextIn: InputStream,
        encryptedOut: OutputStream,
        isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
        onProgress: ((plaintextBytesRead: Long) -> Unit)? = null
    ): EncryptedMediaMetadata {
        val key = ByteArray(KEY_BYTES).also(rng::nextBytes)
        val iv = ByteArray(IV_BYTES).also(rng::nextBytes)
        val keyBase64 = Base64.getEncoder().encodeToString(key)
        val ivBase64 = Base64.getEncoder().encodeToString(iv)

        val plainDigest = MessageDigest.getInstance("SHA-256")
        val cipherDigest = MessageDigest.getInstance("SHA-256")

        try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
            }
            var plainTotal = 0L
            val countingCipherOut = CountingOutputStream(DigestOutputStream(encryptedOut, cipherDigest))
            CipherOutputStream(countingCipherOut, cipher).use { cos ->
                val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    if (isCancelled()) throw MediaTransferCancelledException()
                    val read = plaintextIn.read(buf)
                    if (read < 0) break
                    if (read > 0) {
                        plainDigest.update(buf, 0, read)
                        cos.write(buf, 0, read)
                        plainTotal += read
                        onProgress?.invoke(plainTotal)
                    }
                }
            }
            return EncryptedMediaMetadata(
                keyBase64 = keyBase64,
                ivBase64 = ivBase64,
                plaintextSize = plainTotal,
                plaintextSha256 = plainDigest.digest().toHex(),
                cipherSize = countingCipherOut.bytesWritten,
                cipherSha256 = cipherDigest.digest().toHex()
            )
        } finally {
            key.fill(0)
        }
    }

    fun encryptFile(
        plaintextIn: InputStream,
        encryptedDest: File,
        isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
        onProgress: ((Long) -> Unit)? = null
    ): EncryptedMediaMetadata {
        encryptedDest.parentFile?.mkdirs()
        return try {
            FileOutputStream(encryptedDest).use { fos ->
                encryptStream(plaintextIn, fos, isCancelled, onProgress)
            }
        } catch (t: Throwable) {
            encryptedDest.delete()
            throw t
        }
    }

    fun decryptStream(
        encryptedIn: InputStream,
        plaintextOut: OutputStream,
        keyBase64: String,
        ivBase64: String,
        expectedCipherSha256: String? = null,
        expectedPlaintextSha256: String? = null,
        isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
        onProgress: ((plaintextBytesWritten: Long) -> Unit)? = null
    ): Long {
        val key = try {
            Base64.getDecoder().decode(keyBase64)
        } catch (e: IllegalArgumentException) {
            throw MediaIntegrityException("Invalid attachment key encoding", e)
        }
        val iv = try {
            Base64.getDecoder().decode(ivBase64)
        } catch (e: IllegalArgumentException) {
            key.fill(0)
            throw MediaIntegrityException("Invalid attachment IV encoding", e)
        }
        if (key.size != KEY_BYTES || iv.size != IV_BYTES) {
            key.fill(0)
            throw MediaIntegrityException("Invalid attachment key/IV size (${key.size}/${iv.size})")
        }

        val cipherDigest = MessageDigest.getInstance("SHA-256")
        val plainDigest = MessageDigest.getInstance("SHA-256")

        try {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(TAG_BITS, iv))
            }
            val digestIn = DigestInputStream(encryptedIn, cipherDigest)
            var written = 0L
            try {
                CipherInputStream(digestIn, cipher).use { cis ->
                    val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        if (isCancelled()) throw MediaTransferCancelledException()
                        val read = cis.read(buf)
                        if (read < 0) break
                        if (read > 0) {
                            plainDigest.update(buf, 0, read)
                            plaintextOut.write(buf, 0, read)
                            written += read
                            onProgress?.invoke(written)
                        }
                    }
                }
            } catch (e: MediaTransferCancelledException) {
                throw e
            } catch (e: IOException) {
                if (e.cause is AEADBadTagException || e.cause is GeneralSecurityException) {
                    throw MediaIntegrityException("GCM authentication tag mismatch", e)
                }
                throw e
            } catch (e: GeneralSecurityException) {
                throw MediaIntegrityException("GCM decryption failed", e)
            }

            if (!expectedCipherSha256.isNullOrBlank()) {
                val actualCipherHex = cipherDigest.digest().toHex()
                if (!actualCipherHex.equals(expectedCipherSha256, ignoreCase = true)) {
                    throw MediaIntegrityException(
                        "Ciphertext SHA-256 mismatch (expected $expectedCipherSha256, got $actualCipherHex)"
                    )
                }
            }
            if (!expectedPlaintextSha256.isNullOrBlank()) {
                val actualPlainHex = plainDigest.digest().toHex()
                if (!actualPlainHex.equals(expectedPlaintextSha256, ignoreCase = true)) {
                    throw MediaIntegrityException(
                        "Plaintext SHA-256 mismatch (expected $expectedPlaintextSha256, got $actualPlainHex)"
                    )
                }
            }
            plaintextOut.flush()
            return written
        } finally {
            key.fill(0)
        }
    }

    /**
     * Decrypts [encryptedSource] into [plaintextDest] via an isolated `.tmp` file so unverified
     * plaintext never survives if the GCM tag or SHA-256 check fails.
     */
    fun decryptFile(
        encryptedSource: File,
        plaintextDest: File,
        keyBase64: String,
        ivBase64: String,
        expectedCipherSha256: String? = null,
        expectedPlaintextSha256: String? = null,
        isCancelled: () -> Boolean = { Thread.currentThread().isInterrupted },
        onProgress: ((Long) -> Unit)? = null
    ): Long {
        plaintextDest.parentFile?.mkdirs()
        val tempOut = File(plaintextDest.parentFile, "${plaintextDest.name}.dec.tmp")
        return try {
            val total = FileInputStream(encryptedSource).use { fis ->
                FileOutputStream(tempOut).use { fos ->
                    decryptStream(
                        encryptedIn = fis,
                        plaintextOut = fos,
                        keyBase64 = keyBase64,
                        ivBase64 = ivBase64,
                        expectedCipherSha256 = expectedCipherSha256,
                        expectedPlaintextSha256 = expectedPlaintextSha256,
                        isCancelled = isCancelled,
                        onProgress = onProgress
                    )
                }
            }
            if (plaintextDest.exists()) plaintextDest.delete()
            if (!tempOut.renameTo(plaintextDest)) {
                tempOut.copyTo(plaintextDest, overwrite = true)
                tempOut.delete()
            }
            total
        } catch (t: Throwable) {
            tempOut.delete()
            throw t
        }
    }

    fun sha256File(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buf = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val n = fis.read(buf)
                if (n < 0) break
                if (n > 0) md.update(buf, 0, n)
            }
        }
        return md.digest().toHex()
    }

    private class CountingOutputStream(private val delegate: OutputStream) : OutputStream() {
        var bytesWritten: Long = 0L
            private set

        override fun write(b: Int) {
            delegate.write(b)
            bytesWritten += 1
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            delegate.write(b, off, len)
            bytesWritten += len
        }

        override fun flush() = delegate.flush()
        override fun close() = delegate.close()
    }

    private fun ByteArray.toHex(): String = buildString(size * 2) {
        for (b in this@toHex) {
            val v = b.toInt() and 0xFF
            append(HEX[v ushr 4])
            append(HEX[v and 0x0F])
        }
    }

    private const val HEX = "0123456789abcdef"
}
