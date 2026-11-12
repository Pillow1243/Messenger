package com.offlinemesh.messenger.core.mesh

import java.io.ByteArrayOutputStream
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer

/**
 * The part of a packet that travels *inside* the Signal ciphertext.
 *
 * The outer [PacketHeader] is plain text: every relay reads it and any relay can rewrite it. In
 * protocol version 1 the receiver nevertheless took the conversation (`groupId`), the message id,
 * the timestamp, the attachment metadata and the acknowledgement target from that header, so a
 * relay - or any paired contact - could move a message into another chat, back-date it, corrupt a
 * file transfer or mark somebody else's message as delivered.
 *
 * From version 2 on, everything the receiver acts upon is repeated here, under the same
 * authenticated encryption as the content. The outer header is only used to route.
 */
data class SealedHeader(
    val type: PacketType,
    /** Device the sender encrypted this for. */
    val to: String,
    /** Outer packet id, so the acknowledgement names the exact transmission. */
    val packetId: String,
    val msgId: String = "",
    val createdAt: Long,
    val groupId: String? = null,
    val groupName: String? = null,
    /** Device ids of the group as the sender knows it (including sender and recipient). */
    val groupMembers: List<String> = emptyList(),
    /** Attachment metadata (JSON), empty for text. */
    val meta: String = "",
    /** ACK only: the packet being acknowledged. */
    val ackPacketId: String? = null,
    /** ACK only: set when the whole message (text, or every chunk of a file) has arrived. */
    val ackMsgId: String? = null
)

class SealedMessage(val header: SealedHeader, val payload: ByteArray)

/**
 * Binary layout: `"MSP" | version(1) | flags(1) | type | to | packetId | msgId | createdAt(8) |
 * [groupId] | [groupName] | memberCount(1) | members… | meta | [ackPacketId] | [ackMsgId] | payload`.
 * Strings are `length(2) | UTF-8`. Hand-written rather than JSON so that attachment chunks are
 * not inflated and so that a malformed value fails in one obvious place.
 */
object SealedPayload {

    const val VERSION = 1
    const val MAX_ID_BYTES = 128
    const val MAX_NAME_BYTES = 256
    const val MAX_META_BYTES = 2048
    const val MAX_MEMBERS = 64

    private val MAGIC = byteArrayOf('M'.code.toByte(), 'S'.code.toByte(), 'P'.code.toByte())
    private const val FLAG_GROUP_ID = 1
    private const val FLAG_GROUP_NAME = 2
    private const val FLAG_ACK_PACKET = 4
    private const val FLAG_ACK_MSG = 8

    fun seal(header: SealedHeader, payload: ByteArray): ByteArray {
        require(header.groupMembers.size <= MAX_MEMBERS) { "too many group members" }
        val out = ByteArrayOutputStream(payload.size + 256)
        out.write(MAGIC)
        out.write(VERSION)
        var flags = 0
        if (header.groupId != null) flags = flags or FLAG_GROUP_ID
        if (header.groupName != null) flags = flags or FLAG_GROUP_NAME
        if (header.ackPacketId != null) flags = flags or FLAG_ACK_PACKET
        if (header.ackMsgId != null) flags = flags or FLAG_ACK_MSG
        out.write(flags)
        out.writeString(header.type.name, MAX_ID_BYTES)
        out.writeString(header.to, MAX_ID_BYTES)
        out.writeString(header.packetId, MAX_ID_BYTES)
        out.writeString(header.msgId, MAX_ID_BYTES)
        out.write(ByteBuffer.allocate(8).putLong(header.createdAt).array())
        header.groupId?.let { out.writeString(it, MAX_ID_BYTES) }
        header.groupName?.let { out.writeString(it.truncateUtf8(MAX_NAME_BYTES), MAX_NAME_BYTES) }
        out.write(header.groupMembers.size)
        for (member in header.groupMembers) out.writeString(member, MAX_ID_BYTES)
        out.writeString(header.meta, MAX_META_BYTES)
        header.ackPacketId?.let { out.writeString(it, MAX_ID_BYTES) }
        header.ackMsgId?.let { out.writeString(it, MAX_ID_BYTES) }
        out.write(payload)
        return out.toByteArray()
    }

