package com.offlinemesh.messenger.core.mesh

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.nio.BufferUnderflowException
import java.nio.ByteBuffer

const val MESH_PROTOCOL_VERSION = 1

@Serializable
enum class PacketType {
    /** Handshake: display name, identity key and pre-key bundle. Routed directly, ttl 1. */
    HELLO,

    /** Delivery receipt for [PacketHeader.ackFor] (a msgId). */
    ACK,

    /** Encrypted text message. */
    TEXT,

    /** Encrypted voice note. */
    VOICE,

    /** Encrypted binary attachment chunk. */
    FILE_CHUNK,

    /** Marks the end of an attachment. */
    FILE_COMPLETE,

    /** Key-value catalog update (contact profile changes). */
    PROFILE
}

/**
 * Routing envelope. The [body] is *always* Signal ciphertext (or empty for control packets);
 * the header only contains data needed to route the packet through the mesh.
 */
@Serializable
data class PacketHeader(
    val v: Int = MESH_PROTOCOL_VERSION,
    /** Unique per transmission attempt; used for duplicate suppression. */
    val packetId: String,
    val type: PacketType,
    /** Originating device id. */
    val from: String,
    /** Final recipient device id, or null for group/broadcast packets. */
    val to: String? = null,
    /** Set for group conversations. */
    val groupId: String? = null,
    /** Human readable group title, so receivers can create the conversation. */
    val groupName: String? = null,
    /** Stable id of the message inside the conversation across retries. */
    val msgId: String = "",
    val createdAt: Long,
    /** Hard expiry; relays drop the packet after this instant. */
    val expiresAt: Long,
    val ttl: Int = RoutingPolicy.DEFAULT_TTL,
    val hops: Int = 0,
    /** msgId this packet acknowledges. */
    val ackFor: String? = null,
    /** Free-form small metadata (attachment name/size/chunk index). */
    val meta: String = ""
)

class PacketFormatException(message: String) : Exception(message)

/**
 * Binary framing: `"MBP" | version(1) | headerLen(2) | headerJson | body`.
 *
 * Binary framing (instead of base64 inside JSON) keeps attachment chunks at their natural size
 * and keeps the ceiling for a Nearby payload comfortably under the 100 KB limit.
 */
object PacketCodec {

    private val MAGIC = byteArrayOf('M'.code.toByte(), 'B'.code.toByte(), 'P'.code.toByte())
    private const val VERSION = 1
    private const val MAX_HEADER = 8 * 1024

    /** Nearby Connections tops out at 100 KB per payload; stay well below it. */
    const val MAX_FRAME_SIZE = 90 * 1024

    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(header: PacketHeader, body: ByteArray): ByteArray {
        val headerBytes = json.encodeToString(PacketHeader.serializer(), header)
            .toByteArray(Charsets.UTF_8)
        require(headerBytes.size <= MAX_HEADER) { "packet header too large" }
        val total = MAGIC.size + 1 + 2 + headerBytes.size + body.size
        require(total <= MAX_FRAME_SIZE) {
            "packet of $total bytes exceeds the ${MAX_FRAME_SIZE} byte frame limit"
        }
        val out = ByteArrayOutputStream(total)
        out.write(MAGIC)
        out.write(VERSION)
        out.write((headerBytes.size ushr 8) and 0xFF)
        out.write(headerBytes.size and 0xFF)
        out.write(headerBytes)
        out.write(body)
        return out.toByteArray()
    }

