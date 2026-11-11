package com.offlinemesh.messenger.data

import android.util.Base64
import android.util.Log
import com.offlinemesh.messenger.core.crypto.CryptoException
import com.offlinemesh.messenger.core.crypto.CryptoManager
import com.offlinemesh.messenger.core.crypto.DuplicateCiphertextException
import com.offlinemesh.messenger.core.crypto.IdentityChangedException
import com.offlinemesh.messenger.core.crypto.NoSessionYetException
import com.offlinemesh.messenger.core.db.AppDatabase
import com.offlinemesh.messenger.core.db.ConversationEntity
import com.offlinemesh.messenger.core.db.FileTransferEntity
import com.offlinemesh.messenger.core.db.MessageEntity
import com.offlinemesh.messenger.core.db.RelayEntity
import com.offlinemesh.messenger.core.mesh.FileChunker
import com.offlinemesh.messenger.core.mesh.MeshManager
import com.offlinemesh.messenger.core.mesh.PacketCodec
import com.offlinemesh.messenger.core.mesh.PacketHeader
import com.offlinemesh.messenger.core.mesh.PacketType
import com.offlinemesh.messenger.core.mesh.PeerInfo
import com.offlinemesh.messenger.core.mesh.RoutingPolicy
import com.offlinemesh.messenger.domain.OnlineState
import com.offlinemesh.messenger.domain.Transport
import com.offlinemesh.messenger.domain.TransportContext
import com.offlinemesh.messenger.domain.TransportSelector
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.yield
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

object MessageType {
    const val TEXT = "TEXT"
    const val FILE = "FILE"
    const val VOICE = "VOICE"
}

object MessageStatus {
    const val QUEUED = "QUEUED"
    const val SENT = "SENT"
    const val DELIVERED = "DELIVERED"
    const val FAILED = "FAILED"
    const val RECEIVED = "RECEIVED"
}

@Serializable
data class FileChunkMeta(
    val fileId: String,
    val name: String,
    val index: Int,
    val total: Int,
    val size: Long = 0L,
    val voice: Boolean = false
)

/**
 * Conversation/message logic.
 *
 * Outgoing plaintext exists only in the (SQLCipher-encrypted) message table. It is encrypted
 * per recipient at send time, and the resulting ciphertext is what enters the relay queue for
 * store-and-forward. Relaying other people's packets only ever moves ciphertext.
 */
