package com.offlinemesh.messenger.data

import android.content.Context
import android.net.Uri
import android.util.Base64
import android.util.Log
import com.offlinemesh.messenger.core.crypto.CryptoException
import com.offlinemesh.messenger.core.crypto.CryptoManager
import com.offlinemesh.messenger.core.crypto.DuplicateCiphertextException
import com.offlinemesh.messenger.core.crypto.IdentityChangedException
import com.offlinemesh.messenger.core.crypto.NoSessionYetException
import com.offlinemesh.messenger.core.db.AppDatabase
import com.offlinemesh.messenger.core.db.ContactEntity
import com.offlinemesh.messenger.core.db.ConversationEntity
import com.offlinemesh.messenger.core.db.FileTransferEntity
import com.offlinemesh.messenger.core.db.MessageEntity
import com.offlinemesh.messenger.core.db.RelayEntity
import com.offlinemesh.messenger.core.media.MediaMetadataHelper
import com.offlinemesh.messenger.core.mesh.FileChunker
import com.offlinemesh.messenger.core.mesh.MeshManager
import com.offlinemesh.messenger.core.mesh.PacketCodec
import com.offlinemesh.messenger.core.mesh.PacketHeader
import com.offlinemesh.messenger.core.mesh.PacketType
import com.offlinemesh.messenger.core.mesh.PeerInfo
import com.offlinemesh.messenger.core.mesh.RoutingPolicy
import com.offlinemesh.messenger.domain.HybridReconciler
import com.offlinemesh.messenger.domain.OnlineState
import com.offlinemesh.messenger.domain.Transport
import com.offlinemesh.messenger.domain.TransportContext
import com.offlinemesh.messenger.domain.TransportSelector
import com.offlinemesh.messenger.service.MessageNotifier
import com.offlinemesh.messenger.work.MediaTransferWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlinx.serialization.Serializable
import java.io.File
import java.util.UUID

object ConversationType {
    const val DIRECT = "DIRECT"
    const val GROUP = "GROUP"
    const val CHANNEL = "CHANNEL"
}

object MessageType {
    const val TEXT = "TEXT"
    const val IMAGE = "IMAGE"
    const val VIDEO = "VIDEO"
    const val AUDIO = "AUDIO"
    const val VOICE = "VOICE"
    const val FILE = "FILE"
    const val CALL = "CALL"
    const val SYSTEM = "SYSTEM"
}