    fun decode(frame: ByteArray): DecodedPacket {
        if (frame.size < MAGIC.size + 3) throw PacketFormatException("short frame")
        val buffer = ByteBuffer.wrap(frame)
        try {
            val magic = ByteArray(MAGIC.size)
            buffer.get(magic)
            if (!magic.contentEquals(MAGIC)) throw PacketFormatException("bad magic")
            val version = buffer.get().toInt() and 0xFF
            if (version != VERSION) throw PacketFormatException("unsupported version $version")
            val headerLength = ((buffer.get().toInt() and 0xFF) shl 8) or (buffer.get().toInt() and 0xFF)
            if (headerLength <= 0 || headerLength > MAX_HEADER) {
                throw PacketFormatException("bad header length $headerLength")
            }
            if (buffer.remaining() < headerLength) throw PacketFormatException("truncated header")
            val headerBytes = ByteArray(headerLength)
            buffer.get(headerBytes)
            val header = try {
                json.decodeFromString(PacketHeader.serializer(), String(headerBytes, Charsets.UTF_8))
            } catch (e: Exception) {
                throw PacketFormatException("malformed header: ${e.message}")
            }
            val body = ByteArray(buffer.remaining())
            buffer.get(body)
            return DecodedPacket(header, body)
        } catch (e: BufferUnderflowException) {
            throw PacketFormatException("truncated frame")
        }
    }
}

data class DecodedPacket(val header: PacketHeader, val body: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DecodedPacket) return false
        return header == other.header && body.contentEquals(other.body)
    }

    override fun hashCode(): Int = 31 * header.hashCode() + body.contentHashCode()
}

/** Pure, deterministic routing rules shared by the mesh service and the unit tests. */
object RoutingPolicy {

    const val DEFAULT_TTL = 7
    const val MAX_TTL = 12

    /** A packet is useless once its expiry has passed or its ttl ran out. */
    fun isUsable(header: PacketHeader, now: Long): Boolean =
        header.expiresAt > now && header.ttl > 0 && header.v == MESH_PROTOCOL_VERSION

    /** Never process our own packets that echo back to us. */
    fun isFromSelf(header: PacketHeader, myDeviceId: String): Boolean = header.from == myDeviceId

    /** True when this device is an intended final recipient. */
    fun isForMe(header: PacketHeader, myDeviceId: String): Boolean =
        header.to == myDeviceId || (header.to == null && header.groupId != null)

    /**
     * A relay forwards packets that are not addressed to it, and group packets when it is not
     * the author. Direct messages addressed to us are never forwarded (privacy + saves airtime).
     */
    fun shouldForward(header: PacketHeader, myDeviceId: String, now: Long): Boolean {
        if (!isUsable(header, now)) return false
        if (isFromSelf(header, myDeviceId)) return false
        if (header.ttl <= 1) return false
        if (header.to == myDeviceId) return false
        return true
    }

    fun forwarded(header: PacketHeader): PacketHeader =
        header.copy(ttl = (header.ttl - 1).coerceAtLeast(0), hops = header.hops + 1)

    fun expiryFrom(now: Long): Long = now + DEFAULT_TTL * 60_000L
}

/**
 * Bounded duplicate-suppression cache. Insertion-ordered, evicting the oldest entries,
 * so a flooded mesh does not grow without limit.
 */
class SeenPacketCache(
    private val maxEntries: Int = 4096,
    private val ttlMs: Long = 24 * 60 * 60 * 1000L
) {
    private val entries = object : LinkedHashMap<String, Long>(maxEntries, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?): Boolean =
            size > maxEntries
    }

    /** @return true the first time a packet id is seen, false for duplicates. */
    @Synchronized
    fun markSeen(packetId: String, now: Long): Boolean {
        val previous = entries[packetId]
        if (previous != null && now - previous < ttlMs) return false
        entries[packetId] = now
        return true
    }

    @Synchronized
    fun contains(packetId: String, now: Long): Boolean {
        val at = entries[packetId] ?: return false
        return now - at < ttlMs
    }

    @Synchronized
    fun size(): Int = entries.size

    @Synchronized
    fun clear() = entries.clear()
}

/** Exponential backoff with a hard ceiling, used for retransmission of queued messages. */
object RetryPolicy {
    const val BASE_DELAY_MS = 5_000L
    const val MAX_DELAY_MS = 10 * 60_000L
    const val MAX_ATTEMPTS = 40

    fun delayFor(attempt: Int): Long {
        if (attempt <= 0) return 0L
        val shift = (attempt - 1).coerceAtMost(16)
        val delay = BASE_DELAY_MS shl shift
        return delay.coerceAtMost(MAX_DELAY_MS)
    }

    fun shouldRetry(attempt: Int): Boolean = attempt < MAX_ATTEMPTS
}
