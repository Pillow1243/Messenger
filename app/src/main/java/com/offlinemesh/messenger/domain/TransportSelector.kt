package com.offlinemesh.messenger.domain

/** The transports a single outgoing message can use. */
enum class Transport { MESH, ONLINE, BOTH, QUEUE }

/**
 * Inputs that decide the transport. Everything here is observable at send time, which keeps the
 * decision pure - and therefore unit-testable without Android.
 */
data class TransportContext(
    val signedIn: Boolean,
    val networkAvailable: Boolean,
    val conversationLinkedOnline: Boolean,
    val meshPeerConnected: Boolean
)

/**
 * Chooses how to deliver a message.
 *
 * Rules, in order:
 *  1. both transports usable  -> BOTH (fastest delivery wins, duplicates are de-duplicated by
 *     message id on the receiving side)
 *  2. online usable           -> ONLINE (the server stores the envelope until the peer connects)
 *  3. mesh peer in range      -> MESH
 *  4. neither                 -> QUEUE (kept on device, retried by the mesh store-and-forward
 *     queue and the online outbox)
 *
 * Note that a linked online conversation is *not* enough on its own: without a signed-in session
 * or without a network the selector falls back to the mesh instead of pretending to send.
 */
object TransportSelector {

    fun select(context: TransportContext): Transport {
        val onlineUsable = context.signedIn && context.networkAvailable && context.conversationLinkedOnline
        return when {
            onlineUsable && context.meshPeerConnected -> Transport.BOTH
            onlineUsable -> Transport.ONLINE
            context.meshPeerConnected -> Transport.MESH
            else -> Transport.QUEUE
        }
    }

    /** True when at least one transport can attempt delivery right now. */
    fun canAttemptNow(transport: Transport): Boolean = transport != Transport.QUEUE
}

/** High level state of the online transport, exposed to the UI. */
sealed interface OnlineState {
    data object SignedOut : OnlineState
    data object Offline : OnlineState
    data object Connecting : OnlineState
    data class Connected(val userId: String, val email: String?) : OnlineState
    data class Error(val reason: String) : OnlineState
}
