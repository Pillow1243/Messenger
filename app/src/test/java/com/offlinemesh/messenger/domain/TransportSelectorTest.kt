package com.offlinemesh.messenger.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransportSelectorTest {

    private fun ctx(
        signedIn: Boolean = false,
        network: Boolean = false,
        linked: Boolean = false,
        peer: Boolean = false
    ) = TransportContext(signedIn, network, linked, peer)

    @Test
    fun `uses both transports when every input is available`() {
        assertEquals(Transport.BOTH, TransportSelector.select(ctx(true, true, true, true)))
    }

    @Test
    fun `online only when the peer is out of mesh range`() {
        assertEquals(Transport.ONLINE, TransportSelector.select(ctx(true, true, true, false)))
    }

    @Test
    fun `mesh only without an account`() {
        assertEquals(Transport.MESH, TransportSelector.select(ctx(false, true, true, true)))
    }

    @Test
    fun `mesh when the conversation has no server counterpart`() {
        assertEquals(Transport.MESH, TransportSelector.select(ctx(true, true, false, true)))
    }

    @Test
    fun `mesh when the network is gone even if the session is valid`() {
        assertEquals(Transport.MESH, TransportSelector.select(ctx(true, false, true, true)))
    }

    @Test
    fun `queues when no peer is reachable and there is no network`() {
        assertEquals(Transport.QUEUE, TransportSelector.select(ctx(false, false, false, false)))
        assertEquals(Transport.QUEUE, TransportSelector.select(ctx(true, false, true, false)))
    }

    @Test
    fun `online transport alone is not treated as deliverable now`() {
        assertTrue(TransportSelector.canAttemptNow(Transport.ONLINE))
        assertTrue(TransportSelector.canAttemptNow(Transport.MESH))
        assertTrue(TransportSelector.canAttemptNow(Transport.BOTH))
        assertFalse(TransportSelector.canAttemptNow(Transport.QUEUE))
    }
}
