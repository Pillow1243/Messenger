package com.offlinemesh.messenger.core.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY lastMessageAt DESC")
    fun observeAll(): Flow<List<ConversationEntity>>

    @Query("SELECT * FROM conversations WHERE id = :id")
    suspend fun getById(id: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(conversation: ConversationEntity)

    @Query("UPDATE conversations SET lastMessageAt = :at, lastMessagePreview = :preview, unreadCount = unreadCount + :unreadDelta WHERE id = :id")
    suspend fun updateLastMessage(id: String, at: Long, preview: String, unreadDelta: Int)

    @Query("UPDATE conversations SET unreadCount = 0 WHERE id = :id")
    suspend fun markRead(id: String)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteById(id: String)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp ASC")
    fun observeForConversation(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun getById(id: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY timestamp DESC LIMIT 1")
    suspend fun latestForConversation(conversationId: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: MessageEntity)

    @Query("UPDATE messages SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    @Query("UPDATE messages SET status = :status WHERE packetId = :packetId")
    suspend fun updateStatusByPacketId(packetId: String, status: String)

    @Query("SELECT * FROM messages WHERE status IN ('QUEUED','FAILED') ORDER BY timestamp ASC")
    suspend fun getPending(): List<MessageEntity>
}

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts ORDER BY displayName ASC")
    fun observeAll(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE deviceId = :deviceId")
    suspend fun getByDeviceId(deviceId: String): ContactEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(contact: ContactEntity)

    @Query("UPDATE contacts SET lastSeenAt = :at WHERE deviceId = :deviceId")
    suspend fun updateLastSeen(deviceId: String, at: Long)
}

@Dao
interface OutboxDao {
    @Query("SELECT * FROM outbox ORDER BY createdAt ASC")
    suspend fun getAll(): List<OutboxEntity>

    @Query("SELECT * FROM outbox WHERE toDeviceId = :deviceId OR toDeviceId IS NULL ORDER BY createdAt ASC")
    suspend fun getForDevice(deviceId: String): List<OutboxEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: OutboxEntity)

    @Query("DELETE FROM outbox WHERE packetId = :packetId")
    suspend fun deleteByPacketId(packetId: String)

    @Query("UPDATE outbox SET retryCount = :retryCount, nextRetryAt = :nextRetryAt WHERE packetId = :packetId")
    suspend fun updateRetry(packetId: String, retryCount: Int, nextRetryAt: Long)
}

@Dao
interface SeenPacketDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entry: SeenPacketEntity): Long

    @Query("SELECT COUNT(*) FROM seen_packets WHERE packetId = :packetId")
    suspend fun countByPacketId(packetId: String): Int

    @Query("DELETE FROM seen_packets WHERE seenAt < :before")
    suspend fun deleteOlderThan(before: Long)
}

@Dao
interface SignalDao {
    @Query("SELECT * FROM signal_sessions WHERE remoteName = :remoteName")
    suspend fun getSession(remoteName: String): SessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(entity: SessionEntity)

    @Query("DELETE FROM signal_sessions WHERE remoteName = :remoteName")
    suspend fun deleteSession(remoteName: String)

    @Query("DELETE FROM signal_sessions")
    suspend fun deleteAllSessions()

    @Query("SELECT * FROM signal_prekeys WHERE keyId = :keyId")
    suspend fun getPreKey(keyId: Int): PreKeyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPreKey(entity: PreKeyEntity)

    @Query("DELETE FROM signal_prekeys WHERE keyId = :keyId")
    suspend fun deletePreKey(keyId: Int)

    @Query("SELECT * FROM signal_signed_prekeys WHERE keyId = :keyId")
    suspend fun getSignedPreKey(keyId: Int): SignedPreKeyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSignedPreKey(entity: SignedPreKeyEntity)

    @Query("DELETE FROM signal_signed_prekeys WHERE keyId = :keyId")
    suspend fun deleteSignedPreKey(keyId: Int)

    @Query("SELECT * FROM signal_identities WHERE remoteName = :remoteName")
    suspend fun getIdentity(remoteName: String): IdentityEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertIdentity(entity: IdentityEntity)
}

@Dao
interface FileTransferDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(transfer: FileTransferEntity)

    @Query("SELECT * FROM file_transfers WHERE fileId = :fileId")
    suspend fun getById(fileId: String): FileTransferEntity?

    @Query("UPDATE file_transfers SET receivedChunks = :received, status = :status WHERE fileId = :fileId")
    suspend fun updateProgress(fileId: String, received: Int, status: String)
}

@Dao
interface KeyValueDao {
    @Query("SELECT value FROM key_values WHERE key = :key")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entity: KeyValueEntity)
}
