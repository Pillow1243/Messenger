package com.offlinemesh.messenger.core.mesh

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameRoutingTest {

    private val me = "device-me"
    private val now = 1_700_000_000_000L

    private fun header(
        type: PacketType = PacketType.TEXT,
        from: String = "device-a",
        to: String? = me,
        ttl: Int = RoutingPolicy.DEFAULT_TTL,
        expiresAt: Long = now + 10_000,
        groupId: String? = null
    ) = PacketHeader(
        packetId = "p1",
        type = type,
        from = from,
        to = to,
        groupId = groupId,
        msgId = "m1",
        createdAt = now,
        expiresAt = expiresAt,
        ttl = ttl
    )

    private fun decide(header: PacketHeader, duplicate: Boolean = false) =
        FrameRouting.decide(header, me, now, duplicate)

    @Test
    fun `a packet for this device is delivered and not passed on`() {
        assertEquals(FrameAction.DELIVER, decide(header()))
        assertEquals(FrameAction.DELIVER, decide(header(type = PacketType.FILE_CHUNK)))
    }

    @Test
    fun `a packet for somebody else is relayed`() {
        assertEquals(FrameAction.RELAY, decide(header(to = "device-b")))
    }

    @Test
    fun `a packet for somebody else with no hops left is dropped`() {
        assertEquals(FrameAction.DROP, decide(header(to = "device-b", ttl = 1)))
    }

    @Test
    fun `expired packets and our own echoes are dropped`() {
        assertEquals(FrameAction.DROP, decide(header(expiresAt = now - 1)))
        assertEquals(FrameAction.DROP, decide(header(from = me, to = "device-b")))
    }

    @Test
    fun `a receipt for this device goes to the handler`() {
        assertEquals(FrameAction.ACK_FOR_ME, decide(header(type = PacketType.ACK)))
    }

    @Test
    fun `a receipt for somebody else is carried, so multi-hop messages can be confirmed`() {
        assertEquals(FrameAction.RELAY, decide(header(type = PacketType.ACK, to = "device-b")))
        assertEquals(FrameAction.DROP, decide(header(type = PacketType.ACK, to = "device-b", ttl = 1)))
    }

    @Test
    fun `a repeated data packet for this device is shown again so the receipt can be repeated`() {
        assertEquals(FrameAction.DUPLICATE_FOR_ME, decide(header(), duplicate = true))
        assertEquals(FrameAction.DUPLICATE_FOR_ME, decide(header(type = PacketType.VOICE), duplicate = true))
    }

    @Test
    fun `a repeated packet is never relayed again and a repeated receipt is ignored`() {
        assertEquals(FrameAction.DROP, decide(header(to = "device-b"), duplicate = true))
        assertEquals(FrameAction.DROP, decide(header(type = PacketType.ACK), duplicate = true))
        assertEquals(FrameAction.DROP, decide(header(type = PacketType.HELLO), duplicate = true))
    }

    @Test
    fun `a hello is handled by the transport`() {
        assertEquals(FrameAction.HELLO, decide(header(type = PacketType.HELLO, to = null)))
    }

    @Test
    fun `an unaddressed group packet is delivered and relayed`() {
        assertEquals(FrameAction.DELIVER_AND_RELAY, decide(header(to = null, groupId = "g1")))
    }
}
