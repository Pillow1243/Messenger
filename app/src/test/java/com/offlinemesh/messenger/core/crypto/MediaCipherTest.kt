package com.offlinemesh.messenger.core.crypto

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.Random

class MediaCipherTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `encryptFile and decryptToFile roundtrip multi-chunk binary payload`() {
        // 300 KiB exercises multiple 64 KiB streaming chunks.
        val plainBytes = ByteArray(300 * 1024).also { Random(42L).nextBytes(it) }
        val src = tempFolder.newFile("source.bin").apply { writeBytes(plainBytes) }
        val enc = tempFolder.newFile("cipher.bin")
        val dec = tempFolder.newFile("decrypted.bin")

        var lastProgress = 0L
        val meta = MediaCipher.encryptFile(src, enc, onProgress = { lastProgress = it })

        assertEquals(plainBytes.size.toLong(), meta.plaintextSize)
        assertEquals(plainBytes.size.toLong(), lastProgress)
        // AES-GCM adds a 16-byte (128-bit) authentication tag.
        assertEquals(plainBytes.size.toLong() + 16L, meta.cipherSize)
        assertEquals(meta.cipherSize, enc.length())

        ByteArrayInputStream(enc.readBytes()).use { cipherIn ->
            MediaCipher.decryptToFile(
                cipherIn = cipherIn,
                plainDest = dec,
                keyBase64 = meta.keyBase64,
                ivBase64 = meta.ivBase64,
                expectedCipherSha256 = meta.cipherSha256,
                expectedPlainSha256 = meta.plaintextSha256
            )
        }

        assertTrue(dec.exists())
        assertArrayEquals(plainBytes, dec.readBytes())
    }

    @Test
    fun `every encryption uses a unique ephemeral key and IV`() {
        val sample = "Confidential attachment payload".toByteArray()
        val m1 = MediaCipher.encryptStream(ByteArrayInputStream(sample), ByteArrayOutputStream())
        val m2 = MediaCipher.encryptStream(ByteArrayInputStream(sample), ByteArrayOutputStream())

        assertNotEquals(m1.keyBase64, m2.keyBase64)
        assertNotEquals(m1.ivBase64, m2.ivBase64)
        assertNotEquals(m1.cipherSha256, m2.cipherSha256)
        assertEquals(m1.plaintextSha256, m2.plaintextSha256)
    }

    @Test
    fun `tampered ciphertext fails AEAD and SHA256 verification and deletes partial file`() {
        val plain = ByteArray(96 * 1024).also { Random(7L).nextBytes(it) }
        val src = tempFolder.newFile("plain.pdf").apply { writeBytes(plain) }
        val enc = tempFolder.newFile("enc.bin")
        val dest = tempFolder.newFile("tampered_out.pdf").also { it.delete() }

        val meta = MediaCipher.encryptFile(src, enc)
        val tampered = enc.readBytes().apply { this[this.size / 2] = (this[this.size / 2].toInt() xor 0xFF).toByte() }

        try {
            MediaCipher.decryptToFile(
                cipherIn = ByteArrayInputStream(tampered),
                plainDest = dest,
                keyBase64 = meta.keyBase64,
                ivBase64 = meta.ivBase64,
                expectedCipherSha256 = null, // Force AEAD GCM tag verification path too
                expectedPlainSha256 = meta.plaintextSha256
            )
            fail("Expected MediaIntegrityException on tampered ciphertext")
        } catch (_: MediaIntegrityException) {
            assertFalse("Corrupted output must never remain on disk", dest.exists())
        }
    }

    @Test
    fun `wrong key or wrong expected hash rejects attachment cleanly`() {
        val plain = "Top secret voice note".toByteArray()
        val cipherOut = ByteArrayOutputStream()
        val meta = MediaCipher.encryptStream(ByteArrayInputStream(plain), cipherOut)
        val wrongKey = Base64.getEncoder().encodeToString(ByteArray(32) { 0x5A })
        val dest = tempFolder.newFile("wrong_key.out").also { it.delete() }

        try {
            MediaCipher.decryptToFile(
                cipherIn = ByteArrayInputStream(cipherOut.toByteArray()),
                plainDest = dest,
                keyBase64 = wrongKey,
                ivBase64 = meta.ivBase64,
                expectedCipherSha256 = meta.cipherSha256,
                expectedPlainSha256 = meta.plaintextSha256
            )
            fail("Expected MediaIntegrityException with wrong key")
        } catch (_: MediaIntegrityException) {
            assertFalse(dest.exists())
        }
    }

    @Test
    fun `cancellation aborts streaming encryption and removes partial output`() {
        val plain = ByteArray(256 * 1024) { 0x11 }
        val src = tempFolder.newFile("large.mp4").apply { writeBytes(plain) }
        val enc = tempFolder.newFile("cancelled.enc")
        var chunks = 0

        try {
            MediaCipher.encryptFile(
                sourceFile = src,
                destFile = enc,
                isCancelled = { ++chunks > 1 }
            )
            fail("Expected MediaTransferCancelledException")
        } catch (_: MediaTransferCancelledException) {
            assertFalse("Cancelled ciphertext must be deleted", enc.exists())
        }
    }

    @Test
    fun `OnlinePayloadCodec preserves structured E2EE media metadata and legacy UTF8 text`() {
        val legacy = OnlinePayloadCodec.decode(OnlinePayloadCodec.encodeText("Salaam! سلام"))
        assertEquals("TEXT", legacy.type)
        assertEquals("Salaam! سلام", legacy.body)

        val structured = E2eeMessagePayload(
            type = "IMAGE",
            body = "Sunset",
            fileName = "sunset.jpg",
            fileMime = "image/jpeg",
            fileSize = 148_220L,
            fileSha256 = "abc123",
            cipherSha256 = "def456",
            cipherSize = 148_236L,
            attachmentId = "att-1",
            objectPath = "conv-1/user-1/blob.enc",
            mediaKeyBase64 = "keyB64",
            mediaIvBase64 = "ivB64",
            thumbnailBase64 = "thumbB64",
            width = 1920,
            height = 1080
        )
        val decoded = OnlinePayloadCodec.decode(OnlinePayloadCodec.encodeStructured(structured))
        assertEquals(structured, decoded)
    }
}
