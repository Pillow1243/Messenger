package com.offlinemesh.messenger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class PairingCodecTest {

    private val identityKey: String =
        Base64.getEncoder().encodeToString(ByteArray(33) { (it + 1).toByte() })

    private fun payload(name: String = "Alex", deviceId: String = "device-42") =
        PairingPayload(deviceId = deviceId, name = name, identityKey = identityKey, registrationId = 1234)

    @Test
    fun `round trips a contact code`() {
        val code = PairingCodec.encode(payload())
        assertTrue(code.startsWith(PairingCodec.PREFIX))

        val decoded = PairingCodec.decode(code)
        assertNotNull(decoded)
        assertEquals("device-42", decoded!!.deviceId)
        assertEquals("Alex", decoded.name)
        assertEquals(identityKey, decoded.identityKey)
        assertEquals(1234, decoded.registrationId)
    }

    @Test
    fun `code is url safe so it fits in a QR payload`() {
        val code = PairingCodec.encode(payload())
        val body = code.removePrefix(PairingCodec.PREFIX)
        assertTrue(body.none { it == '+' || it == '/' || it == '=' })
    }

    @Test
    fun `surrounding whitespace is tolerated`() {
        val code = "  " + PairingCodec.encode(payload()) + "\n"
        assertNotNull(PairingCodec.decode(code))
    }

    @Test
    fun `foreign text is rejected`() {
        assertNull(PairingCodec.decode("https://example.com"))
        assertNull(PairingCodec.decode(""))
        assertNull(PairingCodec.decode(PairingCodec.PREFIX))
        assertNull(PairingCodec.decode(PairingCodec.PREFIX + "!!!not-base64!!!"))
    }

    @Test
    fun `identity key must be 33 bytes`() {
        val shortKey = Base64.getEncoder().encodeToString(ByteArray(16))
        val code = PairingCodec.encode(payload().copy(identityKey = shortKey))
        assertNull(PairingCodec.decode(code))
    }

    @Test
    fun `blank device id is rejected`() {
        val code = PairingCodec.encode(payload(deviceId = ""))
        assertNull(PairingCodec.decode(code))
    }

    @Test
    fun `oversized input is rejected before parsing`() {
        val huge = PairingCodec.PREFIX + "A".repeat(5000)
        assertNull(PairingCodec.decode(huge))
    }

    @Test
    fun `unicode names survive the trip`() {
        val code = PairingCodec.encode(payload(name = "علی رضایی"))
        assertEquals("علی رضایی", PairingCodec.decode(code)?.name)
    }
}
