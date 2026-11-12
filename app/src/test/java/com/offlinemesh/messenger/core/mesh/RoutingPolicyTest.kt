package com.offlinemesh.messenger.core.mesh

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingPolicyTest {

    private val me = "device-me"
    private val now = 1_700_000_000_000L

    private fun header(
        from: String = "device-a",
        to: String? = "device-b",
        ttl: Int = RoutingPolicy.DEFAULT_TTL,
        expiresAt: Long = now + 10_000,
        version: Int = MESH_PROTOCOL_VERSION,
        groupId: String? = null
    ) = PacketHeader(
        v = version,
        packetId = "p1",
        type = PacketType.TEXT,
        from = from,
        to = to,
        groupId = groupId,
        msgId = "m1",
        createdAt = now,
        expiresAt = expiresAt,
        ttl = ttl
    )

    @Test
    fun `expired packets are not usable`() {
        assertFalse(RoutingPolicy.isUsable(header(expiresAt = now - 1), now))
        assertTrue(RoutingPolicy.isUsable(header(expiresAt = now + 1), now))
    }

    @Test
    fun `packets with no ttl left are not usable`() {
        assertFalse(RoutingPolicy.isUsable(header(ttl = 0), now))
    }

    @Test
    fun `packets with a different protocol version are dropped`() {
        assertFalse(RoutingPolicy.isUsable(header(version = 99), now))
    }

    @Test
    fun `own packets are recognised`() {
        assertTrue(RoutingPolicy.isFromSelf(header(from = me), me))
        assertFalse(RoutingPolicy.isFromSelf(header(from = "device-b"), me))
    }

    @Test
    fun `direct packets are for me only when addressed to me`() {
        assertTrue(RoutingPolicy.isForMe(header(to = me), me))
        assertFalse(RoutingPolicy.isForMe(header(to = "device-b"), me))
    }

    @Test
    fun `group packets are delivered to every member`() {
        val groupPacket = header(to = null, groupId = "group:1")
        assertTrue(RoutingPolicy.isForMe(groupPacket, me))
        assertTrue(RoutingPolicy.shouldForward(groupPacket, me, now))
    }

    @Test
    fun `direct packet addressed to me is never forwarded`() {
        assertFalse(RoutingPolicy.shouldForward(header(to = me), me, now))
    }

    @Test
    fun `packet for someone else is forwarded by a relay`() {
        assertTrue(RoutingPolicy.shouldForward(header(to = "device-c"), me, now))
    }

    @Test
    fun `relay does not forward packets with ttl one`() {
        assertFalse(RoutingPolicy.shouldForward(header(to = "device-c", ttl = 1), me, now))
    }

    @Test
    fun `relay does not forward expired packets`() {
        assertFalse(RoutingPolicy.shouldForward(header(to = "device-c", expiresAt = now - 5), me, now))
    }

    @Test
    fun `relay never forwards its own packets`() {
        assertFalse(RoutingPolicy.shouldForward(header(from = me, to = "device-c"), me, now))
    }

    @Test
    fun `forwarding decrements ttl and increments hops`() {
        val forwarded = RoutingPolicy.forwarded(header(ttl = 5).copy(hops = 2))
        assertEquals(4, forwarded.ttl)
        assertEquals(3, forwarded.hops)
    }

    @Test
    fun `forwarding never produces a negative ttl`() {
        val forwarded = RoutingPolicy.forwarded(header(ttl = 0))
        assertEquals(0, forwarded.ttl)
    }

    @Test
    fun `a queued packet waits a day for its recipient, not seven minutes`() {
        // Regression: the expiry was computed as DEFAULT_TTL (a hop count) times one minute.
        assertEquals(now + 24 * 60 * 60 * 1000L, RoutingPolicy.expiryFrom(now))
        assertTrue(RoutingPolicy.isUsable(header(expiresAt = RoutingPolicy.expiryFrom(now)), now + 8 * 60_000))
    }

    @Test
    fun `an expiry beyond the store-and-forward window is not honoured`() {
        val far = now + RoutingPolicy.STORE_FORWARD_MS + RoutingPolicy.MAX_EXPIRY_SKEW_MS + 1
        assertFalse(RoutingPolicy.isUsable(header(expiresAt = far), now))
        assertFalse(RoutingPolicy.shouldForward(header(from = "device-x", to = "device-y", expiresAt = far), "device-b", now))
        assertFalse(RoutingPolicy.isUsable(header(expiresAt = Long.MAX_VALUE), now))
    }

    @Test
    fun `a hop limit above the maximum is not honoured`() {
        assertFalse(RoutingPolicy.isUsable(header(ttl = RoutingPolicy.MAX_TTL + 1), now))
        assertTrue(RoutingPolicy.isUsable(header(ttl = RoutingPolicy.MAX_TTL), now))
    }
}
