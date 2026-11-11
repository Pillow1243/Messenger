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

    @Query("SELECT * FROM conversations WHERE id = :id")
    fun getByIdBlocking(id: String): ConversationEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(conversation: ConversationEntity)

    @Query(
        "UPDATE conversations SET lastMessageAt = :at, lastMessagePreview = :preview, " +
            "unreadCount = unreadCount + :unreadDelta WHERE id = :id"
    )
    suspend fun updateLastMessage(id: String, at: Long, preview: String, unreadDelta: Int)

    @Query("UPDATE conversations SET unreadCount = 0 WHERE id = :id")
    suspend fun markRead(id: String)

    @Query("DELETE FROM conversations WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("SELECT COUNT(*) FROM conversations")
    suspend fun count(): Int
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
    suspend fun upsert(message: MessageEntity)

    @Query("UPDATE messages SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    @Query("UPDATE messages SET status = :status WHERE packetId = :packetId")
    suspend fun updateStatusByPacketId(packetId: String, status: String)

    @Query("SELECT * FROM messages WHERE outgoing = 1 AND status IN ('QUEUED','SENDING','SENT') ORDER BY timestamp ASC")
    suspend fun pendingOutgoing(): List<MessageEntity>

    @Query("DELETE FROM messages WHERE conversationId = :conversationId")
    suspend fun deleteForConversation(conversationId: String)

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId")
    suspend fun countForConversation(conversationId: String): Int
}

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts ORDER BY displayName ASC")
    fun observeAll(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE deviceId = :deviceId")
    suspend fun getByDeviceId(deviceId: String): ContactEntity?

    @Query("SELECT * FROM contacts WHERE deviceId = :deviceId")
    fun getByDeviceIdBlocking(deviceId: String): ContactEntity?

    @Query("SELECT * FROM contacts ORDER BY displayName ASC")
    suspend fun getAll(): List<ContactEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(contact: ContactEntity)

    @Query("UPDATE contacts SET lastSeenAt = :at WHERE deviceId = :deviceId")
    suspend fun updateLastSeen(deviceId: String, at: Long)

    @Query("UPDATE contacts SET verified = :verified WHERE deviceId = :deviceId")
    suspend fun setVerified(deviceId: String, verified: Boolean)

    @Query("UPDATE contacts SET identityChanged = :changed WHERE deviceId = :deviceId")
    suspend fun setIdentityChanged(deviceId: String, changed: Boolean)

    @Query("DELETE FROM contacts WHERE deviceId = :deviceId")
    suspend fun delete(deviceId: String)

    @Query("DELETE FROM contacts")
    suspend fun deleteAll()
}

@Dao
interface RelayDao {
    @Query("SELECT * FROM relay_queue ORDER BY createdAt ASC")
    suspend fun getAll(): List<RelayEntity>

    @Query("SELECT * FROM relay_queue WHERE targetDeviceId = :deviceId OR targetDeviceId IS NULL ORDER BY createdAt ASC")
    suspend fun forDevice(deviceId: String): List<RelayEntity>

    /** Atomic claim so two senders never enqueue the same packet twice. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIfAbsent(entry: RelayEntity): Long

    @Query("DELETE FROM relay_queue WHERE packetId = :packetId")
    suspend fun delete(packetId: String)

    @Query("DELETE FROM relay_queue WHERE msgId = :msgId")
    suspend fun deleteForMessage(msgId: String)

    @Query("DELETE FROM relay_queue WHERE expiresAt < :now")
    suspend fun purgeExpired(now: Long)

    @Query("UPDATE relay_queue SET attempts = attempts + 1 WHERE packetId = :packetId")
    suspend fun bumpAttempt(packetId: String)

    @Query("SELECT COUNT(*) FROM relay_queue")
    suspend fun count(): Int
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

/**
 * Signal Protocol state.
 *
 * These methods are deliberately blocking: libsignal calls the store synchronously from inside
 * encrypt/decrypt, and Room's suspend functions cannot be awaited from that thread. Callers must
 * be on a background thread (the mesh/crypto layer always uses Dispatchers.IO).
 */
@Dao
interface SignalDao {
    @Query("SELECT * FROM signal_sessions WHERE remoteName = :remoteName AND deviceId = :deviceId")
    fun session(remoteName: String, deviceId: Int): SessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putSession(entity: SessionEntity)

    @Query("DELETE FROM signal_sessions WHERE remoteName = :remoteName AND deviceId = :deviceId")
    fun deleteSession(remoteName: String, deviceId: Int)

    @Query("DELETE FROM signal_sessions WHERE remoteName = :remoteName")
    fun deleteSessionsFor(remoteName: String)

    @Query("DELETE FROM signal_sessions")
    fun deleteAllSessions()

    @Query("SELECT COUNT(*) FROM signal_sessions WHERE remoteName = :remoteName AND deviceId = :deviceId")
    fun containsSession(remoteName: String, deviceId: Int): Int

    @Query("SELECT * FROM signal_prekeys WHERE keyId = :keyId")
    fun getPreKey(keyId: Int): PreKeyEntity?

    @Query("SELECT keyId FROM signal_prekeys")
    fun preKeyIds(): List<Int>

    @Query("SELECT COUNT(*) FROM signal_prekeys")
    fun preKeyCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putPreKey(entity: PreKeyEntity)

    @Query("DELETE FROM signal_prekeys WHERE keyId = :keyId")
    fun deletePreKey(keyId: Int)

    @Query("SELECT * FROM signal_signed_prekeys WHERE keyId = :keyId")
    fun getSignedPreKey(keyId: Int): SignedPreKeyEntity?

    @Query("SELECT * FROM signal_signed_prekeys")
    fun allSignedPreKeys(): List<SignedPreKeyEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putSignedPreKey(entity: SignedPreKeyEntity)

    @Query("DELETE FROM signal_signed_prekeys WHERE keyId = :keyId")
    fun deleteSignedPreKey(keyId: Int)

    @Query("SELECT * FROM signal_kyber_prekeys WHERE keyId = :keyId")
    fun getKyberPreKey(keyId: Int): KyberPreKeyEntity?

    @Query("SELECT * FROM signal_kyber_prekeys")
    fun allKyberPreKeys(): List<KyberPreKeyEntity>

    @Query("SELECT COUNT(*) FROM signal_kyber_prekeys")
    fun kyberPreKeyCount(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putKyberPreKey(entity: KyberPreKeyEntity)

    @Query("DELETE FROM signal_kyber_prekeys WHERE keyId = :keyId")
    fun deleteKyberPreKey(keyId: Int)

    @Query("SELECT * FROM signal_identities WHERE remoteName = :remoteName AND deviceId = :deviceId")
    fun getIdentity(remoteName: String, deviceId: Int): IdentityEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun putIdentity(entity: IdentityEntity)

    @Query("DELETE FROM signal_identities WHERE remoteName = :remoteName AND deviceId = :deviceId")
    fun deleteIdentity(remoteName: String, deviceId: Int)

    @Query("DELETE FROM signal_identities")
    fun deleteAllIdentities()
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
    @Query("SELECT value FROM key_values WHERE `key` = :key")
    fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun put(entity: KeyValueEntity)

    @Query("DELETE FROM key_values WHERE `key` = :key")
    fun delete(key: String)

    @Query("DELETE FROM key_values")
    fun deleteAll()
}
