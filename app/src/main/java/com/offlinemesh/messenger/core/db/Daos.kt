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

    @Query("SELECT * FROM conversations ORDER BY lastMessageAt DESC")
    suspend fun getAll(): List<ConversationEntity>

    @Query("SELECT * FROM conversations WHERE onlineId = :onlineId LIMIT 1")
    suspend fun getByOnlineId(onlineId: String): ConversationEntity?

    @Query("SELECT * FROM conversations WHERE type = 'CHANNEL' ORDER BY lastMessageAt DESC")
    fun observeChannels(): Flow<List<ConversationEntity>>

    @Query("UPDATE conversations SET onlineId = :onlineId, peerUserId = :peerUserId WHERE id = :id")
    suspend fun linkOnline(id: String, onlineId: String, peerUserId: String?)

    @Query("UPDATE conversations SET title = :title, memberUserIds = :memberUserIds WHERE id = :id")
    suspend fun updateMembership(id: String, title: String, memberUserIds: String)

    @Query("UPDATE conversations SET draft = :draft WHERE id = :id")
    suspend fun saveDraft(id: String, draft: String)

    @Query("UPDATE conversations SET muted = :muted WHERE id = :id")
    suspend fun setMuted(id: String, muted: Boolean)

    @Query("UPDATE conversations SET pinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: String, pinned: Boolean)

    @Query("UPDATE conversations SET archived = :archived WHERE id = :id")
    suspend fun setArchived(id: String, archived: Boolean)

    @Query("SELECT * FROM conversations WHERE title LIKE :pattern OR lastMessagePreview LIKE :pattern ORDER BY lastMessageAt DESC")
    suspend fun search(pattern: String): List<ConversationEntity>
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

    @Query("SELECT * FROM messages WHERE clientMsgId = :clientMsgId LIMIT 1")
    suspend fun getByClientMsgId(clientMsgId: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE remoteId = :remoteId LIMIT 1")
    suspend fun getByRemoteId(remoteId: String): MessageEntity?

    @Query("UPDATE messages SET remoteId = :remoteId, status = :status, error = NULL WHERE id = :id")
    suspend fun markUploaded(id: String, remoteId: String, status: String)

    @Query("UPDATE messages SET status = :status, error = :error WHERE id = :id")
    suspend fun markFailed(id: String, status: String, error: String?)

    @Query("UPDATE messages SET deliveredAt = :at, status = CASE WHEN status IN ('QUEUED','SENDING','SENT') THEN 'DELIVERED' ELSE status END WHERE id = :id")
    suspend fun markDelivered(id: String, at: Long)

    @Query("UPDATE messages SET readAt = :at WHERE id = :id")
    suspend fun markReadAt(id: String, at: Long)

    @Query("UPDATE messages SET body = :body, editedAt = :editedAt WHERE id = :id")
    suspend fun editBody(id: String, body: String, editedAt: Long)

    @Query("UPDATE messages SET deletedAt = :at, body = '' WHERE id = :id")
    suspend fun markDeleted(id: String, at: Long)

    @Query("UPDATE messages SET reactions = :summary WHERE id = :id")
    suspend fun setReactions(id: String, summary: String)

    @Query("UPDATE messages SET starred = :starred WHERE id = :id")
    suspend fun setStarred(id: String, starred: Boolean)

    @Query(
        "SELECT * FROM messages WHERE deletedAt IS NULL AND body LIKE :pattern " +
            "AND (:conversationId IS NULL OR conversationId = :conversationId) " +
            "ORDER BY timestamp DESC LIMIT 200"
    )
    suspend fun search(pattern: String, conversationId: String?): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE starred = 1 AND deletedAt IS NULL ORDER BY timestamp DESC")
    fun observeStarred(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE outgoing = 0 AND status = 'RECEIVED' ORDER BY timestamp ASC LIMIT 1")
    suspend fun oldestUnread(): MessageEntity?

    @Query("SELECT * FROM messages WHERE conversationId = :conversationId AND timestamp <= :before ORDER BY timestamp DESC LIMIT :limit")
    suspend fun page(conversationId: String, before: Long, limit: Int): List<MessageEntity>
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

    @Query(
        """SELECT * FROM contacts
           WHERE displayName LIKE :pattern ESCAPE '\\'
              OR IFNULL(username, '') LIKE :pattern ESCAPE '\\'
           ORDER BY displayName ASC LIMIT 200"""
    )
    suspend fun search(pattern: String): List<ContactEntity>
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

    @Query("UPDATE file_transfers SET status = :status, outputPath = :path WHERE fileId = :fileId")
    suspend fun complete(fileId: String, status: String, path: String)

    @Query("SELECT * FROM file_transfers WHERE status != 'COMPLETE' ORDER BY createdAt ASC")
    suspend fun pending(): List<FileTransferEntity>

    @Query("DELETE FROM file_transfers WHERE status = 'COMPLETE' AND createdAt < :before")
    suspend fun purgeCompleted(before: Long)
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


@Dao
interface OutboxDao {
    @Query("SELECT * FROM online_outbox WHERE nextAttemptAt <= :now ORDER BY createdAt ASC LIMIT :limit")
    suspend fun due(now: Long, limit: Int): List<OutboxEntity>

    @Query("SELECT * FROM online_outbox ORDER BY createdAt ASC LIMIT 1")
    suspend fun peek(): OutboxEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: OutboxEntity)

    @Query("DELETE FROM online_outbox WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM online_outbox WHERE messageId = :messageId")
    suspend fun deleteForMessage(messageId: String)

    @Query("UPDATE online_outbox SET attempts = attempts + 1, nextAttemptAt = :nextAttemptAt, lastError = :error WHERE id = :id")
    suspend fun bump(id: String, nextAttemptAt: Long, error: String?)

    @Query("SELECT COUNT(*) FROM online_outbox")
    suspend fun count(): Int

    @Query("SELECT COUNT(*) FROM online_outbox WHERE messageId = :messageId")
    suspend fun countForMessage(messageId: String): Int

    @Query("DELETE FROM online_outbox")
    suspend fun clear()
}

@Dao
interface SyncStateDao {
    @Query("SELECT value FROM sync_state WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(entry: SyncStateEntity)

    @Query("DELETE FROM sync_state WHERE `key` = :key")
    suspend fun remove(key: String)

    @Query("DELETE FROM sync_state")
    suspend fun clear()
}

@Dao
interface PeerDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDevices(devices: List<PeerDeviceEntity>)

    @Query("SELECT * FROM peer_devices WHERE userId = :userId AND revoked = 0")
    suspend fun devicesFor(userId: String): List<PeerDeviceEntity>

    @Query("SELECT * FROM peer_devices WHERE deviceId = :deviceId LIMIT 1")
    suspend fun deviceByDeviceId(deviceId: String): PeerDeviceEntity?

    @Query("SELECT * FROM peer_devices WHERE revoked = 0")
    suspend fun allDevices(): List<PeerDeviceEntity>

    @Query("UPDATE peer_devices SET revoked = 1 WHERE id = :id")
    suspend fun markRevoked(id: String)

    @Query("DELETE FROM peer_devices")
    suspend fun clearDevices()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfile(profile: PeerProfileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProfiles(profiles: List<PeerProfileEntity>)

    @Query("SELECT * FROM peer_profiles WHERE userId = :userId")
    suspend fun profile(userId: String): PeerProfileEntity?

    @Query("SELECT * FROM peer_profiles")
    suspend fun profiles(): List<PeerProfileEntity>

    @Query("SELECT * FROM peer_profiles WHERE displayName LIKE :pattern OR username LIKE :pattern LIMIT 50")
    suspend fun searchProfiles(pattern: String): List<PeerProfileEntity>

    @Query("DELETE FROM peer_profiles")
    suspend fun clearProfiles()
}
