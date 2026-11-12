package com.offlinemesh.messenger.core.mesh

/** What a device does with a frame that arrived from a neighbour. */
enum class FrameAction {
    /** Expired, malformed for this protocol version, our own echo, or a copy nobody needs. */
    DROP,

    /** Link handshake; handled by the transport itself. */
    HELLO,

    /** A receipt addressed to this device. */
    ACK_FOR_ME,

    /** A data packet addressed to this device. */
    DELIVER,

    /** A packet for somebody else: store it and pass it on. */
    RELAY,

    /** A group broadcast: for this device and for others. */
    DELIVER_AND_RELAY,

    /** A data packet for this device that was already seen once (the sender is repeating it). */
    DUPLICATE_FOR_ME
}

/**
 * The routing decision for an incoming frame, kept apart from the radio code so that it can be
 * tested and so that tests which connect several devices in one process route exactly as the
 * real transport does.
 */
object FrameRouting {

    /** Packet types a sender repeats until they are acknowledged. */
    val RETRANSMITTED_TYPES: Set<PacketType> =
        setOf(PacketType.TEXT, PacketType.VOICE, PacketType.FILE_CHUNK)

    /** False for frames that are not even looked up in the duplicate cache. */
    fun acceptable(header: PacketHeader, myDeviceId: String, now: Long): Boolean =
        RoutingPolicy.isUsable(header, now) && !RoutingPolicy.isFromSelf(header, myDeviceId)

    /**
     * @param duplicate whether this packet id was seen before (in memory or in the database).
     */
    fun decide(header: PacketHeader, myDeviceId: String, now: Long, duplicate: Boolean): FrameAction {
        if (!acceptable(header, myDeviceId, now)) return FrameAction.DROP
        if (duplicate) {
            // A copy of something already seen is never forwarded again. When it is addressed to
            // this device it is still shown to the handler: the sender repeats a packet until it
            // is acknowledged, so a repeat usually means the acknowledgement was lost.
            return if (header.to == myDeviceId && header.type in RETRANSMITTED_TYPES) {
                FrameAction.DUPLICATE_FOR_ME
            } else {
                FrameAction.DROP
            }
        }
        val forward = RoutingPolicy.shouldForward(header, myDeviceId, now)
        return when (header.type) {
            PacketType.HELLO -> FrameAction.HELLO
            PacketType.ACK -> when {
                header.to == myDeviceId -> FrameAction.ACK_FOR_ME
                // Somebody else's receipt is carried like any other packet; otherwise a message
                // that needed a relay could never be confirmed.
                forward -> FrameAction.RELAY
                else -> FrameAction.DROP
            }
            else -> {
                val mine = RoutingPolicy.isForMe(header, myDeviceId)
                when {
                    mine && forward -> FrameAction.DELIVER_AND_RELAY
                    mine -> FrameAction.DELIVER
                    forward -> FrameAction.RELAY
                    else -> FrameAction.DROP
                }
            }
        }
    }
}
