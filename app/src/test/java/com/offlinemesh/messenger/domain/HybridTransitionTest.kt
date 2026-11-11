package com.offlinemesh.messenger.domain

import com.offlinemesh.messenger.core.mesh.PacketHeader
import com.offlinemesh.messenger.core.mesh.PacketType
import com.offlinemesh.messenger.core.mesh.RoutingPolicy
import com.offlinemesh.messenger.core.mesh.SeenPacketCache
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies all 10 offline mesh <-> online Supabase transition invariants deterministically.
 */
class HybridTransitionTest {

    @Test
    fun `scenario 1 & 2 - mesh to online and online to mesh select unified transports cleanly`() {
        // Scenario 1: Paired & chatting offline first, then user signs in & links conversation
        val meshFirst = TransportSelector.select(
            TransportContext(onlineSignedIn = false, networkAvailable = false, conversationLinkedOnline = false, peerDirectlyConnected = true)
        )
        assertEquals(Transport.MESH, meshFirst)

        val linkedWithBoth = TransportSelector.select(
            TransportContext(onlineSignedIn = true, networkAvailable = true, conversationLinkedOnline = true, peerDirectlyConnected = true)
        )
        assertEquals(Transport.BOTH, linkedWithBoth)

        // Scenario 2: Chatted online first, then internet drops and peers meet on Nearby mesh
        val onlineFirst = TransportSelector.select(
            TransportContext(onlineSignedIn = true, networkAvailable = true, conversationLinkedOnline = true, peerDirectlyConnected = false)
        )
        assertEquals(Transport.ONLINE, onlineFirst)

        val metInFieldOffline = TransportSelector.select(
            TransportContext(onlineSignedIn = true, networkAvailable = false, conversationLinkedOnline = true, peerDirectlyConnected = true)
        )
        assertEquals(Transport.MESH, metInFieldOffline)
    }

    @Test
    fun `scenario 3 & 4 - zero connectivity queues locally and mid-chat drop falls back to mesh`() {
        // Scenario 3: Completely offline and peer out of range -> QUEUE locally
        val queued = TransportSelector.select(
            TransportContext(onlineSignedIn = true, networkAvailable = false, conversationLinkedOnline = true, peerDirectlyConnected = false)
        )
        assertEquals(Transport.QUEUE, queued)
        assertFalse(TransportSelector.canAttemptNow(queued))

        // Scenario 4: Wi-Fi drops mid-conversation while peer is still connected via BLE/Wi-Fi Direct
        val fallback = TransportSelector.select(
            TransportContext(onlineSignedIn = true, networkAvailable = false, conversationLinkedOnline = true, peerDirectlyConnected = true)
        )
        assertEquals(Transport.MESH, fallback)
        assertTrue(TransportSelector.canAttemptNow(fallback))
    }

    @Test
    fun `scenario 5 & 8 - crash recovery and cross-transport receipt reconciliation are monotonic`() {
        // Scenario 5 & 8: A message delivered on mesh must never regress when online retry/receipt arrives late
        var status = "QUEUED"
        status = HybridReconciler.reconcileStatus(status, "SENDING")
        assertEquals("SENDING", status)
        status = HybridReconciler.reconcileStatus(status, "DELIVERED")
        assertEquals("DELIVERED", status)

        // Late online SENT or retry QUEUED must not downgrade DELIVERED
        assertEquals("DELIVERED", HybridReconciler.reconcileStatus(status, "SENT"))
        assertEquals("DELIVERED", HybridReconciler.reconcileStatus(status, "QUEUED"))
        assertEquals("DELIVERED", HybridReconciler.reconcileStatus(status, "FAILED"))

        // Earliest confirmed delivery timestamp wins across mesh ACK and online receipt
        assertEquals(1_500L, HybridReconciler.reconcileTimestamp(2_000L, 1_500L))
        assertEquals(1_500L, HybridReconciler.reconcileTimestamp(1_500L, 2_200L))
        assertEquals(1_500L, HybridReconciler.reconcileTimestamp(null, 1_500L))
    }

    @Test
    fun `scenario 6 & 10 - cross-transport dedup provenance and deterministic timeline ordering`() {
        // Commutative transport merge when the same clientMsgId arrives over both mesh and online
        assertEquals("BOTH", HybridReconciler.mergeTransport("MESH", "ONLINE"))
        assertEquals("BOTH", HybridReconciler.mergeTransport("ONLINE", "MESH"))
        assertEquals("BOTH", HybridReconciler.mergeTransport("BOTH", "MESH"))
        assertEquals("MESH", HybridReconciler.mergeTransport("MESH", "MESH"))
        assertEquals("ONLINE", HybridReconciler.mergeTransport("ONLINE", "ONLINE"))

        // Deterministic ordering across multi-device / out-of-order arrivals even on identical timestamps
        data class Entry(val ts: Long, val clientId: String, val id: String)
        val a = Entry(10_000L, "c-1", "id-9")
        val b = Entry(10_000L, "c-2", "id-1")
        val c = Entry(9_999L, "c-9", "id-5")

        val comparator = Comparator<Entry> { x, y ->
            HybridReconciler.compareMessages(x.ts, x.clientId, x.id, y.ts, y.clientId, y.id)
        }
        val order1 = listOf(a, b, c).sortedWith(comparator)
        val order2 = listOf(b, c, a).sortedWith(comparator)
        assertEquals(listOf(c, a, b), order1)
        assertEquals(order1, order2)
    }

    @Test
    fun `scenario 7 & 9 - store-and-forward relay hygiene and Supabase outage independence`() {
        val now = 1_700_000_000_000L
        // Scenario 7: SeenPacketCache + RoutingPolicy prevent relay loops and drop TTL=1 / expired packets
        val seen = SeenPacketCache(maxEntries = 128, ttlMs = 60_000L)
        assertTrue(seen.markSeen("pkt-relay-1", now))
        assertFalse("Duplicate relayed packet must be dropped", seen.markSeen("pkt-relay-1", now + 200))

        val ttlOneHeader = PacketHeader(
            packetId = "pkt-ttl-1",
            type = PacketType.TEXT,
            from = "dev-a",
            to = "dev-c",
            msgId = "m-1",
            createdAt = now,
            expiresAt = now + 30_000L,
            ttl = 1
        )
        assertFalse("TTL=1 packet must not be forwarded by intermediate relay", RoutingPolicy.shouldForward(ttlOneHeader, "dev-relay", now))
        assertTrue(RoutingPolicy.shouldForward(ttlOneHeader.copy(ttl = 3), "dev-relay", now))

        // Scenario 9: Supabase completely unreachable -> offline mesh continues with zero online dependency
        val meshDuringOutage = TransportSelector.select(
            TransportContext(
                onlineSignedIn = true,
                networkAvailable = false,
                conversationLinkedOnline = false,
                peerDirectlyConnected = true
            )
        )
        assertEquals(Transport.MESH, meshDuringOutage)
    }
}
