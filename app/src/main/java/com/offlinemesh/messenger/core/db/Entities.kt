package com.offlinemesh.messenger.core.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Chat/contact metadata. Everything in this database is encrypted at rest by SQLCipher;
 * the passphrase is a random 256-bit key wrapped by a non-exportable Android Keystore key.
 *
 * A conversation exists locally whether it came from the mesh or from the online backend. When
 * it is mirrored online, [onlineId] holds the server uuid and [onlineType] the server-side
 * conversation type; a purely local mesh chat has no online id and never touches the network.
 */
@Entity(
    tableName = "conversations",
    indices = [Index("onlineId")]
)
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val isGroup: Boolean,
    /** Comma separated device ids (peer device id for 1:1, members for groups). */
    val memberIds: String,
    val createdAt: Long,
    val lastMessageAt: Long = 0L,
    val lastMessagePreview: String = "",
    val unreadCount: Int = 0,
    /** DIRECT | GROUP | CHANNEL */
    @ColumnInfo(defaultValue = "DIRECT") val type: String = "DIRECT",
    /** Server conversation uuid, null until the chat is linked to the online backend. */
    @ColumnInfo(defaultValue = "NULL") val onlineId: String? = null,
    /** Other participant's server user id for DIRECT conversations. */
    @ColumnInfo(defaultValue = "NULL") val peerUserId: String? = null,
    @ColumnInfo(defaultValue = "0") val muted: Boolean = false,
    @ColumnInfo(defaultValue = "0") val pinned: Boolean = false,
    @ColumnInfo(defaultValue = "0") val archived: Boolean = false,
    @ColumnInfo(defaultValue = "''") val draft: String = "",
    /** Comma separated server user ids, kept in sync with the online membership table. */
    @ColumnInfo(defaultValue = "''") val memberUserIds: String = "",
    /** Group encryption protocol: 1 = pairwise fan-out (legacy), 2 = Signal Sender Keys. */
    @ColumnInfo(defaultValue = "1") val encryptionVersion: Int = 1,
    @ColumnInfo(defaultValue = "1") val senderKeyEpoch: Int = 1,
    @ColumnInfo(defaultValue = "NULL") val distributionId: String? = null,
    @ColumnInfo(defaultValue = "NULL") val mutedUntil: Long? = null,
    @ColumnInfo(defaultValue = "'member'") val myRole: String = "member"
) {
    val isChannel: Boolean get() = type == "CHANNEL"
}

@Entity(
    tableName = "messages",
    indices = [Index("conversationId"), Index("packetId"), Index("clientMsgId"), Index("remoteId")]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val senderDeviceId: String,
    val outgoing: Boolean,
    /** TEXT | IMAGE | VIDEO | AUDIO | VOICE | FILE | SYSTEM | CALL */
    val type: String,
    val body: String,
    val timestamp: Long,
    /** QUEUED | SENDING | SENT | DELIVERED | FAILED | RECEIVED */
    val status: String,
    val filePath: String? = null,
    val fileName: String? = null,
    val fileSize: Long? = null,
    val durationMs: Long? = null,
    val packetId: String? = null,
    /** Idempotency key shared with the backend (unique per conversation server-side). */
    @ColumnInfo(defaultValue = "''") val clientMsgId: String = "",
    /** Server message uuid once the backend accepted the row. */
    @ColumnInfo(defaultValue = "NULL") val remoteId: String? = null,
    /** MESH | ONLINE | BOTH - which transports carried this message. */
    @ColumnInfo(defaultValue = "MESH") val transport: String = "MESH",
    @ColumnInfo(defaultValue = "NULL") val editedAt: Long? = null,
    @ColumnInfo(defaultValue = "NULL") val deletedAt: Long? = null,
    @ColumnInfo(defaultValue = "NULL") val replyToId: String? = null,
    @ColumnInfo(defaultValue = "0") val starred: Boolean = false,
    /** Compact reaction summary, e.g. "👍:2,❤️:1". */
    @ColumnInfo(defaultValue = "''") val reactions: String = "",
    /** Actionable failure reason (identity_changed, blocked, rate_limited, ...). */
    @ColumnInfo(defaultValue = "NULL") val error: String? = null,
    @ColumnInfo(defaultValue = "NULL") val deliveredAt: Long? = null,
    @ColumnInfo(defaultValue = "NULL") val readAt: Long? = null,
    /** Phase 2: online E2EE media attachment & resumable transfer state. */
    @ColumnInfo(defaultValue = "NULL") val fileMime: String? = null,
    @ColumnInfo(defaultValue = "''") val fileSha256: String = "",
    @ColumnInfo(defaultValue = "NULL") val attachmentRemoteId: String? = null,
    @ColumnInfo(defaultValue = "NULL") val remoteObjectPath: String? = null,
    @ColumnInfo(defaultValue = "NULL") val mediaKeyBase64: String? = null,
    @ColumnInfo(defaultValue = "NULL") val mediaIvBase64: String? = null,
    @ColumnInfo(defaultValue = "NULL") val cipherSha256: String? = null,
    @ColumnInfo(defaultValue = "NULL") val cipherSize: Long? = null,
    @ColumnInfo(defaultValue = "NULL") val thumbnailBase64: String? = null,
    @ColumnInfo(defaultValue = "NULL") val mediaWidth: Int? = null,
    @ColumnInfo(defaultValue = "NULL") val mediaHeight: Int? = null,
    @ColumnInfo(defaultValue = "NULL") val transferState: String? = null,
    @ColumnInfo(defaultValue = "0") val transferBytes: Long = 0L
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
    val lastSeenAt: Long = 0L,
    /** Server user uuid once the contact is linked to an account (null for mesh-only peers). */
    @ColumnInfo(defaultValue = "NULL") val userId: String? = null,
    @ColumnInfo(defaultValue = "''") val username: String = ""
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
    val createdAt: Long = 0L,
    /** SHA-256 of the plaintext, verified after reassembly. */
    @ColumnInfo(defaultValue = "''") val plaintextSha256: String = ""
)