object MessageStatus {
    const val QUEUED = "QUEUED"
    const val SENDING = "SENDING"
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
 * Unified conversation & message coordinator spanning both offline Nearby mesh and online
 * Supabase E2EE transports.
 *
 * Guarantees across all 10 hybrid transition scenarios:
 *  - Single unified [ConversationEntity] per peer across mesh -> online and online -> mesh.
 *  - Cross-transport deduplication by `clientMsgId` / `id` + monotonic `HybridReconciler` receipts.
 *  - Streaming E2EE attachment staging, WorkManager background upload/download, cancel & retry.
 *  - Startup crash recovery for interrupted transfers and in-flight outgoing sends.
 */
class ChatRepository(
    private val db: AppDatabase,
    private val crypto: CryptoManager,
    private val mesh: MeshManager,
    private val files: FileRepository,
    private val settings: SettingsRepository,
    private val contacts: ContactRepository,
    private val online: OnlineSync,
    private val context: Context? = null
) : MeshManager.PacketHandler {

    private val conversations = db.conversationDao()
    private val messages = db.messageDao()
    private val relays = db.relayDao()
    private val transfers = db.fileTransferDao()
    private val peers = db.peerDao()
    private val json = PacketCodec.json

    @Volatile private var lastTypingSentAt = 0L

    // --------------------------------------------------------------- observation

    fun observeConversations(): Flow<List<ConversationEntity>> = conversations.observeAll()

    fun observeConversation(id: String): Flow<ConversationEntity?> = conversations.observeById(id)

    fun observeMessages(conversationId: String): Flow<List<MessageEntity>> =
        messages.observeForConversation(conversationId)

    fun observePeers() = mesh.peerFlow

    fun observeRunning() = mesh.running

    fun observeContacts() = contacts.observeContacts()

    suspend fun conversation(id: String): ConversationEntity? = conversations.getById(id)

    suspend fun markRead(conversationId: String) {
        conversations.markRead(conversationId)
        context?.let { MessageNotifier.cancelConversation(it, conversationId) }
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

    /**
     * Ensures a single unified direct conversation for a mesh `deviceId`.
     * If this `deviceId` belongs to an account that already has an online-first conversation,
     * attaches `memberIds = deviceId` to that existing row instead of duplicating the chat!
     */
    suspend fun ensureDirectConversation(deviceId: String, title: String): String {
        val directByDev = conversations.getById(deviceId)
        val resolvedTitle = title.ifBlank { directByDev?.title ?: deviceId.take(8) }

        if (directByDev != null) {
            if (directByDev.title != resolvedTitle && resolvedTitle.isNotBlank()) {
                conversations.upsert(directByDev.copy(title = resolvedTitle))
            }
            return directByDev.id
        }

        // Scenario 2: user chatted online first ("online:<uuid>") and now meets peer on offline mesh!
        val knownUserId = contacts.get(deviceId)?.userId ?: peers.deviceByDeviceId(deviceId)?.userId
        if (!knownUserId.isNullOrBlank()) {
            val onlineExisting = conversations.getAll().firstOrNull {
                it.type == ConversationType.DIRECT && it.peerUserId == knownUserId
            }
            if (onlineExisting != null) {
                val updatedMembers = (membersOf(onlineExisting) + deviceId).distinct().joinToString(",")
                conversations.upsert(
                    onlineExisting.copy(
                        memberIds = updatedMembers,
                        title = onlineExisting.title.ifBlank { resolvedTitle }
                    )
                )
                return onlineExisting.id
            }
        }

        val now = System.currentTimeMillis()
        conversations.upsert(
            ConversationEntity(
                id = deviceId,
                title = resolvedTitle,
                isGroup = false,
                memberIds = deviceId,
                createdAt = now,
                lastMessageAt = now,
                type = ConversationType.DIRECT,
                peerUserId = knownUserId
            )
        )
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
                lastMessageAt = now,
                type = ConversationType.GROUP,
                encryptionVersion = 1
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

    suspend fun sendText(conversationId: String, text: String, replyToId: String? = null) {
        val body = text.trim()
        if (body.isEmpty()) return
        val conversation = conversations.getById(conversationId) ?: return
        val msgId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val replyTo = replyToId?.takeIf { messages.getById(it) != null }
        val entity = MessageEntity(
            id = msgId,
            conversationId = conversationId,
            senderDeviceId = settings.deviceId(),
            outgoing = true,
            type = MessageType.TEXT,
            body = body,
            timestamp = now,
            status = MessageStatus.QUEUED,
            clientMsgId = msgId,
            replyToId = replyTo
        )
        messages.upsert(entity)
        conversations.updateLastMessage(conversationId, now, previewOf(body), 0)
        deliverMessage(entity, conversation, replaceQueued = false)
    }

    /**
     * Multi-attachment E2EE media & document sending (images, videos, audio, PDFs, arbitrary files).
     *
     *  - Online-capable chats stream URI -> local probe/thumbnail -> [FileVault] -> WorkManager
     *    [MediaTransferWorker] (AES-256-GCM + resumable TUS/POST upload up to 128 MiB).
     *  - Purely offline mesh chats use chunked store-and-forward up to [FileChunker.MAX_FILE_SIZE].
     */
    suspend fun sendAttachmentsFromUris(
        conversationId: String,
        uris: List<Uri>,
        caption: String = ""
    ): List<String> = withContext(Dispatchers.IO) {
        val appCtx = context ?: return@withContext emptyList()
        val baseConv = conversations.getById(conversationId) ?: return@withContext emptyList()
        val linked = ensureOnlineLink(baseConv)
        val canSendOnline = online.isSignedIn && linked.onlineId != null
        val createdIds = mutableListOf<String>()

        uris.forEachIndexed { idx, uri ->
            val msgId = UUID.randomUUID().toString()
            val staged = files.stageOnlineAttachment(uri, msgId, isVoiceNote = false) ?: return@forEachIndexed
            val probe = staged.probe

            if (!canSendOnline && probe.sizeBytes > FileChunker.MAX_FILE_SIZE) {
                files.delete(staged.sealedFile)
                throw IllegalArgumentException("file_too_large_for_mesh")
            }

            val bodyText = if (idx == 0 && caption.isNotBlank()) caption.trim() else probe.displayName
            val now = System.currentTimeMillis() + idx
            val entity = MessageEntity(
                id = msgId,
                conversationId = conversationId,
                senderDeviceId = settings.deviceId(),
                outgoing = true,
                type = probe.messageType,
                body = bodyText,
                timestamp = now,
                status = MessageStatus.QUEUED,
                filePath = staged.sealedFile.absolutePath,
                fileName = probe.displayName,
                fileMime = probe.mimeType,
                fileSize = probe.sizeBytes,
                fileSha256 = staged.plainSha256Hex,
                durationMs = probe.durationMs,
                clientMsgId = msgId,
                transport = if (canSendOnline) "ONLINE" else "MESH",
                thumbnailBase64 = probe.thumbnailBase64,
                mediaWidth = probe.width,
                mediaHeight = probe.height,
                transferState = if (canSendOnline) MediaTransferWorker.STATE_QUEUED else MediaTransferWorker.STATE_COMPLETED,
                transferBytes = if (canSendOnline) 0L else probe.sizeBytes
            )
            messages.upsert(entity)
            conversations.updateLastMessage(conversationId, now, previewOf(bodyText), 0)
            createdIds += msgId

            if (canSendOnline) {
                MediaTransferWorker.enqueueUpload(appCtx, msgId)
            } else {
                // Offline-only mesh chunk transfer: open temp plain copy from sealed file & chunk it
                val tempPlain = files.exportForOpening(staged.sealedFile, probe.displayName)
                if (tempPlain != null) {
                    dispatchFile(msgId, linked, tempPlain, probe.displayName, isVoice = false)
                }
            }
        }
        createdIds
    }

    suspend fun sendFile(conversationId: String, source: File, displayName: String) {
        val baseConv = conversations.getById(conversationId) ?: return
        val linked = ensureOnlineLink(baseConv)
        val canSendOnline = online.isSignedIn && linked.onlineId != null && context != null
        val maxAllowed = if (canSendOnline) MediaMetadataHelper.MAX_ONLINE_ATTACHMENT_BYTES else FileChunker.MAX_FILE_SIZE
        if (source.length() > maxAllowed) {
            source.delete()
            throw IllegalArgumentException("file_too_large")
        }

        val msgId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val mime = context?.let { MediaMetadataHelper.resolveMimeType(it, null, displayName) }
            ?: "application/octet-stream"
        val probe = MediaMetadataHelper.probeFile(source, displayName, mime, isVoiceNote = false)
        val stored = files.storeOutgoing(source, displayName, isVoice = false, id = msgId)
        val (_, shaHex) = files.probeAndHashSealedFile(stored.file, displayName, mime, isVoiceNote = false)

        val entity = MessageEntity(
            id = msgId,
            conversationId = conversationId,
            senderDeviceId = settings.deviceId(),
            outgoing = true,
            type = probe.messageType,
            body = displayName,
            timestamp = now,
            status = MessageStatus.QUEUED,
            filePath = stored.file.absolutePath,
            fileName = displayName,
            fileMime = mime,
            fileSize = stored.size,
            fileSha256 = shaHex,
            durationMs = probe.durationMs,
            clientMsgId = msgId,
            transport = if (canSendOnline) "ONLINE" else "MESH",
            thumbnailBase64 = probe.thumbnailBase64,
            mediaWidth = probe.width,
            mediaHeight = probe.height,
            transferState = if (canSendOnline) MediaTransferWorker.STATE_QUEUED else MediaTransferWorker.STATE_COMPLETED,
            transferBytes = if (canSendOnline) 0L else stored.size
        )
        messages.upsert(entity)
        conversations.updateLastMessage(conversationId, now, displayName, 0)

        if (canSendOnline) {
            source.delete()
            MediaTransferWorker.enqueueUpload(context!!, msgId)
        } else {
            dispatchFile(msgId, linked, source, displayName, isVoice = false)
        }
    }

    suspend fun sendVoice(conversationId: String, source: File, durationMs: Long) {
        val baseConv = conversations.getById(conversationId) ?: return
        val linked = ensureOnlineLink(baseConv)
        val canSendOnline = online.isSignedIn && linked.onlineId != null && context != null
        val maxAllowed = if (canSendOnline) MediaMetadataHelper.MAX_ONLINE_ATTACHMENT_BYTES else FileChunker.MAX_FILE_SIZE
        if (source.length() > maxAllowed) {
            source.delete()
            throw IllegalArgumentException("file_too_large")
        }

        val msgId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val stored = files.storeOutgoing(source, "voice.m4a", isVoice = true, id = msgId)
        val (_, shaHex) = files.probeAndHashSealedFile(
            stored.file,
            "voice.m4a",
            "audio/mp4",
            isVoiceNote = true,
            knownDurationMs = durationMs
        )

        val entity = MessageEntity(
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
            fileMime = "audio/mp4",
            fileSize = stored.size,
            fileSha256 = shaHex,
            durationMs = durationMs,
            clientMsgId = msgId,
            transport = if (canSendOnline) "ONLINE" else "MESH",
            transferState = if (canSendOnline) MediaTransferWorker.STATE_QUEUED else MediaTransferWorker.STATE_COMPLETED,
            transferBytes = if (canSendOnline) 0L else stored.size
        )
        messages.upsert(entity)
        conversations.updateLastMessage(conversationId, now, "voice", 0)

        if (canSendOnline) {
            if (targetsOf(linked).any { mesh.isConnected(it) } && stored.size <= FileChunker.MAX_FILE_SIZE) {
                dispatchFile(msgId, linked, source, "voice.m4a", isVoice = true)
            } else {
                source.delete()
            }
            MediaTransferWorker.enqueueUpload(context!!, msgId)
        } else {
            dispatchFile(msgId, linked, source, "voice.m4a", isVoice = true)
        }
    }

    suspend fun downloadAttachment(messageId: String) {
        val ctx = context ?: return
        val msg = messages.getById(messageId) ?: return
        messages.updateTransferState(messageId, MediaTransferWorker.STATE_QUEUED, msg.transferBytes, null)
        MediaTransferWorker.enqueueDownload(ctx, messageId)
    }

    suspend fun retryMediaTransfer(messageId: String) {
        val ctx = context ?: return
        val msg = messages.getById(messageId) ?: return
        messages.updateTransferState(messageId, MediaTransferWorker.STATE_QUEUED, msg.transferBytes, null)
        if (msg.outgoing) {
            MediaTransferWorker.enqueueUpload(ctx, messageId)
        } else {
            MediaTransferWorker.enqueueDownload(ctx, messageId)
        }
    }

    suspend fun cancelMediaTransfer(messageId: String) {
        val msg = messages.getById(messageId) ?: return
        messages.updateTransferState(messageId, MediaTransferWorker.STATE_CANCELLED, msg.transferBytes, "cancelled")
        online.cancelMediaTransfer(messageId)
    }

    /**
     * Startup crash recovery (Scenario 5):
     *  - Resumes or marks paused any media transfers interrupted mid-upload/download by process kill.
     *  - Resets stuck `SENDING` messages back to `QUEUED` so `retryPending()` flushes them.
     */
    suspend fun recoverInterruptedState() {
        runCatching { files.cleanupStaleTempFiles() }
        for (interrupted in messages.interruptedTransfers()) {
            messages.updateTransferState(
                interrupted.id,
                MediaTransferWorker.STATE_PAUSED,
                interrupted.transferBytes,
                null
            )
            context?.let { ctx ->
                if (interrupted.outgoing) {
                    MediaTransferWorker.enqueueUpload(ctx, interrupted.id)
                } else {
                    MediaTransferWorker.enqueueDownload(ctx, interrupted.id)
                }
            }
        }
        for (pending in messages.pendingOutgoing()) {
            if (pending.status == MessageStatus.SENDING) {
                messages.updateStatus(pending.id, MessageStatus.QUEUED)
            }
        }
    }

    private suspend fun deliverMessage(
        message: MessageEntity,
        conversation: ConversationEntity,
        replaceQueued: Boolean
    ) {
        val linked = ensureOnlineLink(conversation)
        val transport = transportFor(linked)
        var onlineSent = false
        var meshSent = false

        val wantsOnline = transport == Transport.ONLINE || transport == Transport.BOTH
        if (wantsOnline) {
            if (message.type == MessageType.TEXT) {
                val result = online.deliver(message, linked)
                if (result.ok) {
                    onlineSent = true
                } else if (result.error == OnlineError.IDENTITY_CHANGED || result.error == OnlineError.BLOCKED) {
                    messages.markFailed(message.id, MessageStatus.FAILED, result.error)
                } else {
                    online.enqueue(message, linked)
                }
            } else if (context != null && message.transferState != MediaTransferWorker.STATE_CANCELLED) {
                if (message.remoteObjectPath.isNullOrBlank()) {
                    MediaTransferWorker.enqueueUpload(context, message.id)
                } else {
                    val res = online.deliver(message, linked)
                    if (res.ok) onlineSent = true else online.enqueue(message, linked)
                }
            }
        }

        if (transport != Transport.ONLINE && message.type == MessageType.TEXT) {
            meshSent = dispatchMeshText(message, linked, replaceQueued)
        }

        val current = messages.getById(message.id) ?: return
        val nextTransport = when {
            onlineSent && meshSent -> "BOTH"
            onlineSent -> HybridReconciler.mergeTransport(current.transport, "ONLINE")
            meshSent -> "MESH"
            else -> current.transport
        }
        val nextStatus = if (onlineSent || meshSent) {
            HybridReconciler.reconcileStatus(current.status, MessageStatus.SENT)
        } else if (current.status != MessageStatus.FAILED) {
            HybridReconciler.reconcileStatus(current.status, MessageStatus.QUEUED)
        } else {
            current.status
        }
        messages.upsert(current.copy(status = nextStatus, transport = nextTransport))
    }

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
                msgId = message.clientMsgId.ifBlank { message.id }
            )
            if (!handOff(header, body)) handedOff = false
        }
        return handedOff
    }

