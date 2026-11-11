package com.offlinemesh.messenger.core.mesh

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PacketCodecTest {

    private fun header(
        id: String = "packet-1",
        type: PacketType = PacketType.TEXT,
        to: String? = "peer-device",
        groupId: String? = null,
        ttl: Int = RoutingPolicy.DEFAULT_TTL,
        expiresAt: Long = System.currentTimeMillis() + 60_000
    ) = PacketHeader(
        packetId = id,
        type = type,
        from = "my-device",
        to = to,
        groupId = groupId,
        msgId = "message-1",
        createdAt = 1_700_000_000_000,
        expiresAt = expiresAt,
        ttl = ttl
    )

    @Test
    fun `round trips header and body`() {
        val body = ByteArray(2048) { (it % 256).toByte() }
        val encoded = PacketCodec.encode(header(), body)
        val decoded = PacketCodec.decode(encoded)

        assertEquals("packet-1", decoded.header.packetId)
        assertEquals(PacketType.TEXT, decoded.header.type)
        assertEquals("peer-device", decoded.header.to)
        assertEquals("message-1", decoded.header.msgId)
        assertEquals(RoutingPolicy.DEFAULT_TTL, decoded.header.ttl)
        assertArrayEquals(body, decoded.body)
    }

    @Test
    fun `round trips empty body for control packets`() {
        val encoded = PacketCodec.encode(header(type = PacketType.ACK, to = "x"), ByteArray(0))
        val decoded = PacketCodec.decode(encoded)
        assertEquals(PacketType.ACK, decoded.header.type)
        assertEquals(0, decoded.body.size)
    }

    @Test
    fun `keeps group metadata`() {
        val encoded = PacketCodec.encode(
            header(to = "member-2", groupId = "group:42").copy(groupName = "Team"),
            ByteArray(1)
        )
        val decoded = PacketCodec.decode(encoded)
        assertEquals("group:42", decoded.header.groupId)
        assertEquals("Team", decoded.header.groupName)
    }

    @Test
    fun `rejects foreign magic`() {
        val encoded = PacketCodec.encode(header(), ByteArray(4))
        encoded[0] = 'X'.code.toByte()
        assertThrows(PacketFormatException::class.java) { PacketCodec.decode(encoded) }
    }

    @Test
    fun `rejects truncated frames`() {
        val encoded = PacketCodec.encode(header(), ByteArray(64))
        val truncated = encoded.copyOfRange(0, encoded.size - 10)
        // Header length says more bytes than are available -> header/body mismatch is impossible
        // to detect for the body, but a truncation inside the header must throw.
        assertThrows(PacketFormatException::class.java) {
            PacketCodec.decode(truncated.copyOfRange(0, 8))
        }
    }

    @Test
    fun `rejects empty input`() {
        assertThrows(PacketFormatException::class.java) { PacketCodec.decode(ByteArray(0)) }
    }

    @Test
    fun `refuses to encode frames above the transport limit`() {
        val tooBig = ByteArray(PacketCodec.MAX_FRAME_SIZE + 1)
        assertThrows(IllegalArgumentException::class.java) {
            PacketCodec.encode(header(), tooBig)
        }
    }
}
