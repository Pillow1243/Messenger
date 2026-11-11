package com.offlinemesh.messenger.core.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [
        ConversationEntity::class,
        MessageEntity::class,
        ContactEntity::class,
        RelayEntity::class,
        SeenPacketEntity::class,
        IdentityEntity::class,
        SessionEntity::class,
        PreKeyEntity::class,
        SignedPreKeyEntity::class,
        KyberPreKeyEntity::class,
        FileTransferEntity::class,
        KeyValueEntity::class,
        OutboxEntity::class,
        SyncStateEntity::class,
        PeerDeviceEntity::class,
        PeerProfileEntity::class,
        SenderKeyEntity::class,
        GroupSkdmStateEntity::class,
        CallLogEntity::class
    ],
    version = DatabaseVersion.CURRENT,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun conversationDao(): ConversationDao
    abstract fun messageDao(): MessageDao
    abstract fun contactDao(): ContactDao
    abstract fun relayDao(): RelayDao
    abstract fun seenPacketDao(): SeenPacketDao
    abstract fun signalDao(): SignalDao
    abstract fun fileTransferDao(): FileTransferDao
    abstract fun keyValueDao(): KeyValueDao
    abstract fun outboxDao(): OutboxDao
    abstract fun syncStateDao(): SyncStateDao
    abstract fun peerDao(): PeerDao
    abstract fun groupSkdmDao(): GroupSkdmDao
    abstract fun callLogDao(): CallLogDao
}

object DatabaseVersion {
    const val CURRENT = 3
}

/**
 * Version 1 -> 2: hybrid transport.
 *
 * Adds the online-transport columns (server ids, transport, delivery/read timestamps, drafts),
 * the upload outbox, sync cursors and the peer device/profile caches. Every added NOT NULL
 * column carries the same DEFAULT that the entity declares, so Room's schema validation passes
 * and existing rows stay valid; nothing is dropped and no table is recreated.
 *
 * The statements are data so the upgrade can be unit-tested: they must stay additive, and every
 * new column has to be nullable or carry a default, otherwise SQLite rejects the ALTER TABLE on a
 * database that already holds messages.
 */