    private suspend fun ensureOnlineLink(conversation: ConversationEntity): ConversationEntity {
        if (conversation.onlineId != null) return conversation
        if (!online.isSignedIn) return conversation
        val peerDev = peerDeviceIdOf(conversation)
        val peerUserId = conversation.peerUserId
            ?: contacts.get(peerDev)?.userId
            ?: peers.deviceByDeviceId(peerDev)?.userId
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
        var handedOff = targets.isNotEmpty()
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
            source.delete()
        }
        val cur = messages.getById(msgId)
        if (cur != null) {
            val next = if (handedOff) HybridReconciler.reconcileStatus(cur.status, MessageStatus.SENT) else cur.status
            messages.updateStatus(msgId, next)
        }
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
        mesh.broadcast(header, body)
        return false
    }

    // ------------------------------------------------------------------ receiving

    override suspend fun onPacket(header: PacketHeader, body: ByteArray) {
        val plaintext = try {
            crypto.decrypt(header.from, body)
        } catch (e: DuplicateCiphertextException) {
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

        // Scenario 4: cross-transport deduplication if already received via ONLINE!
        val existing = messages.getById(msgId) ?: messages.getByClientMsgId(msgId)
        if (existing != null) {
            val merged = HybridReconciler.mergeTransport(existing.transport, "MESH")
            if (existing.transport != merged || existing.packetId == null) {
                messages.upsert(existing.copy(transport = merged, packetId = existing.packetId ?: header.packetId))
            }
            return
        }

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
                packetId = header.packetId,
                clientMsgId = msgId,
                transport = "MESH",
                deliveredAt = System.currentTimeMillis()
            )
        )
        conversations.updateLastMessage(conversationId, header.createdAt, previewOf(body), 1)
        notifyLocalMeshMessage(conversationId, header.from, body)
    }

    private suspend fun notifyLocalMeshMessage(conversationId: String, senderDeviceId: String, body: String) {
        val ctx = context ?: return
        val conv = conversations.getById(conversationId) ?: return
        val snap = runCatching { settings.snapshot() }.getOrNull()
        val title = conv.title.ifBlank { contacts.displayName(senderDeviceId) }
        MessageNotifier.notifyMessage(
            context = ctx,
            conversationId = conversationId,
            title = title,
            body = body,
            conversation = conv,
            settings = snap
        )
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
        val existing = messages.getById(msgId) ?: messages.getByClientMsgId(msgId)
        if (existing == null) {
            val mime = context?.let { MediaMetadataHelper.resolveMimeType(it, null, meta.name) }
            val msgType = MediaMetadataHelper.classifyMessageType(mime, meta.name, isVoiceNote = meta.voice)
            messages.upsert(
                MessageEntity(
                    id = msgId,
                    conversationId = conversationId,
                    senderDeviceId = header.from,
                    outgoing = false,
                    type = msgType,
                    body = meta.name,
                    timestamp = header.createdAt,
                    status = MessageStatus.RECEIVED,
                    filePath = stored.file.absolutePath,
                    fileName = meta.name,
                    fileMime = mime,
                    fileSize = stored.size,
                    packetId = header.packetId,
                    clientMsgId = msgId,
                    transport = "MESH",
                    transferState = MediaTransferWorker.STATE_COMPLETED,
                    transferBytes = stored.size
                )
            )
            conversations.updateLastMessage(conversationId, header.createdAt, meta.name, 1)
            notifyLocalMeshMessage(conversationId, header.from, meta.name)
        } else if (existing.filePath.isNullOrBlank()) {
            messages.markMediaDownloaded(existing.id, stored.file.absolutePath, stored.size)
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
                        lastMessageAt = header.createdAt,
                        type = ConversationType.GROUP
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
        val local = messages.getById(ackFor) ?: messages.getByClientMsgId(ackFor)
        if (local != null) {
            val now = System.currentTimeMillis()
            val mergedTransport = if (local.remoteId != null) {
                HybridReconciler.mergeTransport(local.transport, "MESH")
            } else {
                local.transport
            }
            messages.upsert(
                local.copy(
                    status = HybridReconciler.reconcileStatus(local.status, MessageStatus.DELIVERED),
                    deliveredAt = HybridReconciler.reconcileTimestamp(local.deliveredAt, now) ?: now,
                    transport = mergedTransport
                )
            )
        } else {
            messages.updateStatus(ackFor, MessageStatus.DELIVERED)
        }
        relays.deleteForMessage(ackFor)
    }

    override suspend fun onRelay(header: PacketHeader, body: ByteArray) {
        val now = System.currentTimeMillis()
        if (!RoutingPolicy.shouldForward(header, mesh.deviceId(), now)) return
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
        val now = System.currentTimeMillis()
        relays.purgeExpired(now)
        val entries = relays.forDevice(deviceId)
        for (entry in entries.take(MAX_FLUSH_PER_RUN)) {
            val header = try {
                json.decodeFromString(PacketHeader.serializer(), entry.headerJson)
            } catch (e: Exception) {
                relays.delete(entry.packetId)
                continue
            }
            if (header.expiresAt <= now) {
                relays.delete(entry.packetId)
                continue
            }
            val body = Base64.decode(entry.bodyBase64, Base64.NO_WRAP)
            // Relay hygiene: direct delivery goes strictly to the authenticated target device;
            // multi-hop store-and-forward only relays unexpired ciphertext within hop TTL.
            val sent = if (header.to == deviceId) {
                mesh.sendTo(deviceId, header, body)
            } else if (header.ttl > 1) {
                mesh.sendTo(deviceId, RoutingPolicy.forwarded(header), body)
            } else {
                false
            }
            if (sent && header.to == deviceId) relays.delete(entry.packetId)
        }
    }

    suspend fun retryPending() {
        val now = System.currentTimeMillis()
        relays.purgeExpired(now)
        for (entry in relays.getAll().take(MAX_FLUSH_PER_RUN)) {
            if (entry.expiresAt <= now) {
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
            val sent = if (header.to != null && mesh.isConnected(header.to)) {
                mesh.sendTo(header.to, header, body)
            } else if (header.ttl > 1 && mesh.connectedDeviceIds().isNotEmpty()) {
                mesh.broadcast(RoutingPolicy.forwarded(header), body) > 0
            } else {
                false
            }
            if (sent && header.to != null && mesh.isConnected(header.to)) {
                relays.delete(entry.packetId)
            }
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

    fun observeTyping(): StateFlow<Map<String, List<String>>> = online.typing

    fun typingTimeoutMs(): Long = 8_000L

    fun observeChannels(): Flow<List<ConversationEntity>> = conversations.observeChannels()

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
        val now = System.currentTimeMillis()
        messages.editBody(messageId, text, now)
        val updated = messages.getById(messageId) ?: message.copy(body = text, editedAt = now)
        val conversation = conversations.getById(message.conversationId)
        if (conversation?.onlineId != null && message.remoteId != null && online.isSignedIn) {
            runCatching { online.editMessage(updated, conversation) }
        }
        return true
    }

    suspend fun deleteForEveryone(messageId: String): Boolean {
        val message = messages.getById(messageId) ?: return false
        if (!message.outgoing) return false
        messages.markDeleted(messageId, System.currentTimeMillis())
        message.filePath?.let { files.delete(File(it)) }
        val conversation = conversations.getById(message.conversationId)
        if (conversation?.onlineId != null && message.remoteId != null && online.isSignedIn) {
            runCatching { online.deleteForEveryone(message, conversation) }
        }
        return true
    }

    suspend fun setConversationMuted(conversationId: String, muted: Boolean, mutedUntilMs: Long? = null) {
        val conv = conversations.getById(conversationId) ?: return
        online.setConversationMuted(conv, muted, mutedUntilMs)
    }

    suspend fun upgradeGroupEncryption(conversationId: String): Boolean {
        val conv = conversations.getById(conversationId) ?: return false
        return online.upgradeGroupToSenderKeys(conv)
    }

    suspend fun addGroupMembers(conversationId: String, userIds: List<String>): Boolean {
        val conv = conversations.getById(conversationId) ?: return false
        return online.addGroupMembers(conv, userIds)
    }

    suspend fun removeGroupMember(conversationId: String, memberUserId: String): Boolean {
        val conv = conversations.getById(conversationId) ?: return false
        return online.removeGroupMember(conv, memberUserId)
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

    suspend fun search(query: String): LocalSearchResult {
        val trimmed = query.trim()
        if (trimmed.length < 2) return LocalSearchResult(emptyList(), emptyList(), emptyList())
        val pattern = "%" + trimmed.replace("%", "\\%").replace("_", "\\_") + "%"
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
