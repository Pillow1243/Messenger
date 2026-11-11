package com.offlinemesh.messenger.core.crypto

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Structured E2EE inner payload carried inside Signal / SenderKey ciphertexts.
 *
 * Attachment keys (`mediaKeyBase64`, `mediaIvBase64`), SHA-256 digests, real filenames, MIME types,
 * dimensions, durations, and preview thumbnails are serialized here BEFORE Signal encryption so
 * the server never sees attachment metadata or symmetric keys.
 *
 * Backward compatibility:
 *  - Plain UTF-8 text payloads from older clients (without [ENVELOPE_PREFIX]) decode seamlessly as
 *    `TEXT` messages.
 */
@Serializable
data class E2eeMessagePayload(
    val v: Int = 2,
    val type: String = "TEXT",
    val body: String = "",
    val fileName: String? = null,
    val fileMime: String? = null,
    val fileSize: Long? = null,
    val fileSha256: String? = null,
    val cipherSha256: String? = null,
    val cipherSize: Long? = null,
    val attachmentId: String? = null,
    val objectPath: String? = null,
    val mediaKeyBase64: String? = null,
    val mediaIvBase64: String? = null,
    val thumbnailBase64: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null
)

object OnlinePayloadCodec {

    private const val ENVELOPE_PREFIX = "\u0000MM2:"
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    fun encodeText(body: String): ByteArray = body.toByteArray(Charsets.UTF_8)

    fun encodeStructured(payload: E2eeMessagePayload): ByteArray {
        val serialized = ENVELOPE_PREFIX + json.encodeToString(E2eeMessagePayload.serializer(), payload)
        return serialized.toByteArray(Charsets.UTF_8)
    }

    fun decode(bytes: ByteArray, fallbackType: String = "TEXT"): E2eeMessagePayload {
        val text = String(bytes, Charsets.UTF_8)
        if (!text.startsWith(ENVELOPE_PREFIX)) {
            return E2eeMessagePayload(type = fallbackType, body = text)
        }
        val rawJson = text.removePrefix(ENVELOPE_PREFIX)
        return runCatching {
            json.decodeFromString(E2eeMessagePayload.serializer(), rawJson)
        }.getOrElse {
            E2eeMessagePayload(type = fallbackType, body = "")
        }
    }
}