    /** @throws PacketFormatException when [bytes] is not a well-formed sealed payload. */
    fun open(bytes: ByteArray): SealedMessage {
        if (bytes.size < MAGIC.size + 2) throw PacketFormatException("short sealed payload")
        val buffer = ByteBuffer.wrap(bytes)
        try {
            val magic = ByteArray(MAGIC.size)
            buffer.get(magic)
            if (!magic.contentEquals(MAGIC)) throw PacketFormatException("not a sealed payload")
            val version = buffer.get().toInt() and 0xFF
            if (version != VERSION) throw PacketFormatException("unsupported sealed version $version")
            val flags = buffer.get().toInt() and 0xFF
            val typeName = buffer.readString(MAX_ID_BYTES)
            val type = PacketType.entries.firstOrNull { it.name == typeName }
                ?: throw PacketFormatException("unknown packet type")
            val to = buffer.readString(MAX_ID_BYTES)
            val packetId = buffer.readString(MAX_ID_BYTES)
            val msgId = buffer.readString(MAX_ID_BYTES)
            val createdAt = buffer.getLong()
            val groupId = if (flags and FLAG_GROUP_ID != 0) buffer.readString(MAX_ID_BYTES) else null
            val groupName = if (flags and FLAG_GROUP_NAME != 0) buffer.readString(MAX_NAME_BYTES) else null
            val memberCount = buffer.get().toInt() and 0xFF
            if (memberCount > MAX_MEMBERS) throw PacketFormatException("too many group members")
            val members = ArrayList<String>(memberCount)
            repeat(memberCount) { members.add(buffer.readString(MAX_ID_BYTES)) }
            val meta = buffer.readString(MAX_META_BYTES)
            val ackPacketId = if (flags and FLAG_ACK_PACKET != 0) buffer.readString(MAX_ID_BYTES) else null
            val ackMsgId = if (flags and FLAG_ACK_MSG != 0) buffer.readString(MAX_ID_BYTES) else null
            val payload = ByteArray(buffer.remaining())
            buffer.get(payload)
            return SealedMessage(
                SealedHeader(
                    type = type,
                    to = to,
                    packetId = packetId,
                    msgId = msgId,
                    createdAt = createdAt,
                    groupId = groupId,
                    groupName = groupName,
                    groupMembers = members,
                    meta = meta,
                    ackPacketId = ackPacketId,
                    ackMsgId = ackMsgId
                ),
                payload
            )
        } catch (e: BufferUnderflowException) {
            throw PacketFormatException("truncated sealed payload")
        }
    }

    private fun ByteArrayOutputStream.writeString(value: String, maxBytes: Int) {
        val encoded = value.toByteArray(Charsets.UTF_8)
        require(encoded.size <= maxBytes) { "sealed field too long (${encoded.size} > $maxBytes)" }
        write((encoded.size shr 8) and 0xFF)
        write(encoded.size and 0xFF)
        write(encoded)
    }

    private fun ByteBuffer.readString(maxBytes: Int): String {
        val length = ((get().toInt() and 0xFF) shl 8) or (get().toInt() and 0xFF)
        if (length > maxBytes) throw PacketFormatException("sealed field too long")
        if (remaining() < length) throw PacketFormatException("truncated sealed field")
        val raw = ByteArray(length)
        get(raw)
        return String(raw, Charsets.UTF_8)
    }

    /** Cuts on a character boundary so the result still fits [maxBytes] once encoded. */
    private fun String.truncateUtf8(maxBytes: Int): String {
        if (toByteArray(Charsets.UTF_8).size <= maxBytes) return this
        var end = minOf(length, maxBytes)
        while (end > 0 && substring(0, end).toByteArray(Charsets.UTF_8).size > maxBytes) end--
        if (end > 0 && Character.isHighSurrogate(this[end - 1])) end--
        return substring(0, end)
    }
}

/**
 * Decisions about packets that arrive from the mesh. Pure functions, so the rules are the same
 * in the app and in the unit tests.
 */
object MeshInboundPolicy {

    /** A sender's clock may be this far ahead of ours before its timestamp is replaced. */
    const val MAX_CLOCK_SKEW_MS = 5 * 60_000L
    const val MAX_FILE_NAME_CHARS = 120

    val MAX_CHUNKS: Int = FileChunker.chunkCount(FileChunker.MAX_FILE_SIZE)