@Entity(tableName = "key_values")
data class KeyValueEntity(
    @PrimaryKey val key: String,
    val value: String
)

/**
 * Outgoing work that must survive a process death: today, messages that still have to be
 * uploaded to the backend. The payload only references a local message id - encryption happens
 * when the job runs, so a rotated pre-key never produces stale ciphertext.
 */
@Entity(tableName = "online_outbox", indices = [Index("nextAttemptAt")])
data class OutboxEntity(
    @PrimaryKey val id: String,
    /** MESSAGE | RECEIPT | READ | DELETE */
    val kind: String,
    val conversationId: String,
    val messageId: String,
    val attempts: Int = 0,
    val nextAttemptAt: Long = 0L,
    val lastError: String? = null,
    val createdAt: Long
)

/** Cursors and flags for the online transport (last pulled timestamp, account id, ...). */
@Entity(tableName = "sync_state")
data class SyncStateEntity(
    @PrimaryKey val key: String,
    val value: String
)

/** Cached devices of conversation peers, so envelope fan-out does not need a round trip. */
@Entity(tableName = "peer_devices", indices = [Index("userId"), Index("deviceId")])
data class PeerDeviceEntity(
    /** Server device row uuid. */
    @PrimaryKey val id: String,
    val userId: String,
    val deviceId: String,
    val identityKey: String,
    val revoked: Boolean = false,
    val lastSeenAt: Long = 0L,
    @ColumnInfo(defaultValue = "''") val name: String = "",
    @ColumnInfo(defaultValue = "'android'") val platform: String = "android",
    @ColumnInfo(defaultValue = "0") val verified: Boolean = false
)

/** Cached public profile of a peer account. */
@Entity(tableName = "peer_profiles")
data class PeerProfileEntity(
    @PrimaryKey val userId: String,
    val username: String = "",
    val displayName: String = "",
    val avatarUrl: String? = null,
    val updatedAt: Long = 0L
)

/** Audited libsignal SenderKeyRecord persistence keyed by (remoteName, deviceId, distributionId). */
@Entity(
    tableName = "signal_sender_keys",
    primaryKeys = ["remoteName", "deviceId", "distributionId"]
)
data class SenderKeyEntity(
    val remoteName: String,
    val deviceId: Int,
    val distributionId: String,
    val recordBase64: String
)

/** Tracks which peer devices have received our pairwise-encrypted SKDM in a given group epoch. */
@Entity(
    tableName = "group_skdm_states",
    primaryKeys = ["conversationId", "epoch", "recipientDeviceRowId"],
    indices = [Index("conversationId")]
)
data class GroupSkdmStateEntity(
    val conversationId: String,
    val epoch: Int,
    val distributionId: String,
    val recipientDeviceRowId: String,
    val sentAt: Long
)

/** Local history of 1:1 WebRTC voice and video calls. */
@Entity(
    tableName = "call_logs",
    indices = [Index("conversationId"), Index("startedAt")]
)
data class CallLogEntity(
    @PrimaryKey val callId: String,
    val conversationId: String,
    val peerUserId: String,
    val peerName: String,
    val kind: String,
    val direction: String,
    val state: String,
    val startedAt: Long,
    val answeredAt: Long? = null,
    val endedAt: Long? = null,
    val durationSec: Int = 0
)
