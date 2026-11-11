package com.offlinemesh.messenger.core.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val isGroup: Boolean,
    val createdAt: Long,
    val lastMessageAt: Long = 0L,
    val lastMessagePreview: String = "",
    val unreadCount: Int = 0,
    val memberIds: String = ""
)

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val senderDeviceId: String,
    val type: String,
    val body: String,
    val timestamp: Long,
    val status: String,
    val filePath: String? = null,
    val fileName: String? = null,
    val durationMs: Long? = null,
    val packetId: String? = null
)

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val deviceId: String,
    val displayName: String,
    val identityKeyBase64: String = "",
    val addedAt: Long,
    val lastSeenAt: Long = 0L
)

@Entity(tableName = "outbox")
data class OutboxEntity(
    @PrimaryKey val packetId: String,
    val packetJson: String,
    val toDeviceId: String?,
    val createdAt: Long,
    val retryCount: Int = 0,
    val nextRetryAt: Long = 0L
)

@Entity(tableName = "seen_packets")
data class SeenPacketEntity(
    @PrimaryKey val packetId: String,
    val seenAt: Long
)

@Entity(tableName = "signal_sessions")
data class SessionEntity(
    @PrimaryKey val remoteName: String,
    val deviceId: Int,
    val recordBase64: String
)

@Entity(tableName = "signal_prekeys")
data class PreKeyEntity(
    @PrimaryKey val keyId: Int,
    val publicKeyBase64: String
)

@Entity(tableName = "signal_signed_prekeys")
data class SignedPreKeyEntity(
    @PrimaryKey val keyId: Int,
    val publicKeyBase64: String,
    val signatureBase64: String
)

@Entity(tableName = "signal_identities")
data class IdentityEntity(
    @PrimaryKey val remoteName: String,
    val deviceId: Int,
    val identityKeyBase64: String
)

@Entity(tableName = "file_transfers")
data class FileTransferEntity(
    @PrimaryKey val fileId: String,
    val conversationId: String,
    val fileName: String,
    val totalChunks: Int,
    val receivedChunks: Int,
    val status: String,
    val outputPath: String
)

@Entity(tableName = "key_values")
data class KeyValueEntity(
    @PrimaryKey val key: String,
    val value: String
)