    private val ID = Regex("^[A-Za-z0-9][A-Za-z0-9_:-]{0,95}$")
    private val FILE_ID = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,95}$")

    /** Conversation, message and device ids: UUIDs, optionally prefixed (`group:<uuid>`). */
    fun isSafeId(id: String?): Boolean = id != null && ID.matches(id)

    /** Ids that become file or directory names: no separator, dot or colon can appear. */
    fun isSafeFileId(id: String?): Boolean = id != null && FILE_ID.matches(id)

    /**
     * The timestamp to store for an incoming message. A value from the future would pin the
     * message to the bottom of the chat for good, and one from the distant past would bury it;
     * neither is accepted from a peer.
     */
    fun acceptedTimestamp(claimed: Long, now: Long): Long {
        val oldest = now - RoutingPolicy.STORE_FORWARD_MS - MAX_CLOCK_SKEW_MS
        return if (claimed in oldest..(now + MAX_CLOCK_SKEW_MS)) claimed else now
    }

    /**
     * Compares the routing header with what the sender sealed. Returns null when they agree, or
     * a short reason when the packet must be dropped.
     */
    fun bindingError(outer: PacketHeader, sealed: SealedHeader, myDeviceId: String): String? = when {
        sealed.type != outer.type -> "type"
        sealed.to != myDeviceId -> "recipient"
        sealed.packetId != outer.packetId -> "packet id"
        sealed.type == PacketType.ACK ->
            if (isSafeId(sealed.ackPacketId) && (sealed.ackMsgId == null || isSafeId(sealed.ackMsgId))) null
            else "ack target"
        !isSafeId(sealed.msgId) -> "message id"
        sealed.groupId != null && !isSafeId(sealed.groupId) -> "group id"
        sealed.groupMembers.any { !isSafeId(it) } -> "group member"
        else -> null
    }

    /** Returns null when the chunk may be written to disk, or the reason it must be dropped. */
    fun chunkError(fileId: String, index: Int, total: Int, declaredSize: Long, payloadSize: Int): String? = when {
        !isSafeFileId(fileId) -> "file id"
        total < 1 || total > MAX_CHUNKS -> "chunk count"
        index < 0 || index >= total -> "chunk index"
        payloadSize > FileChunker.CHUNK_SIZE -> "chunk size"
        // Every chunk but the last is full; an empty file is a single empty chunk.
        index < total - 1 && payloadSize != FileChunker.CHUNK_SIZE -> "chunk size"
        declaredSize < 0 || declaredSize > FileChunker.MAX_FILE_SIZE -> "file size"
        declaredSize > 0 && FileChunker.chunkCount(declaredSize) != total -> "file size"
        else -> null
    }

    /** A display name that is safe to show and to use as the last segment of a path. */
    fun safeFileName(name: String): String {
        val leaf = name.substringAfterLast('/').substringAfterLast('\\')
        val cleaned = buildString {
            for (ch in leaf) {
                if (ch.isISOControl() || ch == ':' || ch == '*' || ch == '?' || ch == '"' ||
                    ch == '<' || ch == '>' || ch == '|'
                ) continue
                append(ch)
            }
        }.trim().trimStart('.')
        return cleaned.take(MAX_FILE_NAME_CHARS).ifBlank { "file" }
    }

    enum class GroupRoute {
        /** Unknown group: create it with the sealed member list. */
        CREATE,
        ACCEPT,
        /** The id belongs to a conversation that is not a mesh group (for example a direct chat). */
        REJECT_NOT_A_GROUP,
        REJECT_NOT_A_MEMBER
    }

    /**
     * Where a packet that names a group may go.
     *
     * @param exists a conversation with this id is stored
     * @param isMeshGroup that conversation is a group that is not backed by an online conversation
     * @param knownMembers its stored member device ids (empty for groups created by version 1)
     */
    fun groupRoute(
        exists: Boolean,
        isMeshGroup: Boolean,
        knownMembers: List<String>,
        sender: String,
        sealedMembers: List<String>,
        myDeviceId: String
    ): GroupRoute = when {
        // The sender has to list both itself and us; otherwise the "group" is a way to push
        // messages into a conversation we were never part of.
        sender !in sealedMembers || myDeviceId !in sealedMembers -> GroupRoute.REJECT_NOT_A_MEMBER
        !exists -> GroupRoute.CREATE
        !isMeshGroup -> GroupRoute.REJECT_NOT_A_GROUP
        knownMembers.isNotEmpty() && sender !in knownMembers -> GroupRoute.REJECT_NOT_A_MEMBER
        else -> GroupRoute.ACCEPT
    }

    /** May an acknowledgement from [sender] settle a message of this conversation? */
    fun ackAllowed(outgoing: Boolean, conversationMembers: List<String>, sender: String): Boolean =
        outgoing && sender in conversationMembers
}
