package com.offlinemesh.messenger.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Base64

/**
 * Out-of-band contact exchange (QR code or copy/paste).
 *
 * The payload contains only public data: the device address and the Signal identity public key.
 * Scanning it pins the identity key, which is a stronger guarantee than plain TOFU, because the
 * key travelled through a trusted physical channel.
 */
@Serializable
data class PairingPayload(
    val v: Int = 1,
    val deviceId: String,
    val name: String,
    /** Base64 of the 33-byte Curve25519 identity public key. */
    val identityKey: String,
    val registrationId: Int = 0
)

object PairingCodec {

    const val PREFIX = "MESHPair:"
    private const val VERSION = 1
    private const val IDENTITY_KEY_BYTES = 33
    private const val MAX_CODE_LENGTH = 2048

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(payload: PairingPayload): String {
        val body = json.encodeToString(PairingPayload.serializer(), payload)
            .toByteArray(Charsets.UTF_8)
        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(body)
        return PREFIX + encoded
    }

    /** @return the payload, or null when the text is not a valid contact code. */
    fun decode(text: String): PairingPayload? {
        val trimmed = text.trim()
        if (trimmed.length > MAX_CODE_LENGTH) return null
        if (!trimmed.startsWith(PREFIX)) return null
        val body = trimmed.removePrefix(PREFIX)
        if (body.isEmpty()) return null
        return try {
            val bytes = Base64.getUrlDecoder().decode(body)
            val payload = json.decodeFromString(PairingPayload.serializer(), String(bytes, Charsets.UTF_8))
            if (payload.v != VERSION) return null
            if (payload.deviceId.isBlank()) return null
            if (payload.name.length > 64) return null
            val keyBytes = Base64.getDecoder().decode(payload.identityKey)
            if (keyBytes.size != IDENTITY_KEY_BYTES) return null
            payload
        } catch (e: Exception) {
            null
        }
    }
}
