package com.offlinemesh.messenger.core.mesh

import kotlinx.serialization.Serializable

@Serializable
enum class PacketType {
    TEXT,
    VOICE,
    FILE_CHUNK,
    FILE_COMPLETE,
    CONTROL,
    HEARTBEAT,
    HELLO,
    ACK,
    PREKEY
}

@Serializable
data class MeshPacket(
    val packetId: String,
    val messageId: String,
    val fromDeviceId: String,
    val toDeviceId: String?,
    val groupId: String?,
    val type: PacketType,
    val ttl: Int,
    val hopCount: Int,
    val timestamp: Long,
    val payload: String,
    val metadata: String = ""
) {
    companion object {
        const val DEFAULT_TTL = 7
    }

    fun forwarded(): MeshPacket = copy(ttl = ttl - 1, hopCount = hopCount + 1)
}

@Serializable
data class HelloInfo(
    val displayName: String,
    val identityKey: String,
    val prekeyBundle: String
)

@Serializable
data class FileChunkMeta(
    val fileId: String,
    val fileName: String,
    val index: Int,
    val total: Int
)
