package com.offlinemesh.messenger.call

/**
 * Pure, JVM-testable 1:1 call state machine, signal deduplicator, and terminal transition guards.
 */
enum class CallPhase {
    IDLE,
    OUTGOING_RINGING,
    INCOMING_RINGING,
    CONNECTING,
    RECONNECTING,
    ACTIVE,
    ENDED
}

data class CallSessionState(
    val phase: CallPhase = CallPhase.IDLE,
    val callId: String = "",
    val localConversationId: String = "",
    val onlineConversationId: String = "",
    val peerUserId: String = "",
    val peerName: String = "",
    val isVideo: Boolean = false,
    val isOutgoing: Boolean = true,
    val startedAtMs: Long = 0L,
    val connectedAtMs: Long? = null,
    val micMuted: Boolean = false,
    val cameraEnabled: Boolean = false,
    val speakerEnabled: Boolean = false,
    val usingFrontCamera: Boolean = true,
    val turnConfigured: Boolean = false,
    val endReason: String? = null,
    val durationSec: Int = 0
) {
    val isInProgress: Boolean
        get() = phase in setOf(
            CallPhase.OUTGOING_RINGING,
            CallPhase.INCOMING_RINGING,
            CallPhase.CONNECTING,
            CallPhase.RECONNECTING,
            CallPhase.ACTIVE
        )
}

class CallStateMachine {

    companion object {
        const val RING_TIMEOUT_MS = 60_000L
        private val TERMINAL_REMOTE_STATES = setOf("ended", "declined", "missed", "failed", "busy")
    }

    private val processedSignals = LinkedHashSet<Long>()

    @Synchronized
    fun markSignalProcessed(signalId: Long): Boolean {
        if (signalId <= 0L) return true
        if (!processedSignals.add(signalId)) return false
        while (processedSignals.size > 512) {
            val first = processedSignals.iterator().next()
            processedSignals.remove(first)
        }
        return true
    }

    @Synchronized
    fun resetSignals() {
        processedSignals.clear()
    }

    fun isExpiredRing(createdAtMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean =
        nowMs - createdAtMs > RING_TIMEOUT_MS + 5_000L

    fun isTerminalRemoteState(remoteState: String): Boolean =
        remoteState.lowercase() in TERMINAL_REMOTE_STATES

    fun canStartCall(current: CallSessionState, conversationType: String): Result<Unit> {
        if (conversationType != "DIRECT") {
            return Result.failure(IllegalArgumentException("only_direct_calls_supported"))
        }
        if (current.isInProgress) {
            return Result.failure(IllegalStateException("call_already_active"))
        }
        return Result.success(Unit)
    }

    fun onOutgoingCreated(
        callId: String,
        localConversationId: String,
        onlineConversationId: String,
        peerUserId: String,
        peerName: String,
        isVideo: Boolean,
        turnConfigured: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ): CallSessionState {
        resetSignals()
        return CallSessionState(
            phase = CallPhase.OUTGOING_RINGING,
            callId = callId,
            localConversationId = localConversationId,
            onlineConversationId = onlineConversationId,
            peerUserId = peerUserId,
            peerName = peerName,
            isVideo = isVideo,
            isOutgoing = true,
            startedAtMs = nowMs,
            cameraEnabled = isVideo,
            speakerEnabled = isVideo,
            turnConfigured = turnConfigured
        )
    }

    fun onIncomingOffer(
        current: CallSessionState,
        callId: String,
        localConversationId: String,
        onlineConversationId: String,
        peerUserId: String,
        peerName: String,
        isVideo: Boolean,
        nowMs: Long = System.currentTimeMillis()
    ): CallSessionState? {
        if (current.isInProgress && current.callId != callId) {
            // Busy on another call; caller receives a "busy" rejection.
            return null
        }
        if (current.callId == callId && current.isInProgress) return current
        resetSignals()
        return CallSessionState(
            phase = CallPhase.INCOMING_RINGING,
            callId = callId,
            localConversationId = localConversationId,
            onlineConversationId = onlineConversationId,
            peerUserId = peerUserId,
            peerName = peerName,
            isVideo = isVideo,
            isOutgoing = false,
            startedAtMs = nowMs,
            cameraEnabled = isVideo,
            speakerEnabled = isVideo
        )
    }

    fun onConnecting(current: CallSessionState, turnConfigured: Boolean = current.turnConfigured): CallSessionState {
        if (!current.isInProgress) return current
        return current.copy(phase = CallPhase.CONNECTING, turnConfigured = turnConfigured)
    }

    fun onIceConnected(current: CallSessionState, nowMs: Long = System.currentTimeMillis()): CallSessionState {
        if (!current.isInProgress) return current
        return current.copy(
            phase = CallPhase.ACTIVE,
            connectedAtMs = current.connectedAtMs ?: nowMs
        )
    }

    fun onIceDisconnected(current: CallSessionState): CallSessionState {
        if (current.phase != CallPhase.ACTIVE && current.phase != CallPhase.CONNECTING) return current
        return current.copy(phase = CallPhase.RECONNECTING)
    }

    fun onEnded(
        current: CallSessionState,
        reason: String,
        nowMs: Long = System.currentTimeMillis()
    ): CallSessionState {
        if (current.phase == CallPhase.IDLE || current.phase == CallPhase.ENDED) return current
        val duration = current.connectedAtMs?.let { ((nowMs - it) / 1000L).toInt().coerceAtLeast(0) } ?: 0
        return current.copy(
            phase = CallPhase.ENDED,
            endReason = reason,
            durationSec = duration
        )
    }
}
