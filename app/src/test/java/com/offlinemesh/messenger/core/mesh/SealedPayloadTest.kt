package com.offlinemesh.messenger.core.mesh

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class SealedPayloadTest {

    private val me = "11111111-1111-4111-8111-111111111111"
    private val peer = "22222222-2222-4222-8222-222222222222"
    private val other = "33333333-3333-4333-8333-333333333333"

    private fun sealed(
        type: PacketType = PacketType.TEXT,
        to: String = me,
        packetId: String = "packet-1",
        msgId: String = "44444444-4444-4444-8444-444444444444",
        groupId: String? = null,
        members: List<String> = emptyList()
    ) = SealedHeader(
        type = type,
        to = to,
        packetId = packetId,
        msgId = msgId,
        createdAt = 1_700_000_000_000,
        groupId = groupId,
        groupName = groupId?.let { "Hikers" },
        groupMembers = members
    )

    private fun outer(type: PacketType = PacketType.TEXT, packetId: String = "packet-1") = PacketHeader(
        packetId = packetId,
        type = type,
        from = peer,
        to = me,
        createdAt = 1,
        expiresAt = 2
    )

    @Test
    fun `a sealed message survives the round trip`() {
        val header = sealed(groupId = "group:abc", members = listOf(me, peer, other))
            .copy(meta = """{"fileId":"f","index":3}""")
        val payload = byteArrayOf(0, 1, 2, -1, -128, 127)
        val opened = SealedPayload.open(SealedPayload.seal(header, payload))
        assertEquals(header, opened.header)
        assertArrayEquals(payload, opened.payload)
    }

    @Test
    fun `optional fields stay absent and an empty payload is allowed`() {
        val opened = SealedPayload.open(SealedPayload.seal(sealed(), ByteArray(0)))
        assertNull(opened.header.groupId)
        assertNull(opened.header.groupName)
        assertNull(opened.header.ackPacketId)
        assertNull(opened.header.ackMsgId)
        assertEquals(0, opened.payload.size)
    }

    @Test
    fun `an acknowledgement carries the packet and, when complete, the message`() {
        val ack = sealed(type = PacketType.ACK, msgId = "").copy(ackPacketId = "packet-9", ackMsgId = "msg-9")
        val opened = SealedPayload.open(SealedPayload.seal(ack, ByteArray(0))).header
        assertEquals("packet-9", opened.ackPacketId)
        assertEquals("msg-9", opened.ackMsgId)
    }

    @Test
    fun `non-ascii text keeps its bytes`() {
        val header = sealed(groupId = "group:abc", members = listOf(me, peer)).copy(groupName = "Поход 🏔️")
        val text = "Привет, 世界 👋".toByteArray(Charsets.UTF_8)
        val opened = SealedPayload.open(SealedPayload.seal(header, text))
        assertEquals("Поход 🏔️", opened.header.groupName)
        assertEquals("Привет, 世界 👋", String(opened.payload, Charsets.UTF_8))
    }

    @Test
    fun `an over-long group name is cut on a character boundary instead of failing`() {
        val header = sealed(groupId = "group:abc", members = listOf(me, peer)).copy(groupName = "🏔️".repeat(200))
        val name = SealedPayload.open(SealedPayload.seal(header, ByteArray(0))).header.groupName!!
        assertTrue(name.toByteArray(Charsets.UTF_8).size <= SealedPayload.MAX_NAME_BYTES)
        assertFalse(Character.isHighSurrogate(name.last()))
    }

    @Test
    fun `text that was never sealed is rejected`() {
        assertThrows(PacketFormatException::class.java) {
            SealedPayload.open("hello from protocol one".toByteArray())
        }
        assertThrows(PacketFormatException::class.java) { SealedPayload.open(ByteArray(0)) }
    }

    @Test
    fun `every truncation of a sealed message is rejected, never misread`() {
        val full = SealedPayload.seal(sealed(groupId = "group:abc", members = listOf(me, peer)), ByteArray(0))
        for (length in 0 until full.size) {
            assertThrows(PacketFormatException::class.java) { SealedPayload.open(full.copyOf(length)) }
        }
    }

    @Test
    fun `an unknown version or type is rejected`() {
        val bytes = SealedPayload.seal(sealed(), ByteArray(0))
        val wrongVersion = bytes.copyOf().also { it[3] = 9 }
        assertThrows(PacketFormatException::class.java) { SealedPayload.open(wrongVersion) }
        // "TEXT" starts after magic(3) + version(1) + flags(1) + length(2).
        val wrongType = bytes.copyOf().also { it[7] = 'X'.code.toByte() }
        assertThrows(PacketFormatException::class.java) { SealedPayload.open(wrongType) }
    }

    @Test
    fun `more members than the cap cannot be sealed`() {
        val members = (0..SealedPayload.MAX_MEMBERS).map { "member-$it" }
        assertThrows(IllegalArgumentException::class.java) {
            SealedPayload.seal(sealed(groupId = "group:abc", members = members), ByteArray(0))
        }
    }

    // ------------------------------------------------------------------ binding

    @Test
    fun `matching routing and sealed headers are accepted`() {
        assertNull(MeshInboundPolicy.bindingError(outer(), sealed(), me))
    }

    @Test
    fun `a relay that rewrites the packet type or id is detected`() {
        assertEquals("type", MeshInboundPolicy.bindingError(outer(type = PacketType.VOICE), sealed(), me))
        assertEquals("packet id", MeshInboundPolicy.bindingError(outer(packetId = "other"), sealed(), me))
    }

    @Test
    fun `a message sealed for another device is refused`() {
        assertEquals("recipient", MeshInboundPolicy.bindingError(outer(), sealed(to = other), me))
    }

    @Test
    fun `ids that could escape a directory are refused`() {
        assertEquals("message id", MeshInboundPolicy.bindingError(outer(), sealed(msgId = "../../databases/x"), me))
        assertEquals("message id", MeshInboundPolicy.bindingError(outer(), sealed(msgId = ""), me))
        assertEquals("group id", MeshInboundPolicy.bindingError(outer(), sealed(groupId = "a/b"), me))
        assertEquals(
            "group member",
            MeshInboundPolicy.bindingError(outer(), sealed(groupId = "group:x", members = listOf(me, "a b")), me)
        )
    }

    @Test
    fun `an acknowledgement must name a packet`() {
        val ack = sealed(type = PacketType.ACK, msgId = "")
        assertEquals("ack target", MeshInboundPolicy.bindingError(outer(type = PacketType.ACK), ack, me))
        assertNull(
            MeshInboundPolicy.bindingError(outer(type = PacketType.ACK), ack.copy(ackPacketId = "packet-9"), me)
        )
        assertEquals(
            "ack target",
            MeshInboundPolicy.bindingError(
                outer(type = PacketType.ACK), ack.copy(ackPacketId = "packet-9", ackMsgId = "../x"), me
            )
        )
    }

    @Test
    fun `safe ids`() {
        assertTrue(MeshInboundPolicy.isSafeId("group:6f1c2a34-0000-4000-8000-000000000000"))
        assertTrue(MeshInboundPolicy.isSafeFileId("6f1c2a34-0000-4000-8000-000000000000"))
        assertFalse(MeshInboundPolicy.isSafeFileId("group:6f1c"))
        assertFalse(MeshInboundPolicy.isSafeFileId(".."))
        assertFalse(MeshInboundPolicy.isSafeFileId("a/b"))
        assertFalse(MeshInboundPolicy.isSafeFileId("a\\b"))
        assertFalse(MeshInboundPolicy.isSafeFileId(""))
        assertFalse(MeshInboundPolicy.isSafeFileId(null))
        assertFalse(MeshInboundPolicy.isSafeFileId("x".repeat(97)))
    }

    // ------------------------------------------------------------------- chunks

    private val chunk = FileChunker.CHUNK_SIZE

    @Test
    fun `well-formed chunks are accepted`() {
        assertNull(MeshInboundPolicy.chunkError("file-1", 0, 3, 2L * chunk + 10, chunk))
        assertNull(MeshInboundPolicy.chunkError("file-1", 2, 3, 2L * chunk + 10, 10))
        assertNull(MeshInboundPolicy.chunkError("file-1", 0, 1, 0, 0))
    }

    @Test
    fun `chunk indexes outside the declared range are refused`() {
        assertEquals("chunk index", MeshInboundPolicy.chunkError("file-1", -1, 3, 0, chunk))
        assertEquals("chunk index", MeshInboundPolicy.chunkError("file-1", 3, 3, 0, chunk))
        assertEquals("chunk count", MeshInboundPolicy.chunkError("file-1", 0, 0, 0, chunk))
        assertEquals(
            "chunk count",
            MeshInboundPolicy.chunkError("file-1", 0, MeshInboundPolicy.MAX_CHUNKS + 1, 0, chunk)
        )
    }

    @Test
    fun `a transfer cannot be declared larger than the mesh limit or than its chunks`() {
        assertEquals("file size", MeshInboundPolicy.chunkError("file-1", 0, 2, FileChunker.MAX_FILE_SIZE + 1, chunk))
        assertEquals("file size", MeshInboundPolicy.chunkError("file-1", 0, 2, 5L * chunk, chunk))
        assertEquals("chunk size", MeshInboundPolicy.chunkError("file-1", 0, 2, 0, chunk + 1))
        assertEquals("chunk size", MeshInboundPolicy.chunkError("file-1", 0, 2, 0, 12))
    }

    @Test
    fun `a file id that is a path is refused before anything touches the disk`() {
        assertEquals("file id", MeshInboundPolicy.chunkError("../../files/media_vault/x", 0, 1, 0, 0))
        assertEquals("file id", MeshInboundPolicy.chunkError("a/b", 0, 1, 0, 0))
    }

    @Test
    fun `file names lose their path and control characters`() {
        assertEquals("passwd", MeshInboundPolicy.safeFileName("../../etc/passwd"))
        assertEquals("report.pdf", MeshInboundPolicy.safeFileName("C:\\Users\\me\\report.pdf"))
        assertEquals("ab.txt", MeshInboundPolicy.safeFileName("a\u0000b\n.txt"))
        assertEquals("hidden", MeshInboundPolicy.safeFileName("...hidden"))
        assertEquals("file", MeshInboundPolicy.safeFileName("///"))
        assertEquals("фото.jpg", MeshInboundPolicy.safeFileName("фото.jpg"))
        assertEquals(MeshInboundPolicy.MAX_FILE_NAME_CHARS, MeshInboundPolicy.safeFileName("x".repeat(500)).length)
    }

    // --------------------------------------------------------------- timestamps

    @Test
    fun `plausible timestamps are kept and impossible ones replaced by the arrival time`() {
        val now = 1_700_000_000_000
        assertEquals(now - 60_000, MeshInboundPolicy.acceptedTimestamp(now - 60_000, now))
        assertEquals(now + 60_000, MeshInboundPolicy.acceptedTimestamp(now + 60_000, now))
        assertEquals(now, MeshInboundPolicy.acceptedTimestamp(now + 3_600_000, now))
        assertEquals(now, MeshInboundPolicy.acceptedTimestamp(Long.MAX_VALUE, now))
        assertEquals(now, MeshInboundPolicy.acceptedTimestamp(0, now))
        // A message that waited the whole store-and-forward window keeps its real time.
        val waited = now - RoutingPolicy.STORE_FORWARD_MS
        assertEquals(waited, MeshInboundPolicy.acceptedTimestamp(waited, now))
    }

    // ------------------------------------------------------------------- groups

    private fun route(
        exists: Boolean,
        isMeshGroup: Boolean = true,
        known: List<String> = emptyList(),
        sender: String = peer,
        sealedMembers: List<String> = listOf(me, peer)
    ) = MeshInboundPolicy.groupRoute(exists, isMeshGroup, known, sender, sealedMembers, me)

    @Test
    fun `an unknown group is created from the sealed member list`() {
        assertEquals(MeshInboundPolicy.GroupRoute.CREATE, route(exists = false))
    }

    @Test
    fun `a group id that is really a direct chat cannot be written to`() {
        // The id of a direct conversation is the peer's device id; naming it as a "group" used to
        // drop the message into that chat, where it looked as if the peer had written it.
        assertEquals(MeshInboundPolicy.GroupRoute.REJECT_NOT_A_GROUP, route(exists = true, isMeshGroup = false))
    }

    @Test
    fun `only a known member may post into an existing group`() {
        assertEquals(MeshInboundPolicy.GroupRoute.ACCEPT, route(exists = true, known = listOf(me, peer)))
        assertEquals(
            MeshInboundPolicy.GroupRoute.REJECT_NOT_A_MEMBER,
            route(exists = true, known = listOf(me, other), sender = peer)
        )
    }

    @Test
    fun `a group created by protocol one adopts its first sealed member list`() {
        assertEquals(MeshInboundPolicy.GroupRoute.ACCEPT, route(exists = true, known = emptyList()))
    }

    @Test
    fun `the sender must list itself and the recipient`() {
        assertEquals(
            MeshInboundPolicy.GroupRoute.REJECT_NOT_A_MEMBER,
            route(exists = false, sealedMembers = listOf(peer, other))
        )
        assertEquals(
            MeshInboundPolicy.GroupRoute.REJECT_NOT_A_MEMBER,
            route(exists = false, sealedMembers = listOf(me, other))
        )
        assertEquals(MeshInboundPolicy.GroupRoute.REJECT_NOT_A_MEMBER, route(exists = false, sealedMembers = emptyList()))
    }

    // --------------------------------------------------------- acknowledgements

    @Test
    fun `only a participant can settle an outgoing message`() {
        assertTrue(MeshInboundPolicy.ackAllowed(outgoing = true, conversationMembers = listOf(me, peer), sender = peer))
        assertFalse(MeshInboundPolicy.ackAllowed(outgoing = true, conversationMembers = listOf(me, peer), sender = other))
        assertFalse(MeshInboundPolicy.ackAllowed(outgoing = false, conversationMembers = listOf(me, peer), sender = peer))
    }
}
