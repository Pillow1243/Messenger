package com.offlinemesh.messenger.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallStateMachineTest {

    @Test
    fun `rejects group and channel conversations so only 1_to_1 direct calls are allowed`() {
        val sm = CallStateMachine()
        val idle = CallSessionState()
        assertTrue(sm.canStartCall(idle, "DIRECT").isSuccess)
        assertTrue(sm.canStartCall(idle, "GROUP").isFailure)
        assertTrue(sm.canStartCall(idle, "CHANNEL").isFailure)
    }

    @Test
    fun `full outgoing call lifecycle tracks connection, ICE reconnection, and call duration`() {
        val sm = CallStateMachine()
        val ringing = sm.onOutgoingCreated(
            callId = "call-1",
            localConversationId = "dev-bob",
            onlineConversationId = "online-conv-1",
            peerUserId = "user-bob",
            peerName = "Bob",
            isVideo = true,
            turnConfigured = false,
            nowMs = 1_000L
        )
        assertEquals(CallPhase.OUTGOING_RINGING, ringing.phase)
        assertTrue(ringing.isInProgress)
        assertTrue(ringing.cameraEnabled)
        assertTrue(sm.canStartCall(ringing, "DIRECT").isFailure)

        val connecting = sm.onConnecting(ringing, turnConfigured = true)
        assertEquals(CallPhase.CONNECTING, connecting.phase)
        assertTrue(connecting.turnConfigured)

        val active = sm.onIceConnected(connecting, nowMs = 5_000L)
        assertEquals(CallPhase.ACTIVE, active.phase)
        assertEquals(5_000L, active.connectedAtMs)

        val reconnecting = sm.onIceDisconnected(active)
        assertEquals(CallPhase.RECONNECTING, reconnecting.phase)

        val recovered = sm.onIceConnected(reconnecting, nowMs = 12_000L)
        assertEquals(CallPhase.ACTIVE, recovered.phase)
        // Original connectedAtMs is preserved across temporary ICE hiccups
        assertEquals(5_000L, recovered.connectedAtMs)

        val ended = sm.onEnded(recovered, reason = "hangup", nowMs = 68_000L)
        assertEquals(CallPhase.ENDED, ended.phase)
        assertFalse(ended.isInProgress)
        assertEquals(63, ended.durationSec)
    }

    @Test
    fun `incoming call while busy returns null so caller receives busy decline`() {
        val sm = CallStateMachine()
        val first = sm.onIncomingOffer(
            current = CallSessionState(),
            callId = "call-a",
            localConversationId = "conv-a",
            onlineConversationId = "online-a",
            peerUserId = "user-a",
            peerName = "Alice",
            isVideo = false,
            nowMs = 10_000L
        )
        assertNotNull(first)
        assertEquals(CallPhase.INCOMING_RINGING, first?.phase)

        // Duplicate Realtime/push event for the SAME callId is idempotent
        val duplicateSame = sm.onIncomingOffer(
            current = first!!,
            callId = "call-a",
            localConversationId = "conv-a",
            onlineConversationId = "online-a",
            peerUserId = "user-a",
            peerName = "Alice",
            isVideo = false,
            nowMs = 11_000L
        )
        assertEquals(first, duplicateSame)

        // Second concurrent caller while busy returns null
        val concurrentOther = sm.onIncomingOffer(
            current = first,
            callId = "call-b",
            localConversationId = "conv-b",
            onlineConversationId = "online-b",
            peerUserId = "user-b",
            peerName = "Charlie",
            isVideo = true,
            nowMs = 12_000L
        )
        assertNull(concurrentOther)
    }

    @Test
    fun `signal deduplication and expired ring guards prevent stale call replays`() {
        val sm = CallStateMachine()
        assertTrue(sm.markSignalProcessed(101L))
        assertFalse("Duplicate signal row id must be ignored", sm.markSignalProcessed(101L))
        assertTrue(sm.markSignalProcessed(102L))

        assertFalse(sm.isExpiredRing(createdAtMs = 100_000L, nowMs = 130_000L))
        assertTrue(sm.isExpiredRing(createdAtMs = 100_000L, nowMs = 170_000L))

        assertTrue(sm.isTerminalRemoteState("ended"))
        assertTrue(sm.isTerminalRemoteState("declined"))
        assertTrue(sm.isTerminalRemoteState("missed"))
        assertFalse(sm.isTerminalRemoteState("ringing"))
        assertFalse(sm.isTerminalRemoteState("active"))
    }
}
