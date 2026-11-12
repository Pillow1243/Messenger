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
import com.offlinemesh.messenger.core.mesh.MeshInboundPolicy
import com.offlinemesh.messenger.core.mesh.MeshManager
import com.offlinemesh.messenger.core.mesh.PacketCodec
import com.offlinemesh.messenger.core.mesh.PacketHeader
import com.offlinemesh.messenger.core.mesh.PacketType
import com.offlinemesh.messenger.core.mesh.PeerInfo
import com.offlinemesh.messenger.core.mesh.RoutingPolicy
import com.offlinemesh.messenger.core.mesh.SealedHeader
import com.offlinemesh.messenger.core.mesh.SealedPayload
import com.offlinemesh.messenger.core.mesh.BoundedMemo
import com.offlinemesh.messenger.core.mesh.TransmitLog
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
    private val outbox = db.outboxDao()
    private val json = PacketCodec.json

    @Volatile private var lastTypingSentAt = 0L
    @Volatile private var lastAttachmentProblem: String? = null

    /** When each queued packet last went out, to space retransmissions. */
    private val lastTransmit = TransmitLog()

    /** When a repeated packet was last answered, so a flood of copies costs one receipt. */
    private val reAckLimiter = TransmitLog()

    /** The receipt given for each packet (the completed message id, or ""), to repeat it verbatim. */
    private val ackMemo = BoundedMemo<String>()

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

    /**
     * Deletes a chat from this device. Returns false, and deletes nothing, when the chat is an
     * online group or channel and the server could not be told that this account left: the
     * account would stay a member, keep receiving the conversation, and the "deleted" chat would
     * be back after the next sync.
     */
    suspend fun deleteConversation(conversationId: String): Boolean {
        val conversation = conversations.getById(conversationId) ?: return true
        if (conversation.onlineId != null) {
            val left = online.isSignedIn &&
                runCatching { online.leaveConversation(conversation) }.getOrDefault(false)
            val shared = conversation.type != ConversationType.DIRECT
            if (!left && shared) return false
        }
        // Everything that belongs to the chat goes with it, not only the rows: queued packets,
        // pending uploads and the sealed attachment files used to stay behind.
        for (id in messages.pendingIdsForConversation(conversationId)) {
            relays.deleteForMessage(id)
            outbox.deleteForMessage(id)
        }
        for (path in messages.filePathsForConversation(conversationId)) {
            runCatching { files.delete(File(path)) }
        }
        messages.deleteForConversation(conversationId)
        conversations.deleteById(conversationId)
        context?.let { MessageNotifier.cancelConversation(it, conversationId) }
        return true
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
        var firstProblem: Throwable? = null

        uris.forEachIndexed { idx, uri ->
            val msgId = UUID.randomUUID().toString()
            // One unusable attachment must not abort the ones after it: the caller used to get an
            // exception for the whole batch while half of it had already been sent.
            val stagedOrNull = try {
                files.stageOnlineAttachment(uri, msgId, isVoiceNote = false)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (firstProblem == null) firstProblem = e
                null
            }
            val staged = stagedOrNull ?: return@forEachIndexed
            val probe = staged.probe

            if (!canSendOnline && probe.sizeBytes > FileChunker.MAX_FILE_SIZE) {
                files.delete(staged.sealedFile)
                if (firstProblem == null) firstProblem = IllegalArgumentException("file_too_large_for_mesh")
                return@forEachIndexed
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
                val tempPlain = files.exportForMesh(staged.sealedFile, msgId)
                if (tempPlain != null) {
                    dispatchFile(msgId, linked, tempPlain, probe.displayName, isVoice = false)
                } else {
                    messages.updateStatus(msgId, MessageStatus.FAILED)
                    messages.updateTransferState(msgId, MediaTransferWorker.STATE_FAILED, 0L, "local_file_unreadable")
                }
            }
        }
        lastAttachmentProblem = firstProblem?.message
        // Nothing went out at all: report why. Otherwise the caller gets the ids that were
        // created and asks lastAttachmentProblem() about the rest.
        firstProblem?.let { if (createdIds.isEmpty()) throw it }
        createdIds
    }

    /** Why the last batch of attachments was only partly sent, or null when all of it was. */
    fun lastAttachmentProblem(): String? = lastAttachmentProblem

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
        val conversation = conversations.getById(msg.conversationId)
        if (msg.outgoing && conversation != null && (conversation.onlineId == null || !online.isSignedIn)) {
            // A mesh-only attachment has nothing to upload; cut it into packets again.
            val sealed = msg.filePath?.let { File(it) }
            val plain = sealed?.let { files.exportForMesh(it, msg.id) }
            if (plain == null) {
                messages.updateTransferState(messageId, MediaTransferWorker.STATE_FAILED, 0L, "local_file_unreadable")
                return
            }
            relays.deleteForMessage(msg.id)
            messages.updateStatus(messageId, MessageStatus.QUEUED)
            messages.updateTransferState(messageId, MediaTransferWorker.STATE_COMPLETED, msg.fileSize ?: plain.length(), null)
            dispatchFile(msg.id, conversation, plain, msg.fileName ?: "attachment", isVoice = msg.type == MessageType.VOICE)
            return
        }
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
        val wireMsgId = message.clientMsgId.ifBlank { message.id }
        var handedOff = true
        for (target in targets) {
            // A retry must not encrypt the text again while a sealed copy is still queued for
            // this recipient: every encryption advances the ratchet, and a message that waited a
            // week for its recipient used to burn a message key on every pass. The queued copy
            // is what gets repeated (see transmit()).
            if (replaceQueued && relays.countForMessageAndTarget(wireMsgId, target) > 0) {
                if (!mesh.isConnected(target)) handedOff = false
                continue
            }
            val header = header(
                type = PacketType.TEXT,
                conversation = conversation,
                target = target,
                msgId = wireMsgId
            )
            val body = sealAndEncrypt(header, conversation, message.body.toByteArray(Charsets.UTF_8))
            if (body == null) {
                handedOff = false
                continue
            }
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

        // A recipient that is out of range gets the file from the queue, so the queue has to be
        // able to hold all of it. It used to accept what fitted and drop the rest without a word,
        // leaving a transfer that could never complete and a message that still looked sent.
        val needsQueue = targets.any { !mesh.isConnected(it) }
        if (needsQueue && relays.count() + total.toLong() * targets.size > MAX_RELAY_ENTRIES) {
            source.delete()
            val cur = messages.getById(msgId)
            // With an online copy on its way the mesh attempt is only an extra; say nothing.
            if (cur != null && cur.remoteId == null && conversation.onlineId == null) {
                messages.updateStatus(msgId, MessageStatus.FAILED)
                messages.updateTransferState(
                    msgId, MediaTransferWorker.STATE_FAILED, 0L, ERROR_MESH_QUEUE_FULL
                )
            }
            return
        }

        try {
            for (index in 0 until total) {
                val chunk = FileChunker.readChunk(source, index)
                val meta = json.encodeToString(
                    FileChunkMeta.serializer(),
                    FileChunkMeta(msgId, displayName, index, total, size, isVoice)
                )
                for (target in targets) {
                    val header = header(
                        type = type,
                        conversation = conversation,
                        target = target,
                        msgId = msgId,
                        meta = meta
                    )
                    val body = sealAndEncrypt(header, conversation, chunk)
                    if (body == null) {
                        handedOff = false
                        continue
                    }
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

    /**
     * Everything the receiver acts upon goes inside the ciphertext; see [SealedHeader]. Returns
     * null when there is no usable session with the target yet.
     */
    private suspend fun sealAndEncrypt(
        header: PacketHeader,
        conversation: ConversationEntity?,
        payload: ByteArray,
        ackPacketId: String? = null,
        ackMsgId: String? = null
    ): ByteArray? {
        val target = header.to ?: return null
        val isGroup = conversation?.isGroup == true
        val sealed = SealedHeader(
            type = header.type,
            to = target,
            packetId = header.packetId,
            msgId = header.msgId,
            createdAt = header.createdAt,
            groupId = if (isGroup) conversation?.id else null,
            groupName = if (isGroup) conversation?.title else null,
            groupMembers = if (isGroup && conversation != null) {
                membersOf(conversation).take(SealedPayload.MAX_MEMBERS)
            } else {
                emptyList()
            },
            meta = header.meta,
            ackPacketId = ackPacketId,
            ackMsgId = ackMsgId
        )
        return try {
            crypto.encrypt(target, SealedPayload.seal(sealed, payload))
        } catch (e: NoSessionYetException) {
            null
        } catch (e: IdentityChangedException) {
            null
        } catch (e: CryptoException) {
            null
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Packet not sealed: ${e.message}")
            null
        }
    }

    /**
     * Sends a packet of our own, directly if the recipient is in range and through the neighbours
     * otherwise. Returns true when it went out on a direct link.
     *
     * A data packet stays queued after it has been sent: only the recipient's acknowledgement
     * ([onAck]) removes it. A write to a radio link that then drops, or to an endpoint that only
     * claimed to be the recipient, is not a delivery.
     */
    private suspend fun handOff(header: PacketHeader, body: ByteArray): Boolean {
        val now = System.currentTimeMillis()
        if (header.expiresAt <= now) return false
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
            lastTransmit.mark(header.packetId, now)
            // Acknowledgements are not acknowledged themselves; a lost one is replaced when the
            // sender repeats the packet.
            if (header.type == PacketType.ACK) relays.delete(header.packetId)
            return true
        }
        if (mesh.broadcast(header, body) > 0) lastTransmit.mark(header.packetId, now)
        return false
    }

    // ------------------------------------------------------------------ receiving

    override suspend fun onPacket(header: PacketHeader, body: ByteArray) {
        val me = mesh.deviceId()
        val plaintext = try {
            crypto.decrypt(header.from, body)
        } catch (e: DuplicateCiphertextException) {
            // Decrypted once already, so the sender is repeating itself because our receipt did
            // not arrive. The packet id is the only thing we can confirm here: the sealed header
            // cannot be read a second time.
            sendAck(header.from, header.packetId, completedMsgId = ackMemo.get(header.packetId)?.takeIf { it.isNotEmpty() })
            return
        } catch (e: Exception) {
            Log.w(TAG, "Could not decrypt ${header.type} from ${header.from}: ${e.message}")
            return
        }
        val opened = try {
            SealedPayload.open(plaintext)
        } catch (e: Exception) {
            Log.w(TAG, "Dropping ${header.type} from ${header.from}: ${e.message}")
            return
        }
        val sealed = opened.header
        val mismatch = MeshInboundPolicy.bindingError(header, sealed, me)
        if (mismatch != null) {
            Log.w(TAG, "Dropping ${header.type} from ${header.from}: sealed $mismatch does not match")
            return
        }

        // From here on only values that were inside the ciphertext are used. `from` is trusted
        // because the session that decrypted the packet belongs to that device.
        val now = System.currentTimeMillis()
        val trusted = header.copy(
            to = me,
            groupId = sealed.groupId,
            groupName = sealed.groupName,
            msgId = sealed.msgId,
            createdAt = MeshInboundPolicy.acceptedTimestamp(sealed.createdAt, now),
            meta = sealed.meta,
            ackFor = null
        )
        val conversationId = conversationFor(trusted, sealed.groupMembers)
        if (conversationId == null) {
            // Refused, but the packet itself is confirmed so that the sender stops repeating it.
            ackMemo.put(header.packetId, "")
            sendAck(header.from, header.packetId, completedMsgId = null)
            return
        }

        val complete = when (header.type) {
            PacketType.TEXT -> {
                onText(trusted, conversationId, opened.payload)
                true
            }
            PacketType.FILE_CHUNK, PacketType.VOICE -> onChunk(trusted, conversationId, opened.payload)
            else -> false
        }
        ackMemo.put(header.packetId, if (complete) sealed.msgId else "")
        sendAck(header.from, header.packetId, completedMsgId = if (complete) sealed.msgId else null)
    }

    /**
     * A packet for us that the mesh layer had already seen. Usually the first copy was processed
     * and only the receipt was lost; then the ratchet rejects the repeat and [onPacket] answers
     * with a fresh receipt. If the first copy never got as far as being decrypted (the process
     * died in between), it is simply processed now.
     */
    override suspend fun onDuplicate(header: PacketHeader, body: ByteArray) {
        val now = System.currentTimeMillis()
        if (!reAckLimiter.due(header.packetId, now, REACK_INTERVAL_MS)) return
        reAckLimiter.mark(header.packetId, now)
        onPacket(header, body)
    }

    private suspend fun onText(header: PacketHeader, conversationId: String, plaintext: ByteArray) {
        val msgId = header.msgId

        // Scenario 4: cross-transport deduplication if already received via ONLINE!
        val existing = messages.getById(msgId) ?: messages.getByClientMsgId(msgId)
        if (existing != null) {
            // Message ids are chosen by the sender. Reusing the id of a message in another chat,
            // or of one of our own, changes nothing there.
            if (existing.outgoing || existing.conversationId != conversationId) {
                Log.w(TAG, "Ignoring text from ${header.from}: id is already used by another message")
                return
            }
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

    /** Stores one chunk. Returns true when the whole attachment has arrived. */
    private suspend fun onChunk(header: PacketHeader, conversationId: String, plaintext: ByteArray): Boolean {
        val parsed = try {
            json.decodeFromString(FileChunkMeta.serializer(), header.meta)
        } catch (e: Exception) {
            Log.w(TAG, "Chunk without metadata", e)
            return false
        }
        val problem = MeshInboundPolicy.chunkError(
            parsed.fileId, parsed.index, parsed.total, parsed.size, plaintext.size
        )
        // A transfer belongs to the message it was announced for; anything else would let one
        // contact write into another contact's half-finished transfer.
        if (problem != null || parsed.fileId != header.msgId) {
            Log.w(TAG, "Dropping chunk from ${header.from}: bad ${problem ?: "transfer id"}")
            return false
        }
        val meta = parsed.copy(name = MeshInboundPolicy.safeFileName(parsed.name))

        val transfer = transfers.getById(meta.fileId)
        if (transfer != null) {
            if (transfer.conversationId != conversationId || transfer.totalChunks != meta.total) {
                Log.w(TAG, "Dropping chunk from ${header.from}: does not belong to this transfer")
                return false
            }
            // Already assembled: a repeated chunk only needs its receipt.
            if (transfer.status == "COMPLETE") return true
        } else {
            val known = messages.getById(meta.fileId) ?: messages.getByClientMsgId(meta.fileId)
            if (known != null && (known.outgoing || known.conversationId != conversationId)) {
                Log.w(TAG, "Dropping chunk from ${header.from}: id is already used by another message")
                return false
            }
        }

        val staging = files.stagingFileFor(meta.fileId)
        files.writeChunk(meta.fileId, meta.index, plaintext)

        // Counted from what is on disk, not from a running total: the staging directory lives in
        // the cache and may be emptied while a transfer is under way.
        val received = (staging.listFiles { f -> f.name.startsWith("chunk_") }?.size ?: 0)
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
            return completeTransfer(meta, header, conversationId)
        }
        return false
    }

    private suspend fun completeTransfer(
        meta: FileChunkMeta,
        header: PacketHeader,
        conversationId: String
    ): Boolean {
        val stored = files.completeIncoming(meta.fileId, meta.name, meta.total, meta.voice) ?: return false
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
        return true
    }

    /**
     * The conversation an authenticated packet belongs to, or null when it must not be stored.
     *
     * [header] and [sealedMembers] come from inside the ciphertext. A group id is still only a
     * claim by the sender: it may name a conversation the sender is not part of - notably a direct
     * chat, whose id is the other party's device id - so the stored conversation decides.
     */
    private suspend fun conversationFor(header: PacketHeader, sealedMembers: List<String>): String? {
        val groupId = header.groupId
        if (groupId != null) {
            val me = mesh.deviceId()
            val existing = conversations.getById(groupId)
            val route = MeshInboundPolicy.groupRoute(
                exists = existing != null,
                isMeshGroup = existing != null && existing.isGroup && existing.onlineId == null,
                knownMembers = existing?.let { membersOf(it) }.orEmpty(),
                sender = header.from,
                sealedMembers = sealedMembers,
                myDeviceId = me
            )
            val members = sealedMembers.distinct().joinToString(",")
            return when (route) {
                MeshInboundPolicy.GroupRoute.CREATE -> {
                    conversations.upsert(
                        ConversationEntity(
                            id = groupId,
                            title = header.groupName?.takeIf { it.isNotBlank() }?.take(120) ?: "Group",
                            isGroup = true,
                            memberIds = members,
                            createdAt = header.createdAt,
                            lastMessageAt = header.createdAt,
                            type = ConversationType.GROUP
                        )
                    )
                    groupId
                }
                MeshInboundPolicy.GroupRoute.ACCEPT -> {
                    // Groups received under protocol 1 were stored without members, which is why
                    // nobody but the creator could write in them.
                    if (existing != null && membersOf(existing).isEmpty()) {
                        conversations.upsert(existing.copy(memberIds = members))
                    }
                    groupId
                }
                else -> {
                    Log.w(TAG, "Refusing group packet from ${header.from}: $route")
                    null
                }
            }
        }
        val peer = header.from
        return ensureDirectConversation(peer, contacts.displayName(peer))
    }

    /**
     * Confirms one packet to its sender and, when [completedMsgId] is set, the whole message.
     * The receipt is encrypted for the sender like any other packet, so nobody else can issue it.
     */
    private suspend fun sendAck(to: String, ackedPacketId: String, completedMsgId: String?) {
        val me = mesh.deviceId()
        if (to.isBlank() || to == me) return
        val now = System.currentTimeMillis()
        val ack = PacketHeader(
            packetId = UUID.randomUUID().toString(),
            type = PacketType.ACK,
            from = me,
            to = to,
            msgId = "",
            createdAt = now,
            expiresAt = RoutingPolicy.expiryFrom(now),
            ttl = RoutingPolicy.DEFAULT_TTL
        )
        val body = sealAndEncrypt(
            ack, conversation = null, payload = ByteArray(0),
            ackPacketId = ackedPacketId, ackMsgId = completedMsgId
        ) ?: return
        handOff(ack, body)
    }

    /**
     * A receipt. Protocol 1 accepted the acknowledged id from the plain header, so anybody in
     * radio range could mark any message delivered and make the sender discard the ciphertext it
     * was still holding for the real recipient. Now the receipt has to decrypt under the session
     * with [PacketHeader.from], name a packet that was queued for exactly that device, and - to
     * settle a message - come from a participant of that message's conversation.
     */
    override suspend fun onAck(header: PacketHeader, body: ByteArray) {
        val sender = header.from
        val sealed = try {
            SealedPayload.open(crypto.decrypt(sender, body)).header
        } catch (e: Exception) {
            // Forged, replayed or damaged.
            return
        }
        if (MeshInboundPolicy.bindingError(header, sealed, mesh.deviceId()) != null) return
        val ackedPacket = sealed.ackPacketId ?: return

        var deliveredMsgId = sealed.ackMsgId
        val entry = relays.getById(ackedPacket)
        if (entry != null) {
            if (entry.targetDeviceId != sender) return
            relays.delete(ackedPacket)
            if (deliveredMsgId == null && entry.msgId.isNotBlank()) {
                // A text is a single packet, so its receipt settles the message even when the
                // receiver could not say so (a repeated packet cannot be decrypted twice).
                val type = runCatching {
                    json.decodeFromString(PacketHeader.serializer(), entry.headerJson).type
                }.getOrNull()
                if (type == PacketType.TEXT) deliveredMsgId = entry.msgId
            }
        }
        val msgId = deliveredMsgId?.takeIf { it.isNotBlank() } ?: return

        val local = messages.getById(msgId) ?: messages.getByClientMsgId(msgId) ?: return
        val conversation = conversations.getById(local.conversationId) ?: return
        if (!MeshInboundPolicy.ackAllowed(local.outgoing, membersOf(conversation), sender)) return

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
        // Only this recipient's copies: other group members may still be waiting for theirs.
        relays.deleteForMessageAndTarget(msgId, sender)
    }

    override suspend fun onRelay(header: PacketHeader, body: ByteArray) {
        val now = System.currentTimeMillis()
        if (!RoutingPolicy.shouldForward(header, mesh.deviceId(), now)) return
        // Other people's packets may fill the queue only up to a point; the rest is kept for our own.
        if (relays.count() >= MAX_FOREIGN_RELAY_ENTRIES) return
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

    /**
     * Puts one queued packet on the air. With [via] set it goes to that neighbour only - as the
     * final delivery when the neighbour is the recipient, as a relay hop otherwise; without it,
     * to every neighbour. Returns true when something was sent.
     */
    private suspend fun transmit(entry: RelayEntity, via: String?, now: Long): Boolean {
        if (entry.expiresAt <= now) {
            relays.delete(entry.packetId)
            return false
        }
        val header = try {
            json.decodeFromString(PacketHeader.serializer(), entry.headerJson)
        } catch (e: Exception) {
            relays.delete(entry.packetId)
            return false
        }
        val body = try {
            Base64.decode(entry.bodyBase64, Base64.NO_WRAP)
        } catch (e: IllegalArgumentException) {
            relays.delete(entry.packetId)
            return false
        }
        val mine = header.from == mesh.deviceId()
        val toRecipient = via != null && header.to == via
        // Our own packet starts its journey with the full hop budget; a packet we carry for
        // somebody else has already used one hop getting here.
        val onAir = if (mine || toRecipient) header else RoutingPolicy.forwarded(header)
        val sent = when {
            // Relay hygiene: direct delivery goes strictly to the target device.
            toRecipient -> mesh.sendTo(via!!, header, body)
            !mine && header.ttl <= 1 -> false
            via != null -> mesh.sendTo(via, onAir, body)
            else -> mesh.broadcast(onAir, body) > 0
        }
        if (!sent) return false
        lastTransmit.mark(entry.packetId, now)
        if (toRecipient && (!mine || header.type == PacketType.ACK)) {
            // A packet we only carried is done once the target has it; whether it arrived is
            // between the two ends. Our own data packets wait for the receipt instead.
            relays.delete(entry.packetId)
        } else {
            relays.bumpAttempt(entry.packetId)
        }
        return true
    }

    /**
     * A device just came into range. Everything addressed to it goes out now; after that it is
     * offered a share of what is waiting for devices that are not in range, since it may meet
     * them before we do.
     */
    private suspend fun flushQueue(deviceId: String) {
        val now = System.currentTimeMillis()
        relays.purgeExpired(now)
        for (entry in relays.forDevice(deviceId, MAX_FLUSH_PER_RUN)) {
            transmit(entry, via = deviceId, now = now)
        }
        val connected = mesh.connectedDeviceIds()
        var budget = MAX_RELAY_OFFER_PER_PEER
        for (ref in relays.refs()) {
            if (budget <= 0) break
            val target = ref.targetDeviceId ?: continue
            if (target == deviceId || target in connected) continue
            val entry = relays.getById(ref.packetId) ?: continue
            if (transmit(entry, via = deviceId, now = now)) budget--
        }
    }

    suspend fun retryPending() {
        resendQueue()

        for (message in messages.pendingOutgoing()) {
            if (message.status != MessageStatus.QUEUED) continue
            val conversation = conversations.getById(message.conversationId) ?: continue
            deliverMessage(message, conversation, replaceQueued = true)
        }
        runCatching { online.drainOutbox() }
    }

    override suspend fun onTick() = resendQueue()

    /**
     * Periodic pass over the queue. Packets are taken least-tried first and only those that can
     * actually move are loaded, so a backlog for a device that is away no longer keeps everything
     * behind it from being sent (the old pass looked at the 200 oldest rows and nothing else).
     */
    private suspend fun resendQueue() {
        val now = System.currentTimeMillis()
        relays.purgeExpired(now)
        val connected = mesh.connectedDeviceIds()
        if (connected.isNotEmpty()) {
            var budget = MAX_FLUSH_PER_RUN
            for (ref in relays.refs()) {
                if (budget <= 0) break
                val target = ref.targetDeviceId
                val direct = target != null && target in connected
                val interval = if (direct) DIRECT_RESEND_MS else RELAY_RESEND_MS
                if (!lastTransmit.due(ref.packetId, now, interval)) continue
                val entry = relays.getById(ref.packetId) ?: continue
                if (transmit(entry, via = if (direct) target else null, now = now)) budget--
            }
        }
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

    /**
     * Returns false when the reaction could not be changed. Reactions exist only on the server, so
     * a message that travelled over the mesh alone cannot carry one; that is reported instead of
     * the tap doing nothing.
     */
    suspend fun toggleReaction(messageId: String, emoji: String): Boolean {
        val message = messages.getById(messageId) ?: return false
        if (message.remoteId == null || !online.isSignedIn) return false
        return online.toggleReaction(message, emoji)
    }

    suspend fun setStarred(messageId: String, starred: Boolean) =
        messages.setStarred(messageId, starred)

    // ------------------------------------------------------------------ archive & saved

    fun observeArchivedConversations(): Flow<List<ConversationEntity>> = conversations.observeArchived()

    fun observeArchivedCount(): Flow<Int> = conversations.observeArchivedCount()

    fun observeStarredMessages(): Flow<List<MessageEntity>> = messages.observeStarred()

    /**
     * Hides a chat from the main list without deleting anything.
     *
     * Archive state is intentionally device-local: the server's `mm_conversations.archived_at`
     * column is owner-scoped, and a plain member cannot write it (the RLS update policy requires
     * an admin role), so mirroring the flag would fail silently for most users. Keeping it in
     * SQLCipher gives every device a predictable, private layout instead of a fake server sync.
     */
    suspend fun setConversationArchived(conversationId: String, archived: Boolean): Boolean {
        if (conversations.getById(conversationId) == null) return false
        conversations.setArchived(conversationId, archived)
        return true
    }

    /**
     * "Delete for me": drops the message from this device's database and cancels any pending
     * upload for it. Nothing is sent to the server, so the peer keeps its copy — the counterpart
     * of `deleteForEveryone()`.
     */
    suspend fun deleteForMe(messageId: String): Boolean {
        val message = messages.getById(messageId) ?: return false
        outbox.deleteForMessage(messageId)
        messages.deleteLocal(messageId)
        if (message.conversationId.isNotBlank()) {
            val latest = messages.latestForConversation(message.conversationId)
            conversations.updateLastMessage(
                message.conversationId,
                latest?.timestamp ?: 0L,
                latest?.let { previewOf(it.body.ifBlank { it.fileName.orEmpty() }) } ?: "",
                0
            )
        }
        return true
    }

    /**
     * Forwards a message into another conversation as a new message owned by this device: text
     * bodies are re-sent as text, locally held media is re-sent from the original file (so the
     * forward works offline over the mesh too) and messages whose payload is not available on this
     * device report `false` instead of silently doing nothing.
     */
    suspend fun forwardMessage(messageId: String, targetConversationId: String): Boolean {
        val message = messages.getById(messageId) ?: return false
        if (message.deletedAt != null) return false
        val target = conversations.getById(targetConversationId) ?: return false
        if (target.id == message.conversationId) return false

        return when {
            message.type == "TEXT" -> {
                if (message.body.isBlank()) return false
                sendText(targetConversationId, message.body)
                true
            }
            message.filePath != null && File(message.filePath).exists() -> {
                val file = File(message.filePath)
                if (message.type == "VOICE") {
                    sendVoice(targetConversationId, file, message.durationMs ?: 0L)
                } else {
                    sendFile(targetConversationId, file, message.fileName ?: file.name)
                }
                true
            }
            else -> false
        }
    }

    suspend fun editMessage(messageId: String, newBody: String): Boolean {
        val message = messages.getById(messageId) ?: return false
        if (!message.outgoing || message.deletedAt != null) return false
        val text = newBody.trim()
        if (text.isEmpty()) return false
        if (text == message.body) return true
        val now = System.currentTimeMillis()
        val conversation = conversations.getById(message.conversationId)
        // Other people hold a copy of a message that went through the server. Their copy is
        // changed first; if that fails the text here stays as it is, rather than this device
        // showing an edit nobody else received.
        if (message.remoteId != null) {
            if (conversation?.onlineId == null || !online.isSignedIn) return false
            val published = runCatching {
                online.editMessage(message.copy(body = text, editedAt = now), conversation)
            }.getOrDefault(false)
            if (!published) return false
        }
        messages.editBody(messageId, text, now)
        return true
    }

    suspend fun deleteForEveryone(messageId: String): Boolean {
        val message = messages.getById(messageId) ?: return false
        if (!message.outgoing) return false
        val conversation = conversations.getById(message.conversationId)
        // Server first, for the same reason as in editMessage(): a message that only disappears
        // here while every other participant keeps it was not deleted "for everyone".
        if (message.remoteId != null) {
            if (conversation?.onlineId == null || !online.isSignedIn) return false
            val deleted = runCatching { online.deleteForEveryone(message, conversation) }.getOrDefault(false)
            if (!deleted) return false
        }
        outbox.deleteForMessage(messageId)
        relays.deleteForMessage(message.clientMsgId.ifBlank { message.id })
        messages.markDeleted(messageId, System.currentTimeMillis())
        message.filePath?.let { files.delete(File(it)) }
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
        /**
         * Packets the store-and-forward queue holds. A 50 MiB attachment is 1,600 chunks, so the
         * old limit of 600 meant anything above ~19 MB could not be sent to somebody out of range.
         */
        const val MAX_RELAY_ENTRIES = 2000

        /** Share of the queue other people's packets may use; the rest is reserved for our own. */
        const val MAX_FOREIGN_RELAY_ENTRIES = 400
        const val MAX_FLUSH_PER_RUN = 200

        /** An unacknowledged packet is repeated to a recipient in range no more often than this. */
        const val DIRECT_RESEND_MS = 20_000L

        /**
         * A packet for a device out of range is offered again to the current neighbours this
         * often. They drop what they have already seen, so this only has to cover a lost frame.
         */
        const val RELAY_RESEND_MS = 600_000L

        /** How much of the queue for absent devices a newly connected neighbour is handed. */
        const val MAX_RELAY_OFFER_PER_PEER = 200

        /** Copies of one packet arriving over several paths are answered once in this window. */
        const val REACK_INTERVAL_MS = 15_000L
        const val ERROR_MESH_QUEUE_FULL = "mesh_queue_full"
        const val TYPING_THROTTLE_MS = 5_000L
    }
}
