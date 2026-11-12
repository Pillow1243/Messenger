package com.offlinemesh.messenger.core.mesh

import kotlinx.coroutines.flow.StateFlow

/**
 * What the message layer needs from the radio: who we are, who is in range, and a way to hand a
 * frame to one neighbour or to all of them. [MeshManager] implements it on top of Nearby
 * Connections; tests implement it with an in-process link so that the real message layer of
 * several devices can be run against each other.
 */
interface MeshLink {
    val peerFlow: StateFlow<List<PeerInfo>>
    val running: StateFlow<Boolean>

    fun deviceId(): String

    /** Sends to a directly connected device. False when it is not in range or the write failed. */
    fun sendTo(deviceId: String, header: PacketHeader, body: ByteArray): Boolean

    /** Sends to every neighbour except [exceptEndpointId]; returns how many were written to. */
    fun broadcast(header: PacketHeader, body: ByteArray, exceptEndpointId: String? = null): Int

    fun isConnected(deviceId: String): Boolean

    fun connectedDeviceIds(): Set<String>
}
