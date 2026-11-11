package com.offlinemesh.messenger.core.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Chat/contact metadata. Everything in this database is encrypted at rest by SQLCipher;
 * the passphrase is a random 256-bit key wrapped by a non-exportable Android Keystore key.
 */
@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val isGroup: Boolean,
    /** Comma separated device ids (peer device id for 1:1, members for groups). */
    val memberIds: String,
    val createdAt: Long,
    val lastMessageAt: Long = 0L,
    val lastMessagePreview: String = "",
    val unreadCount: Int = 0
)

@Entity(
    tableName = "messages",
    indices = [Index("conversationId"), Index("packetId")]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val senderDeviceId: String,
    val outgoing: Boolean,
    /** TEXT | FILE | VOICE */
    val type: String,
    val body: String,
    val timestamp: Long,
    /** QUEUED | SENDING | SENT | DELIVERED | FAILED | RECEIVED */
    val status: String,
    val filePath: String? = null,
    val fileName: String? = null,
    val fileSize: Long? = null,
    val durationMs: Long? = null,
    val packetId: String? = null
)

@Entity(tableName = "contacts")
data class ContactEntity(
    @PrimaryKey val deviceId: String,
    val displayName: String,
    /** Base64 of the peer's Signal identity public key (33 bytes). */
    val identityKeyBase64: String = "",
    val registrationId: Int = 0,
    /** Set when the user compared safety numbers in person. */
    val verified: Boolean = false,
    /** Set when libsignal reports that a known contact presented a new identity key. */
    val identityChanged: Boolean = false,
    val addedAt: Long,
    val lastSeenAt: Long = 0L
)

/** Packets waiting for store-and-forward delivery (always ciphertext, never plaintext). */
@Entity(tableName = "relay_queue", indices = [Index("targetDeviceId")])
data class RelayEntity(
    @PrimaryKey val packetId: String,
    /** Serialized packet header (JSON), no plaintext body. */
    val headerJson: String,
    /** Base64 of the encrypted body. */
    val bodyBase64: String,
    /** Message id, so an ACK can clear the queued ciphertext. */
    val msgId: String = "",
    /** null = broadcast to anyone who can forward it. */
    val targetDeviceId: String?,
    val createdAt: Long,
    val expiresAt: Long,
    val attempts: Int = 0
)

/** Duplicate-suppression ring for mesh flooding. */
@Entity(tableName = "seen_packets")
data class SeenPacketEntity(
    @PrimaryKey val packetId: String,
    val seenAt: Long
)

@Entity(tableName = "signal_identities", primaryKeys = ["remoteName", "deviceId"])
data class IdentityEntity(
    val remoteName: String,
    val deviceId: Int,
    val identityKeyBase64: String
)

@Entity(tableName = "signal_sessions", primaryKeys = ["remoteName", "deviceId"])
data class SessionEntity(
    val remoteName: String,
    val deviceId: Int,
    val recordBase64: String
)

@Entity(tableName = "signal_prekeys")
data class PreKeyEntity(
    @PrimaryKey val keyId: Int,
    val recordBase64: String
)

@Entity(tableName = "signal_signed_prekeys")
data class SignedPreKeyEntity(
    @PrimaryKey val keyId: Int,
    val recordBase64: String
)

@Entity(tableName = "signal_kyber_prekeys")
data class KyberPreKeyEntity(
    @PrimaryKey val keyId: Int,
    val recordBase64: String
)

@Entity(tableName = "file_transfers")
data class FileTransferEntity(
    @PrimaryKey val fileId: String,
    val conversationId: String,
    val fileName: String,
    val totalChunks: Int,
    val receivedChunks: Int,
    val status: String,
    val outputPath: String,
    val createdAt: Long = 0L
)

@Entity(tableName = "key_values")
data class KeyValueEntity(
    @PrimaryKey val key: String,
    val value: String
)