class ChatRepository(
    private val db: AppDatabase,
    private val crypto: CryptoManager,
    private val mesh: MeshManager,
    private val files: FileRepository,
    private val settings: SettingsRepository,
    private val contacts: ContactRepository,
    private val online: OnlineSync
) : MeshManager.PacketHandler {

    private val conversations = db.conversationDao()
    private val messages = db.messageDao()
    private val relays = db.relayDao()
    private val transfers = db.fileTransferDao()
    private val json = PacketCodec.json

    @Volatile private var lastTypingSentAt = 0L

    // --------------------------------------------------------------- observation

    fun observeConversations(): Flow<List<ConversationEntity>> = conversations.observeAll()

    fun observeMessages(conversationId: String): Flow<List<MessageEntity>> =
        messages.observeForConversation(conversationId)

    fun observePeers() = mesh.peerFlow

    fun observeRunning() = mesh.running

    fun observeContacts() = contacts.observeContacts()

    suspend fun conversation(id: String): ConversationEntity? = conversations.getById(id)

    suspend fun markRead(conversationId: String) {
        conversations.markRead(conversationId)
        // Online read receipts are best effort: a failure must never block the UI.
        val conversation = conversations.getById(conversationId) ?: return
        if (conversation.onlineId == null || !online.isSignedIn) return
        val latest = messages.latestForConversation(conversationId) ?: return
        if (!latest.outgoing) online.markRead(conversation, latest)
    }

    suspend fun deleteConversation(conversationId: String) {
        val conversation = conversations.getById(conversationId)
        if (conversation?.onlineId != null && online.isSignedIn) {
            runCatching { online.leaveConversation(conversation) }
        }
        messages.deleteForConversation(conversationId)
        conversations.deleteById(conversationId)
    }

    suspend fun exportForOpening(sealedPath: String?, displayName: String): File? {
        if (sealedPath.isNullOrBlank()) return null
        return files.exportForOpening(File(sealedPath), displayName)
    }

    // ------------------------------------------------------------- conversations

    suspend fun ensureDirectConversation(deviceId: String, title: String): String {
        val existing = conversations.getById(deviceId)
        val resolvedTitle = title.ifBlank { existing?.title ?: deviceId.take(8) }
        if (existing == null) {
            val now = System.currentTimeMillis()
            conversations.upsert(
                ConversationEntity(
                    id = deviceId,
                    title = resolvedTitle,
                    isGroup = false,
                    memberIds = deviceId,
                    createdAt = now,
                    lastMessageAt = now
                )
            )
        } else if (existing.title != resolvedTitle) {
            conversations.upsert(existing.copy(title = resolvedTitle))
        }
        return deviceId
    }

    suspend fun createGroup(name: String, memberIds: List<String>): String {
        val id = "group:" + UUID.randomUUID().toString()
        val me = settings.deviceId()
        val members = (memberIds + me).filter { it.isNotBlank() }.distinct()
        val now = System.currentTimeMillis()
        conversations.upsert(
            ConversationEntity(
                id = id,
                title = name.ifBlank { "Group" },
                isGroup = true,
                memberIds = members.joinToString(","),
                createdAt = now,
                lastMessageAt = now
            )
        )
        return id
    }

    private fun membersOf(conversation: ConversationEntity): List<String> =
        conversation.memberIds.split(',').map { it.trim() }.filter { it.isNotEmpty() }

    private suspend fun targetsOf(conversation: ConversationEntity): List<String> {
        val me = settings.deviceId()
        return membersOf(conversation).filter { it != me }
    }

    // -------------------------------------------------------------------- sending

    suspend fun sendText(conversationId: String, text: String) {
        val body = text.trim()
        if (body.isEmpty()) return
        val conversation = conversations.getById(conversationId) ?: return
        val msgId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val entity = MessageEntity(
            id = msgId,
            conversationId = conversationId,
            senderDeviceId = settings.deviceId(),
            outgoing = true,
            type = MessageType.TEXT,
            body = body,
            timestamp = now,
            status = MessageStatus.QUEUED
        )
        messages.upsert(entity)
        conversations.updateLastMessage(conversationId, now, previewOf(body), 0)
        deliverMessage(entity, conversation, replaceQueued = false)
    }

    suspend fun sendFile(conversationId: String, source: File, displayName: String) {
        val conversation = conversations.getById(conversationId) ?: return
        if (source.length() > FileChunker.MAX_FILE_SIZE) {
            throw IllegalArgumentException("file too large")
        }
        val msgId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val stored = files.storeOutgoing(source, displayName, isVoice = false, id = msgId)
        messages.upsert(
            MessageEntity(
                id = msgId,
                conversationId = conversationId,
                senderDeviceId = settings.deviceId(),
                outgoing = true,
                type = MessageType.FILE,
                body = displayName,
                timestamp = now,
                status = MessageStatus.QUEUED,
                filePath = stored.file.absolutePath,
                fileName = displayName,
                fileSize = stored.size
            )
        )
        conversations.updateLastMessage(conversationId, now, displayName, 0)
        dispatchFile(msgId, conversation, source, displayName, isVoice = false)
    }

    suspend fun sendVoice(conversationId: String, source: File, durationMs: Long) {
        val conversation = conversations.getById(conversationId) ?: return
        if (source.length() > FileChunker.MAX_FILE_SIZE) {
            throw IllegalArgumentException("file too large")
        }
        val msgId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val stored = files.storeOutgoing(source, "voice.m4a", isVoice = true, id = msgId)
        messages.upsert(
            MessageEntity(
                id = msgId,
                conversationId = conversationId,
                senderDeviceId = settings.deviceId(),
                outgoing = true,
                type = MessageType.VOICE,
                body = "voice",
                timestamp = now,
                status = MessageStatus.QUEUED,
                filePath = stored.file.absolutePath,
                fileName = "voice.m4a",
                fileSize = stored.size,
                durationMs = durationMs
            )
        )
        conversations.updateLastMessage(conversationId, now, "voice", 0)
        dispatchFile(msgId, conversation, source, "voice.m4a", isVoice = true)
    }

    /**
     * Delivers one outgoing message over the best available transport(s).
     *
     * The order matters: the ciphertext is produced per transport, the message stays queued until
     * at least one transport accepted it, and the online outbox keeps the retry alive so a
     * connection drop never loses the message.
     */
    private suspend fun deliverMessage(
        message: MessageEntity,
        conversation: ConversationEntity,
        replaceQueued: Boolean
    ) {
        val linked = ensureOnlineLink(conversation)
        val transport = transportFor(linked)
        var handedOff = false

        val wantsOnline = transport == Transport.ONLINE || transport == Transport.BOTH
        if (wantsOnline && message.type == MessageType.TEXT) {
            val result = online.deliver(message, linked)
            if (result.ok) {
                handedOff = true
                messages.updateStatus(message.id, MessageStatus.SENT)
            } else if (result.error == OnlineError.IDENTITY_CHANGED || result.error == OnlineError.BLOCKED) {
                messages.markFailed(message.id, MessageStatus.FAILED, result.error)
            } else {
                online.enqueue(message, linked)
            }
        } else if (wantsOnline && message.type != MessageType.TEXT) {
            // Media over the online transport is not enabled in this build: the file path uploads
            // encrypted blobs to storage, which is a separate, documented follow-up. Never fake it.
            messages.markFailed(message.id, MessageStatus.QUEUED, "media_online_unsupported")
        }

        if (transport != Transport.ONLINE) {
            handedOff = dispatchMeshText(message, linked, replaceQueued) || handedOff
        }

        if (handedOff) {
            messages.updateStatus(message.id, MessageStatus.SENT)
        } else if (messages.getById(message.id)?.status != MessageStatus.FAILED) {
            messages.updateStatus(message.id, MessageStatus.QUEUED)
        }
    }

    /** Mesh path: encrypt per peer device, queue for store-and-forward, try to hand off now. */
    private suspend fun dispatchMeshText(
        message: MessageEntity,
        conversation: ConversationEntity,
        replaceQueued: Boolean
    ): Boolean {
        val targets = targetsOf(conversation)
        if (targets.isEmpty()) return false
        if (replaceQueued) relays.deleteForMessage(message.id)
        var handedOff = true
        for (target in targets) {
            val plaintext = message.body.toByteArray(Charsets.UTF_8)
            val body = try {
                crypto.encrypt(target, plaintext)
            } catch (e: NoSessionYetException) {
                handedOff = false
                continue
            } catch (e: IdentityChangedException) {
                handedOff = false
                continue
            } catch (e: CryptoException) {
                handedOff = false
                continue
            }
            val header = header(
                type = PacketType.TEXT,
                conversation = conversation,
                target = target,
                msgId = message.id
            )
            if (!handOff(header, body)) handedOff = false
        }
        return handedOff
    }

    /**
     * Attaches a mesh conversation to its online counterpart the first time an online send is
     * attempted, so one local row serves both transports.
     */
    private suspend fun ensureOnlineLink(conversation: ConversationEntity): ConversationEntity {
        if (conversation.onlineId != null) return conversation
        if (!online.isSignedIn) return conversation
        val peerUserId = conversation.peerUserId
            ?: contacts.get(peerDeviceIdOf(conversation))?.userId
            ?: return conversation
        return online.linkDirectConversation(conversation, peerUserId) ?: conversation
    }

    private fun peerDeviceIdOf(conversation: ConversationEntity): String =
        conversation.memberIds.split(',').map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()

    private fun transportFor(conversation: ConversationEntity): Transport {
        val members = conversation.memberIds.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val peerInRange = members.any { mesh.isConnected(it) }
        return TransportSelector.select(
            TransportContext(
                signedIn = online.isSignedIn,
                networkAvailable = online.networkAvailable,
                conversationLinkedOnline = conversation.onlineId != null,
                meshPeerConnected = peerInRange
            )
        )
    }

    private suspend fun dispatchFile(
        msgId: String,
        conversation: ConversationEntity,
        source: File,
        displayName: String,
        isVoice: Boolean
    ) {
        val total = FileChunker.chunkCount(source)
        val size = source.length()
        val targets = targetsOf(conversation)
        var handedOff = true
        val type = if (isVoice) PacketType.VOICE else PacketType.FILE_CHUNK
        try {
            for (index in 0 until total) {
                val chunk = FileChunker.readChunk(source, index)
                val meta = json.encodeToString(
                    FileChunkMeta.serializer(),
                    FileChunkMeta(msgId, displayName, index, total, size, isVoice)
                )
                for (target in targets) {
                    val body = try {
                        crypto.encrypt(target, chunk)
                    } catch (e: NoSessionYetException) {
                        handedOff = false
                        continue
                    } catch (e: IdentityChangedException) {
                        handedOff = false
                        continue
                    } catch (e: CryptoException) {
                        handedOff = false
                        continue
                    }
                    val header = header(
                        type = type,
                        conversation = conversation,
                        target = target,
                        msgId = msgId,
                        meta = meta
                    )
                    if (!handOff(header, body)) handedOff = false
                }
                yield()
            }
        } finally {
            // The plaintext copy in the cache is no longer needed: the sealed copy is at rest.
            source.delete()
        }
        messages.updateStatus(msgId, if (handedOff) MessageStatus.SENT else MessageStatus.QUEUED)
    }

    private fun header(
        type: PacketType,
        conversation: ConversationEntity,
        target: String,
        msgId: String,
        meta: String = ""
    ): PacketHeader {
        val now = System.currentTimeMillis()
        return PacketHeader(
            packetId = UUID.randomUUID().toString(),
            type = type,
            from = mesh.deviceId(),
            to = target,
            groupId = if (conversation.isGroup) conversation.id else null,
            groupName = if (conversation.isGroup) conversation.title else null,
            msgId = msgId,
            createdAt = now,
            expiresAt = RoutingPolicy.expiryFrom(now),
            ttl = RoutingPolicy.DEFAULT_TTL,
            meta = meta
        )
    }

    /**
     * Stores the ciphertext for store-and-forward and tries to push it now.
     * @return true when it left the device.
     */
    private suspend fun handOff(header: PacketHeader, body: ByteArray): Boolean {
        if (header.expiresAt <= System.currentTimeMillis()) return false
        if (relays.count() < MAX_RELAY_ENTRIES) {
            relays.insertIfAbsent(
                RelayEntity(
                    packetId = header.packetId,
                    headerJson = json.encodeToString(PacketHeader.serializer(), header),
                    bodyBase64 = Base64.encodeToString(body, Base64.NO_WRAP),
                    msgId = header.msgId,
                    targetDeviceId = header.to,
                    createdAt = header.createdAt,
                    expiresAt = header.expiresAt
                )
            )
        }
        if (header.to != null && mesh.sendTo(header.to, header, body)) {
            relays.delete(header.packetId)
            return true
        }
        // No direct route: let the neighbours carry it (they will forward it hop by hop).
        mesh.broadcast(header, body)
        return false
    }

    // ------------------------------------------------------------------ receiving

    override suspend fun onPacket(header: PacketHeader, body: ByteArray) {
        val plaintext = try {
            crypto.decrypt(header.from, body)
        } catch (e: DuplicateCiphertextException) {
            // Retransmission of something we already accepted: just re-acknowledge it.
            sendAck(header)
            return
        } catch (e: Exception) {
            Log.w(TAG, "Could not decrypt ${header.type} from ${header.from}: ${e.message}")
            return
        }

        when (header.type) {
            PacketType.TEXT -> onText(header, plaintext)
            PacketType.FILE_CHUNK, PacketType.VOICE -> onChunk(header, plaintext)
            else -> Unit
        }
        sendAck(header)
    }

    private suspend fun onText(header: PacketHeader, plaintext: ByteArray) {
        val conversationId = conversationFor(header)
        val msgId = header.msgId.ifBlank { header.packetId }
        if (messages.getById(msgId) != null) return
        val body = String(plaintext, Charsets.UTF_8)
        messages.upsert(
            MessageEntity(
                id = msgId,
                conversationId = conversationId,
                senderDeviceId = header.from,
                outgoing = false,
                type = MessageType.TEXT,
                body = body,
                timestamp = header.createdAt,
                status = MessageStatus.RECEIVED,
                packetId = header.packetId
            )
        )
        conversations.updateLastMessage(conversationId, header.createdAt, previewOf(body), 1)
    }

    private suspend fun onChunk(header: PacketHeader, plaintext: ByteArray) {
        val meta = try {
            json.decodeFromString(FileChunkMeta.serializer(), header.meta)
        } catch (e: Exception) {
            Log.w(TAG, "Chunk without metadata", e)
            return
        }
        val conversationId = conversationFor(header)
        val chunkFile = File(files.stagingFileFor(meta.fileId), "chunk_${meta.index}.bin")
        val isNew = !chunkFile.exists()
        files.writeChunk(meta.fileId, meta.index, plaintext)

        val transfer = transfers.getById(meta.fileId)
        val received = ((transfer?.receivedChunks ?: 0) + if (isNew) 1 else 0)
            .coerceAtMost(meta.total)
        if (transfer == null) {
            transfers.upsert(
                FileTransferEntity(
                    fileId = meta.fileId,
                    conversationId = conversationId,
                    fileName = meta.name,
                    totalChunks = meta.total,
                    receivedChunks = received,
                    status = "IN_PROGRESS",
                    outputPath = "",
                    createdAt = header.createdAt
                )
            )
        } else {
            transfers.updateProgress(
                meta.fileId,
                received,
                if (received >= meta.total) "ASSEMBLING" else "IN_PROGRESS"
            )
        }

        if (received >= meta.total) {
            completeTransfer(meta, header, conversationId)
        }
    }

    private suspend fun completeTransfer(
        meta: FileChunkMeta,
        header: PacketHeader,
        conversationId: String
    ) {
        val stored = files.completeIncoming(meta.fileId, meta.name, meta.total, meta.voice) ?: return
        val msgId = header.msgId.ifBlank { meta.fileId }
        if (messages.getById(msgId) == null) {
            messages.upsert(
                MessageEntity(
                    id = msgId,
                    conversationId = conversationId,
                    senderDeviceId = header.from,
                    outgoing = false,
                    type = if (meta.voice) MessageType.VOICE else MessageType.FILE,
                    body = meta.name,
                    timestamp = header.createdAt,
                    status = MessageStatus.RECEIVED,
                    filePath = stored.file.absolutePath,
                    fileName = meta.name,
                    fileSize = stored.size,
                    packetId = header.packetId
                )
            )
            conversations.updateLastMessage(conversationId, header.createdAt, meta.name, 1)
        }
        transfers.updateProgress(meta.fileId, meta.total, "COMPLETE")
    }

    private suspend fun conversationFor(header: PacketHeader): String {
        val groupId = header.groupId
        if (groupId != null) {
            val existing = conversations.getById(groupId)
            if (existing == null) {
                conversations.upsert(
                    ConversationEntity(
                        id = groupId,
                        title = header.groupName?.takeIf { it.isNotBlank() } ?: "Group",
                        isGroup = true,
                        memberIds = "",
                        createdAt = header.createdAt,
                        lastMessageAt = header.createdAt
                    )
                )
            }
            return groupId
        }
        val peer = header.from
        return ensureDirectConversation(peer, contacts.displayName(peer))
    }

    private suspend fun sendAck(header: PacketHeader) {
        val me = mesh.deviceId()
        if (header.from.isBlank() || header.from == me) return
        val now = System.currentTimeMillis()
        val ack = PacketHeader(
            packetId = UUID.randomUUID().toString(),
            type = PacketType.ACK,
            from = me,
            to = header.from,
            msgId = "",
            createdAt = now,
            expiresAt = RoutingPolicy.expiryFrom(now),
            ttl = RoutingPolicy.DEFAULT_TTL,
            ackFor = header.msgId.ifBlank { header.packetId }
        )
        handOff(ack, ByteArray(0))
    }

    override suspend fun onAck(header: PacketHeader) {
        val ackFor = header.ackFor ?: return
        messages.updateStatus(ackFor, MessageStatus.DELIVERED)
        relays.deleteForMessage(ackFor)
    }

    override suspend fun onRelay(header: PacketHeader, body: ByteArray) {
        if (header.expiresAt <= System.currentTimeMillis()) return
        if (relays.count() >= MAX_RELAY_ENTRIES) return
        relays.insertIfAbsent(
            RelayEntity(
                packetId = header.packetId,
                headerJson = json.encodeToString(PacketHeader.serializer(), header),
                bodyBase64 = Base64.encodeToString(body, Base64.NO_WRAP),
                msgId = header.msgId,
                targetDeviceId = header.to,
                createdAt = header.createdAt,
                expiresAt = header.expiresAt
            )
        )
    }

    override suspend fun onPeerReady(peer: PeerInfo) {
        contacts.rememberPeer(peer.deviceId, peer.displayName)
        ensureDirectConversation(peer.deviceId, contacts.displayName(peer.deviceId))
        flushQueue(peer.deviceId)
        retryPending()
    }

    override suspend fun onPeerGone(deviceId: String) {
        Log.i(TAG, "Peer left: $deviceId")
    }

    // --------------------------------------------------------------- store & forward

    private suspend fun flushQueue(deviceId: String) {
        val entries = relays.forDevice(deviceId)
        for (entry in entries.take(MAX_FLUSH_PER_RUN)) {
            val header = try {
                json.decodeFromString(PacketHeader.serializer(), entry.headerJson)
            } catch (e: Exception) {
                relays.delete(entry.packetId)
                continue
            }
            if (header.expiresAt <= System.currentTimeMillis()) {
                relays.delete(entry.packetId)
                continue
            }
            val body = Base64.decode(entry.bodyBase64, Base64.NO_WRAP)
            val sent = if (header.to != null) {
                mesh.sendTo(header.to, header, body)
            } else {
                mesh.broadcast(header, body) > 0
            }
            if (sent && header.to != null) relays.delete(entry.packetId)
        }
    }

    /** Called on peer connect, on the periodic worker and when the app comes back to foreground. */
    suspend fun retryPending() {
        relays.purgeExpired(System.currentTimeMillis())
        for (entry in relays.getAll().take(MAX_FLUSH_PER_RUN)) {
            if (entry.expiresAt <= System.currentTimeMillis()) {
                relays.delete(entry.packetId)
                continue
            }
            val header = try {
                json.decodeFromString(PacketHeader.serializer(), entry.headerJson)
            } catch (e: Exception) {
                relays.delete(entry.packetId)
                continue
            }
            val body = Base64.decode(entry.bodyBase64, Base64.NO_WRAP)
            val sent = if (header.to != null) {
                mesh.sendTo(header.to, header, body)
            } else if (mesh.connectedDeviceIds().isNotEmpty()) {
                mesh.broadcast(header, body) > 0
            } else {
                false
            }
            if (sent && header.to != null) relays.delete(entry.packetId)
        }

        for (message in messages.pendingOutgoing()) {
            if (message.status != MessageStatus.QUEUED) continue
            val conversation = conversations.getById(message.conversationId) ?: continue
            deliverMessage(message, conversation, replaceQueued = true)
        }
        runCatching { online.drainOutbox() }
    }

    // ----------------------------------------------------------- hybrid extras

    fun observeOnlineState(): StateFlow<OnlineState> = online.state

    fun observeNetwork(): StateFlow<Boolean> = online.networkFlow()

    fun observeTyping(): StateFlow<Map<String, List<String>>>  = online.typing

    fun typingTimeoutMs(): Long = 8_000L

    fun observeChannels(): Flow<List<ConversationEntity>> = conversations.observeChannels()

    /** Signals "user is typing" to the online peers of this conversation (at most every 5 s). */
    suspend fun notifyTyping(conversationId: String) {
        val conversation = conversations.getById(conversationId) ?: return
        if (conversation.onlineId == null || !online.isSignedIn) return
        val now = System.currentTimeMillis()
        if (now - lastTypingSentAt < TYPING_THROTTLE_MS) return
        lastTypingSentAt = now
        runCatching { online.notifyTyping(conversation) }
    }

    suspend fun saveDraft(conversationId: String, text: String) =
        conversations.saveDraft(conversationId, text)

    /** Adds/removes a reaction. Reactions are an online feature; the mesh protocol has none. */
    suspend fun toggleReaction(messageId: String, emoji: String) {
        val message = messages.getById(messageId) ?: return
        if (message.remoteId == null) return
        val already = message.reactions.split(',').any { it.startsWith(emoji) }
        if (already) online.removeReaction(message, emoji) else online.addReaction(message, emoji)
    }

    suspend fun setStarred(messageId: String, starred: Boolean) =
        messages.setStarred(messageId, starred)

    suspend fun editMessage(messageId: String, newBody: String): Boolean {
        val message = messages.getById(messageId) ?: return false
        if (!message.outgoing || message.deletedAt != null) return false
        val text = newBody.trim()
        if (text.isEmpty()) return false
        messages.editBody(messageId, text, System.currentTimeMillis())
        val conversation = conversations.getById(message.conversationId)
        if (conversation?.onlineId != null && message.remoteId != null && online.isSignedIn) {
            runCatching { online.editMessage(message, conversation) }
        }
        return true
    }

    suspend fun deleteForEveryone(messageId: String): Boolean {
        val message = messages.getById(messageId) ?: return false
        if (!message.outgoing) return false
        messages.markDeleted(messageId, System.currentTimeMillis())
        val conversation = conversations.getById(message.conversationId)
        if (conversation?.onlineId != null && message.remoteId != null && online.isSignedIn) {
            runCatching { online.deleteForEveryone(message, conversation) }
        }
        return true
    }

    suspend fun openChatWithAccount(userId: String): String? = online.openDirectChat(userId)

    suspend fun createOnlineGroup(title: String, memberUserIds: List<String>): String? =
        online.createGroup(title, memberUserIds)

    suspend fun searchAccounts(query: String): List<RemoteProfile> = online.searchUsers(query)

    suspend fun searchChannels(query: String): List<RemoteChannel> = online.searchChannels(query)

    suspend fun createChannel(title: String, username: String?, isPublic: Boolean): String? =
        online.createChannel(title, username, isPublic)

    suspend fun joinChannel(onlineId: String, invite: String?): String? = online.joinChannel(onlineId, invite)

    fun observeContactsWithAccounts(): Flow<List<ContactEntity>> = contacts.observeContacts()

    /**
     * Local search over decrypted content. The server only ever holds ciphertext, so search must
     * happen on the device - which is also the privacy-preserving answer.
     */
    suspend fun search(query: String): LocalSearchResult {
        val trimmed = query.trim()
        if (trimmed.length < 2) return LocalSearchResult(emptyList(), emptyList(), emptyList())
        val pattern = "%" + trimmed.replace("%", "\%").replace("_", "\_") + "%"
        val foundMessages = messages.search(pattern, null)
        val foundConversations = conversations.search(pattern)
        val foundContacts = contacts.search(pattern)
        return LocalSearchResult(foundMessages, foundConversations, foundContacts)
    }

    data class LocalSearchResult(
        val messages: List<MessageEntity>,
        val conversations: List<ConversationEntity>,
        val contacts: List<ContactEntity>
    )

    private fun previewOf(text: String): String =
        text.replace('\n', ' ').take(120)

    private companion object {
        const val TAG = "ChatRepository"
        const val MAX_RELAY_ENTRIES = 600
        const val MAX_FLUSH_PER_RUN = 200
        const val TYPING_THROTTLE_MS = 5_000L
    }
}