internal val MIGRATION_1_2_STATEMENTS: List<String> = listOf(
    "ALTER TABLE conversations ADD COLUMN type TEXT NOT NULL DEFAULT 'DIRECT'",
    "ALTER TABLE conversations ADD COLUMN onlineId TEXT DEFAULT NULL",
    "ALTER TABLE conversations ADD COLUMN peerUserId TEXT DEFAULT NULL",
    "ALTER TABLE conversations ADD COLUMN muted INTEGER NOT NULL DEFAULT 0",
    "ALTER TABLE conversations ADD COLUMN pinned INTEGER NOT NULL DEFAULT 0",
    "ALTER TABLE conversations ADD COLUMN archived INTEGER NOT NULL DEFAULT 0",
    "ALTER TABLE conversations ADD COLUMN draft TEXT NOT NULL DEFAULT ''",
    "ALTER TABLE conversations ADD COLUMN memberUserIds TEXT NOT NULL DEFAULT ''",
    "CREATE INDEX IF NOT EXISTS index_conversations_onlineId ON conversations (onlineId)",
    "UPDATE conversations SET type = CASE WHEN isGroup = 1 THEN 'GROUP' ELSE 'DIRECT' END",
    "ALTER TABLE messages ADD COLUMN clientMsgId TEXT NOT NULL DEFAULT ''",
    "ALTER TABLE messages ADD COLUMN remoteId TEXT DEFAULT NULL",
    "ALTER TABLE messages ADD COLUMN transport TEXT NOT NULL DEFAULT 'MESH'",
    "ALTER TABLE messages ADD COLUMN editedAt INTEGER DEFAULT NULL",
    "ALTER TABLE messages ADD COLUMN deletedAt INTEGER DEFAULT NULL",
    "ALTER TABLE messages ADD COLUMN replyToId TEXT DEFAULT NULL",
    "ALTER TABLE messages ADD COLUMN starred INTEGER NOT NULL DEFAULT 0",
    "ALTER TABLE messages ADD COLUMN reactions TEXT NOT NULL DEFAULT ''",
    "ALTER TABLE messages ADD COLUMN error TEXT DEFAULT NULL",
    "ALTER TABLE messages ADD COLUMN deliveredAt INTEGER DEFAULT NULL",
    "ALTER TABLE messages ADD COLUMN readAt INTEGER DEFAULT NULL",
    "CREATE INDEX IF NOT EXISTS index_messages_clientMsgId ON messages (clientMsgId)",
    "CREATE INDEX IF NOT EXISTS index_messages_remoteId ON messages (remoteId)",
    "UPDATE messages SET clientMsgId = id WHERE clientMsgId = ''",
    "ALTER TABLE contacts ADD COLUMN userId TEXT DEFAULT NULL",
    "ALTER TABLE contacts ADD COLUMN username TEXT NOT NULL DEFAULT ''",
    "ALTER TABLE file_transfers ADD COLUMN plaintextSha256 TEXT NOT NULL DEFAULT ''",
    "CREATE TABLE IF NOT EXISTS online_outbox (" +
                "id TEXT NOT NULL PRIMARY KEY, " +
                "kind TEXT NOT NULL, " +
                "conversationId TEXT NOT NULL, " +
                "messageId TEXT NOT NULL, " +
                "attempts INTEGER NOT NULL DEFAULT 0, " +
                "nextAttemptAt INTEGER NOT NULL DEFAULT 0, " +
                "lastError TEXT DEFAULT NULL, " +
                "createdAt INTEGER NOT NULL)",
    "CREATE INDEX IF NOT EXISTS index_online_outbox_nextAttemptAt ON online_outbox (nextAttemptAt)",
    "CREATE TABLE IF NOT EXISTS sync_state (" +
                "`key` TEXT NOT NULL PRIMARY KEY, " +
                "value TEXT NOT NULL)",
    "CREATE TABLE IF NOT EXISTS peer_devices (" +
                "id TEXT NOT NULL PRIMARY KEY, " +
                "userId TEXT NOT NULL, " +
                "deviceId TEXT NOT NULL, " +
                "identityKey TEXT NOT NULL, " +
                "revoked INTEGER NOT NULL DEFAULT 0, " +
                "lastSeenAt INTEGER NOT NULL DEFAULT 0)",
    "CREATE INDEX IF NOT EXISTS index_peer_devices_userId ON peer_devices (userId)",
    "CREATE INDEX IF NOT EXISTS index_peer_devices_deviceId ON peer_devices (deviceId)",
    "CREATE TABLE IF NOT EXISTS peer_profiles (" +
                "userId TEXT NOT NULL PRIMARY KEY, " +
                "username TEXT NOT NULL DEFAULT '', " +
                "displayName TEXT NOT NULL DEFAULT '', " +
                "avatarUrl TEXT DEFAULT NULL, " +
                "updatedAt INTEGER NOT NULL DEFAULT 0)"
)

val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_1_2_STATEMENTS.forEach(db::execSQL)
    }
}

/**
 * Version 2 -> 3: Phase 2 production hardening.
 *
 * Purely additive upgrade adding:
 * - explicit group encryption protocol versioning (`encryptionVersion` default 1 so existing chats
 *   stay on pairwise fan-out unless upgraded; `senderKeyEpoch`, `distributionId`), timed mute, and role
 * - streaming E2EE attachment metadata, encrypted preview thumbnail, dimensions, and resumable transfer state
 * - per-device metadata (`name`, `platform`, `verified`) on `peer_devices`
 * - `signal_sender_keys`, `group_skdm_states`, and `call_logs` tables
 */
