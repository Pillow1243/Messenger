package com.offlinemesh.messenger.data

import android.content.Context
import android.util.Base64
import android.util.Log
import com.offlinemesh.messenger.core.crypto.CryptoManager
import com.offlinemesh.messenger.core.db.AppDatabase
import com.offlinemesh.messenger.core.db.ConversationEntity
import com.offlinemesh.messenger.core.db.MessageEntity
import com.offlinemesh.messenger.core.db.OutboxEntity
import com.offlinemesh.messenger.core.db.SeenPacketEntity
import com.offlinemesh.messenger.core.mesh.FileChunkMeta
import com.offlinemesh.messenger.core.mesh.FileChunker
import com.offlinemesh.messenger.core.mesh.MeshManager
import com.offlinemesh.messenger.core.mesh.MeshPacket
import com.offlinemesh.messenger.core.mesh.MeshPacketHandler
import com.offlinemesh.messenger.core.mesh.PacketType
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

class ChatRepository(
    private val context: Context,
    private val db: AppDatabase,
    private val crypto: CryptoManager,
    private val mesh: MeshManager,
    private val files: FileRepository,
    private val settings: SettingsRepository
) : MeshPacketHandler {

    private val json = Json { ignoreUnknownKeys = true }

    fun observeConversations(): Flow<List<ConversationEntity>> = db.conversationDao().observeAll()

    fun observeMessages(conversationId: String): Flow<List<MessageEntity>> =
        db.messageDao().observeForConversation(conversationId)

    suspend fun ensureDirectConversation(peerDeviceId: String, displayName: String): String {
        val id = "dm:$peerDeviceId"
        val existing = db.conversationDao().getById(id)
        if (existing == null) {
            db.conversationDao().upsert(
                ConversationEntity(
                    id = id,
                    title = displayName,
                    isGroup = false,
                    createdAt = System.currentTimeMillis(),
                    memberIds = peerDeviceId
                )
            )
        }
        return id
    }

    suspend fun ensureGroupConversation(groupId: String, title: String, memberIds: List<String>): String {
        val id = "group:$groupId"
        val existing = db.conversationDao().getById(id)
        if (existing == null) {
            db.conversationDao().upsert(
                ConversationEntity(
                    id = id,
                    title = title,
                    isGroup = true,
                    createdAt = System.currentTimeMillis(),
                    memberIds = memberIds.joinToString(",")
                )
            )
        }
        return id
    }

    suspend fun markRead(conversationId: String) {
        db.conversationDao().markRead(conversationId)
    }

    suspend fun sendText(conversationId: String, body: String) {
        val myId = settings.getDeviceId()
        val conversation = db.conversationDao().getById(conversationId) ?: return
        val messageId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val message = MessageEntity(
            id = messageId,
            conversationId = conversationId,
            senderDeviceId = myId,
            type = "TEXT",
            body = body,
            timestamp = now,
            status = "QUEUED"
        )
        db.messageDao().insert(message)
        db.conversationDao().updateLastMessage(conversationId, now, body, 0)

        val targets = if (conversation.isGroup) {
            conversation.memberIds.split(",").filter { it.isNotEmpty() && it != myId }
        } else {
            listOf(conversation.memberIds)
        }
        for (target in targets) {
            try {
                val encrypted = crypto.encrypt(target, body.toByteArray(Charsets.UTF_8))
                val packet = MeshPacket(
                    packetId = UUID.randomUUID().toString(),
                    messageId = messageId,
                    fromDeviceId = myId,
                    toDeviceId = target,
                    groupId = if (conversation.isGroup) conversationId else null,
                    type = PacketType.TEXT,
                    ttl = MeshPacket.DEFAULT_TTL,
                    hopCount = 0,
                    timestamp = now,
                    payload = Base64.encodeToString(encrypted, Base64.NO_WRAP)
                )
                db.outboxDao().insert(
                    OutboxEntity(
                        packetId = packet.packetId,
                        packetJson = json.encodeToString(packet),
                        toDeviceId = target,
                        createdAt = now
                    )
                )
                if (mesh.sendToDevice(packet, target)) {
                    db.messageDao().updateStatus(messageId, "SENT")
                }
            } catch (e: Exception) {
                Log.e(TAG, "sendText failed for $target", e)
            }
        }
    }

    suspend fun sendVoice(conversationId: String, file: File, durationMs: Long) {
        val myId = settings.getDeviceId()
        val conversation = db.conversationDao().getById(conversationId) ?: return
        val messageId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val message = MessageEntity(
            id = messageId,
            conversationId = conversationId,
            senderDeviceId = myId,
            type = "VOICE",
            body = "",
            timestamp = now,
            status = "QUEUED",
            filePath = file.absolutePath,
            fileName = file.name,
            durationMs = durationMs
        )
        db.messageDao().insert(message)
        db.conversationDao().updateLastMessage(conversationId, now, "🎤 Voice note", 0)

        val bytes = file.readBytes()
        val targets = if (conversation.isGroup) {
            conversation.memberIds.split(",").filter { it.isNotEmpty() && it != myId }
        } else {
            listOf(conversation.memberIds)
        }
        for (target in targets) {
            try {
                val encrypted = crypto.encrypt(target, bytes)
                val packet = MeshPacket(
                    packetId = UUID.randomUUID().toString(),
                    messageId = messageId,
                    fromDeviceId = myId,
                    toDeviceId = target,
                    groupId = if (conversation.isGroup) conversationId else null,
                    type = PacketType.VOICE,
                    ttl = MeshPacket.DEFAULT_TTL,
                    hopCount = 0,
                    timestamp = now,
                    payload = Base64.encodeToString(encrypted, Base64.NO_WRAP)
                )
                db.outboxDao().insert(
                    OutboxEntity(
                        packetId = packet.packetId,
                        packetJson = json.encodeToString(packet),
                        toDeviceId = target,
                        createdAt = now
                    )
                )
                if (mesh.sendToDevice(packet, target)) {
                    db.messageDao().updateStatus(messageId, "SENT")
                }
            } catch (e: Exception) {
                Log.e(TAG, "sendVoice failed", e)
            }
        }
    }

    suspend fun sendFile(conversationId: String, file: File, fileName: String) {
        val myId = settings.getDeviceId()
        val conversation = db.conversationDao().getById(conversationId) ?: return
        if (file.length() > FileChunker.MAX_FILE_SIZE) return
        val fileId = UUID.randomUUID().toString()
        val messageId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val total = FileChunker.chunkCount(file)
        val message = MessageEntity(
            id = messageId,
            conversationId = conversationId,
            senderDeviceId = myId,
            type = "FILE",
            body = fileName,
            timestamp = now,
            status = "QUEUED",
            filePath = file.absolutePath,
            fileName = fileName
        )
        db.messageDao().insert(message)
        db.conversationDao().updateLastMessage(conversationId, now, "📎 $fileName", 0)

        val targets = if (conversation.isGroup) {
            conversation.memberIds.split(",").filter { it.isNotEmpty() && it != myId }
        } else {
            listOf(conversation.memberIds)
        }
        for (target in targets) {
            for (index in 0 until total) {
                try {
                    val chunk = FileChunker.readChunk(file, index)
                    val encrypted = crypto.encrypt(target, chunk)
                    val meta = FileChunkMeta(fileId = fileId, fileName = fileName, index = index, total = total)
                    val packet = MeshPacket(
                        packetId = UUID.randomUUID().toString(),
                        messageId = messageId,
                        fromDeviceId = myId,
                        toDeviceId = target,
                        groupId = if (conversation.isGroup) conversationId else null,
                        type = PacketType.FILE_CHUNK,
                        ttl = MeshPacket.DEFAULT_TTL,
                        hopCount = 0,
                        timestamp = now,
                        payload = Base64.encodeToString(encrypted, Base64.NO_WRAP),
                        metadata = json.encodeToString(meta)
                    )
                    db.outboxDao().insert(
                        OutboxEntity(
                            packetId = packet.packetId,
                            packetJson = json.encodeToString(packet),
                            toDeviceId = target,
                            createdAt = now
                        )
                    )
                    mesh.sendToDevice(packet, target)
                } catch (e: Exception) {
                    Log.e(TAG, "sendFile chunk failed", e)
                }
            }
        }
        db.messageDao().updateStatus(messageId, "SENT")
    }

    override suspend fun onPacketReceived(packet: MeshPacket, fromEndpoint: String) {
        val myId = settings.getDeviceId()
        val inserted = db.seenPacketDao().insertIfAbsent(
            SeenPacketEntity(packetId = packet.packetId, seenAt = System.currentTimeMillis())
        )
        if (inserted == -1L) return

        if (packet.type == PacketType.ACK) {
            db.outboxDao().deleteByPacketId(packet.messageId)
            db.messageDao().updateStatusByPacketId(packet.messageId, "DELIVERED")
            return
        }

        val isForMe = packet.toDeviceId == null || packet.toDeviceId == myId
        if (!isForMe) return

        when (packet.type) {
            PacketType.TEXT -> {
                try {
                    val plain = crypto.decrypt(packet.fromDeviceId, Base64.decode(packet.payload, Base64.NO_WRAP))
                    val body = String(plain, Charsets.UTF_8)
                    val conversationId = packet.groupId ?: "dm:${packet.fromDeviceId}"
                    ensureConversationForIncoming(conversationId, packet)
                    if (db.messageDao().getById(packet.messageId) == null) {
                        db.messageDao().insert(
                            MessageEntity(
                                id = packet.messageId,
                                conversationId = conversationId,
                                senderDeviceId = packet.fromDeviceId,
                                type = "TEXT",
                                body = body,
                                timestamp = packet.timestamp,
                                status = "DELIVERED",
                                packetId = packet.packetId
                            )
                        )
                        db.conversationDao().updateLastMessage(conversationId, packet.timestamp, body, 1)
                    }
                    sendAck(packet)
                } catch (e: Exception) {
                    Log.e(TAG, "TEXT decrypt failed", e)
                }
            }
            PacketType.VOICE -> {
                try {
                    val plain = crypto.decrypt(packet.fromDeviceId, Base64.decode(packet.payload, Base64.NO_WRAP))
                    val saved = files.saveVoiceBytes(plain)
                    val conversationId = packet.groupId ?: "dm:${packet.fromDeviceId}"
                    ensureConversationForIncoming(conversationId, packet)
                    if (db.messageDao().getById(packet.messageId) == null) {
                        db.messageDao().insert(
                            MessageEntity(
                                id = packet.messageId,
                                conversationId = conversationId,
                                senderDeviceId = packet.fromDeviceId,
                                type = "VOICE",
                                body = "",
                                timestamp = packet.timestamp,
                                status = "DELIVERED",
                                filePath = saved.absolutePath,
                                fileName = saved.name,
                                packetId = packet.packetId
                            )
                        )
                        db.conversationDao().updateLastMessage(conversationId, packet.timestamp, "🎤 Voice note", 1)
                    }
                    sendAck(packet)
                } catch (e: Exception) {
                    Log.e(TAG, "VOICE decrypt failed", e)
                }
            }
            PacketType.FILE_CHUNK -> {
                try {
                    val plain = crypto.decrypt(packet.fromDeviceId, Base64.decode(packet.payload, Base64.NO_WRAP))
                    val meta: FileChunkMeta = json.decodeFromString(packet.metadata)
                    files.saveChunk(meta.fileId, meta.index, plain)
                    val existing = db.fileTransferDao().getById(meta.fileId)
                    val received = (existing?.receivedChunks ?: 0) + 1
                    val conversationId = packet.groupId ?: "dm:${packet.fromDeviceId}"
                    if (existing == null) {
                        db.fileTransferDao().upsert(
                            com.offlinemesh.messenger.core.db.FileTransferEntity(
                                fileId = meta.fileId,
                                conversationId = conversationId,
                                fileName = meta.fileName,
                                totalChunks = meta.total,
                                receivedChunks = received,
                                status = "RECEIVING",
                                outputPath = ""
                            )
                        )
                    } else {
                        db.fileTransferDao().updateProgress(meta.fileId, received, "RECEIVING")
                    }
                    if (received >= meta.total) {
                        val assembled = files.assemble(meta.fileId, meta.fileName)
                        ensureConversationForIncoming(conversationId, packet)
                        if (db.messageDao().getById(packet.messageId) == null) {
                            db.messageDao().insert(
                                MessageEntity(
                                    id = packet.messageId,
                                    conversationId = conversationId,
                                    senderDeviceId = packet.fromDeviceId,
                                    type = "FILE",
                                    body = meta.fileName,
                                    timestamp = packet.timestamp,
                                    status = "DELIVERED",
                                    filePath = assembled.absolutePath,
                                    fileName = meta.fileName,
                                    packetId = packet.packetId
                                )
                            )
                            db.conversationDao().updateLastMessage(conversationId, packet.timestamp, "📎 ${meta.fileName}", 1)
                        }
                        db.fileTransferDao().updateProgress(meta.fileId, received, "COMPLETE")
                    }
                    sendAck(packet)
                } catch (e: Exception) {
                    Log.e(TAG, "FILE_CHUNK failed", e)
                }
            }
            else -> {
            }
        }
    }

    override suspend fun onPeerHello(peerDeviceId: String, displayName: String, endpointId: String) {
        val contact = db.contactDao().getByDeviceId(peerDeviceId)
        if (contact == null) {
            db.contactDao().upsert(
                com.offlinemesh.messenger.core.db.ContactEntity(
                    deviceId = peerDeviceId,
                    displayName = displayName,
                    addedAt = System.currentTimeMillis(),
                    lastSeenAt = System.currentTimeMillis()
                )
            )
        } else {
            db.contactDao().updateLastSeen(peerDeviceId, System.currentTimeMillis())
        }
        flushOutboxFor(peerDeviceId)
    }

    suspend fun flushOutboxFor(deviceId: String) {
        val entries = db.outboxDao().getForDevice(deviceId)
        for (entry in entries) {
            try {
                val packet: MeshPacket = json.decodeFromString(entry.packetJson)
                if (mesh.sendToDevice(packet, deviceId)) {
                    db.messageDao().updateStatusByPacketId(packet.packetId, "SENT")
                }
            } catch (e: Exception) {
                Log.e(TAG, "flush failed", e)
            }
        }
    }

    suspend fun retryPending() {
        val now = System.currentTimeMillis()
        val entries = db.outboxDao().getAll()
        for (entry in entries) {
            if (entry.nextRetryAt > now) continue
            try {
                val packet: MeshPacket = json.decodeFromString(entry.packetJson)
                val target = entry.toDeviceId
                val sent = if (target != null) mesh.sendToDevice(packet, target) else false
                if (!sent) {
                    val backoff = minOf(300_000L, 10_000L * (1 shl minOf(entry.retryCount, 5)))
                    db.outboxDao().updateRetry(entry.packetId, entry.retryCount + 1, now + backoff)
                }
            } catch (e: Exception) {
                Log.e(TAG, "retry failed", e)
            }
        }
    }

    private suspend fun ensureConversationForIncoming(conversationId: String, packet: MeshPacket) {
        val existing = db.conversationDao().getById(conversationId)
        if (existing != null) return
        if (packet.groupId != null) {
            db.conversationDao().upsert(
                ConversationEntity(
                    id = conversationId,
                    title = "Group",
                    isGroup = true,
                    createdAt = System.currentTimeMillis(),
                    memberIds = packet.fromDeviceId
                )
            )
        } else {
            val contact = db.contactDao().getByDeviceId(packet.fromDeviceId)
            db.conversationDao().upsert(
                ConversationEntity(
                    id = conversationId,
                    title = contact?.displayName ?: packet.fromDeviceId.take(8),
                    isGroup = false,
                    createdAt = System.currentTimeMillis(),
                    memberIds = packet.fromDeviceId
                )
            )
        }
    }

    private fun sendAck(packet: MeshPacket) {
        val ack = MeshPacket(
            packetId = UUID.randomUUID().toString(),
            messageId = packet.packetId,
            fromDeviceId = packet.toDeviceId ?: "",
            toDeviceId = packet.fromDeviceId,
            groupId = null,
            type = PacketType.ACK,
            ttl = MeshPacket.DEFAULT_TTL,
            hopCount = 0,
            timestamp = System.currentTimeMillis(),
            payload = packet.messageId
        )
        mesh.sendToDevice(ack, packet.fromDeviceId)
    }

    companion object {
        private const val TAG = "ChatRepository"
    }
}