internal val MIGRATION_2_3_STATEMENTS: List<String> = listOf(
    "ALTER TABLE conversations ADD COLUMN encryptionVersion INTEGER NOT NULL DEFAULT 1",
    "ALTER TABLE conversations ADD COLUMN senderKeyEpoch INTEGER NOT NULL DEFAULT 1",
    "ALTER TABLE conversations ADD COLUMN distributionId TEXT DEFAULT NULL",
    "ALTER TABLE conversations ADD COLUMN mutedUntil INTEGER DEFAULT NULL",
    "ALTER TABLE conversations ADD COLUMN myRole TEXT NOT NULL DEFAULT 'member'",
    "ALTER TABLE messages ADD COLUMN fileMime TEXT DEFAULT NULL",
    "ALTER TABLE messages ADD COLUMN fileSha256 TEXT NOT NULL DEFAULT ''",
    "ALTER TABLE messages ADD COLUMN attachmentRemoteId TEXT DEFAULT NULL",
    "ALTER TABLE messages ADD COLUMN remoteObjectPath TEXT DEFAULT NULL",
    "ALTER TABLE messages ADD COLUMN mediaKeyBase64 TEXT DEFAULT NULL",
    "ALTER TABLE messages ADD COLUMN mediaIvBase64 TEXT DEFAULT NULL",
    "ALTER TABLE messages ADD COLUMN cipherSha256 TEXT DEFAULT NULL",
    "ALTER TABLE messages ADD COLUMN cipherSize INTEGER DEFAULT NULL",
    "ALTER TABLE messages ADD COLUMN thumbnailBase64 TEXT DEFAULT NULL",
    "ALTER TABLE messages ADD COLUMN mediaWidth INTEGER DEFAULT NULL",
    "ALTER TABLE messages ADD COLUMN mediaHeight INTEGER DEFAULT NULL",
    "ALTER TABLE messages ADD COLUMN transferState TEXT DEFAULT NULL",
    "ALTER TABLE messages ADD COLUMN transferBytes INTEGER NOT NULL DEFAULT 0",
    "ALTER TABLE peer_devices ADD COLUMN name TEXT NOT NULL DEFAULT ''",
    "ALTER TABLE peer_devices ADD COLUMN platform TEXT NOT NULL DEFAULT 'android'",
    "ALTER TABLE peer_devices ADD COLUMN verified INTEGER NOT NULL DEFAULT 0",
    "CREATE TABLE IF NOT EXISTS signal_sender_keys (" +
        "remoteName TEXT NOT NULL, " +
        "deviceId INTEGER NOT NULL, " +
        "distributionId TEXT NOT NULL, " +
        "recordBase64 TEXT NOT NULL, " +
        "PRIMARY KEY(remoteName, deviceId, distributionId))",
    "CREATE TABLE IF NOT EXISTS group_skdm_states (" +
        "conversationId TEXT NOT NULL, " +
        "epoch INTEGER NOT NULL, " +
        "distributionId TEXT NOT NULL, " +
        "recipientDeviceRowId TEXT NOT NULL, " +
        "sentAt INTEGER NOT NULL, " +
        "PRIMARY KEY(conversationId, epoch, recipientDeviceRowId))",
    "CREATE INDEX IF NOT EXISTS index_group_skdm_states_conversationId ON group_skdm_states (conversationId)",
    "CREATE TABLE IF NOT EXISTS call_logs (" +
        "callId TEXT NOT NULL PRIMARY KEY, " +
        "conversationId TEXT NOT NULL, " +
        "peerUserId TEXT NOT NULL, " +
        "peerName TEXT NOT NULL, " +
        "kind TEXT NOT NULL, " +
        "direction TEXT NOT NULL, " +
        "state TEXT NOT NULL, " +
        "startedAt INTEGER NOT NULL, " +
        "answeredAt INTEGER, " +
        "endedAt INTEGER, " +
        "durationSec INTEGER NOT NULL)"
    ,
    "CREATE INDEX IF NOT EXISTS index_call_logs_conversationId ON call_logs (conversationId)",
    "CREATE INDEX IF NOT EXISTS index_call_logs_startedAt ON call_logs (startedAt)"
)

val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        MIGRATION_2_3_STATEMENTS.forEach(db::execSQL)
    }
}
