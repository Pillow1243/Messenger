package com.offlinemesh.messenger.data

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.offlinemesh.messenger.core.crypto.CryptoManager
import com.offlinemesh.messenger.core.crypto.PreKeyBundleCodec
import com.offlinemesh.messenger.core.crypto.SignalStore
import com.offlinemesh.messenger.core.db.AppDatabase
import com.offlinemesh.messenger.core.db.MessageEntity
import com.offlinemesh.messenger.core.mesh.FileChunker
import com.offlinemesh.messenger.core.mesh.FrameAction
import com.offlinemesh.messenger.core.mesh.FrameRouting
import com.offlinemesh.messenger.core.mesh.MeshLink
import com.offlinemesh.messenger.core.mesh.PacketCodec
import com.offlinemesh.messenger.core.mesh.PacketHeader
import com.offlinemesh.messenger.core.mesh.PacketType
import com.offlinemesh.messenger.core.mesh.PeerInfo
import com.offlinemesh.messenger.core.mesh.RoutingPolicy
import com.offlinemesh.messenger.core.mesh.SealedHeader
import com.offlinemesh.messenger.core.mesh.SealedPayload
import com.offlinemesh.messenger.core.mesh.SeenPacketCache
import java.io.File
import java.util.UUID
import kotlin.random.Random
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the real message layer of several devices against each other on an emulator.
 *
 * Each [Node] is a complete device stack - its own database, its own Signal identity and
 * sessions (the real libsignal), its own file store and its own [ChatRepository] - and the only
 * thing replaced is the radio: frames travel through an in-process [Hub] instead of Nearby
 * Connections, routed by the same [FrameRouting] the real transport uses. The hub also lets a
 * test stand where an attacker in radio range stands: it can drop, rewrite and inject frames.
 *
 * What is checked here is the behaviour the mesh protocol promises, end to end: a message
 * arrives once and in the right chat, a receipt can only come from the recipient, a relay can
 * neither redirect nor forge, and a file transfer cannot write outside its staging directory.
 */
@RunWith(AndroidJUnit4::class)
class MeshProtocolInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var hub: Hub
    private lateinit var root: File
    private val json = Json { ignoreUnknownKeys = true }

    @Before
    fun setUp() {
        root = File(context.cacheDir, "mesh-protocol-test-" + UUID.randomUUID())
        hub = Hub()
    }

    @After
    fun tearDown() {
        hub.nodes.values.forEach { it.db.close() }
        root.deleteRecursively()
    }

    // ------------------------------------------------------------------ delivery

    @Test
    fun aTextArrivesOnceAndIsConfirmedByTheRecipient() = runBlocking {
        val a = node("Alice")
        val b = node("Bob")
        connect(a, b)

        a.chats.sendText(b.id, "hello Bob")
        hub.pump()

        assertEquals(listOf("hello Bob"), b.messagesWith(a.id).map { it.body })
        assertFalse(b.messagesWith(a.id).single().outgoing)
        val sent = a.messagesWith(b.id).single()
        assertEquals(MessageStatus.DELIVERED, sent.status)
        assertEquals("the queued copy is released by the receipt", 0, a.db.relayDao().count())
    }

    @Test
    fun aMessageForAnAbsentDeviceIsKeptAndDeliveredWhenItAppears() = runBlocking {
        val a = node("Alice")
        val b = node("Bob")
        introduce(a, b)
        a.chats.ensureDirectConversation(b.id, "Bob")

        a.chats.sendText(b.id, "see you later")
        hub.pump()
        assertEquals(1, a.db.relayDao().count())
        assertNotEquals(MessageStatus.DELIVERED, a.messagesWith(b.id).single().status)

        connect(a, b)

        assertEquals(listOf("see you later"), b.messagesWith(a.id).map { it.body })
        assertEquals(MessageStatus.DELIVERED, a.messagesWith(b.id).single().status)
        assertEquals(0, a.db.relayDao().count())
    }

    @Test
    fun aMessageAndItsReceiptCrossARelay() = runBlocking {
        val a = node("Alice")
        val relay = node("Relay")
        val b = node("Bob")
        introduce(a, b)
        a.chats.ensureDirectConversation(b.id, "Bob")
        connect(a, relay)
        connect(relay, b)

        a.chats.sendText(b.id, "over two hops")
        hub.pump()

        assertEquals(listOf("over two hops"), b.messagesWith(a.id).map { it.body })
        assertEquals(
            "the receipt has to travel back through the relay",
            MessageStatus.DELIVERED,
            a.messagesWith(b.id).single().status
        )
        assertTrue("the relay cannot read what it carries", relay.messagesWith(a.id).isEmpty())
    }

    @Test
    fun aLostReceiptIsRepeatedWhenTheSenderRepeatsThePacket() = runBlocking {
        val a = node("Alice")
        val b = node("Bob")
        connect(a, b)

        hub.tap = { frame -> frame.takeUnless { it.header.type == PacketType.ACK } }
        a.chats.sendText(b.id, "did you get this?")
        hub.pump()

        assertEquals(1, b.messagesWith(a.id).size)
        assertNotEquals(MessageStatus.DELIVERED, a.messagesWith(b.id).single().status)
        assertEquals("unconfirmed, so still queued", 1, a.db.relayDao().count())

        hub.tap = { it }
        // The link comes back: everything unconfirmed for Bob is sent again.
        a.chats.onPeerReady(b.peerInfo())
        hub.pump()

        assertEquals("the repeat is not stored twice", 1, b.messagesWith(a.id).size)
        assertEquals(MessageStatus.DELIVERED, a.messagesWith(b.id).single().status)
        assertEquals(0, a.db.relayDao().count())
    }

    // ------------------------------------------------------------------ receipts

    @Test
    fun aReceiptCannotBeForged() = runBlocking {
        val a = node("Alice")
        val b = node("Bob")
        val mallory = node("Mallory")
        introduce(a, b)
        a.chats.ensureDirectConversation(b.id, "Bob")
        a.chats.sendText(b.id, "for Bob only")
        hub.pump()
        val message = a.messagesWith(b.id).single()
        val queued = a.db.relayDao().getAll().single()
        val now = System.currentTimeMillis()

        fun ackHeader(from: String) = PacketHeader(
            packetId = UUID.randomUUID().toString(),
            type = PacketType.ACK,
            from = from,
            to = a.id,
            createdAt = now,
            expiresAt = RoutingPolicy.expiryFrom(now),
            ackFor = message.id
        )

        // 1. What protocol 1 accepted: a plain header that claims to come from Bob.
        a.receive("radio", PacketCodec.encode(ackHeader(b.id), ByteArray(0)))
        // 2. The same with bytes that are not a ciphertext of Bob's session.
        a.receive("radio", PacketCodec.encode(ackHeader(b.id), ByteArray(96) { 7 }))
        // 3. A genuine contact of Alice acknowledges a packet that was queued for Bob.
        connect(a, mallory)
        val forged = ackHeader(mallory.id)
        val sealed = SealedHeader(
            type = PacketType.ACK,
            to = a.id,
            packetId = forged.packetId,
            createdAt = now,
            ackPacketId = queued.packetId,
            ackMsgId = message.id
        )
        val body = mallory.crypto.encrypt(a.id, SealedPayload.seal(sealed, ByteArray(0)))
        a.receive(mallory.id, PacketCodec.encode(forged, body))
        hub.pump()

        assertNotEquals(MessageStatus.DELIVERED, a.messagesWith(b.id).single().status)
        assertNotNull("the ciphertext for Bob must still be queued", a.db.relayDao().getById(queued.packetId))

        // And the real recipient still gets it and can confirm it.
        connect(a, b)
        assertEquals(listOf("for Bob only"), b.messagesWith(a.id).map { it.body })
        assertEquals(MessageStatus.DELIVERED, a.messagesWith(b.id).single().status)
    }

    // ------------------------------------------------------------------ routing fields

    @Test
    fun aRelayCannotMoveAMessageIntoAnotherChatOrChangeItsTime() = runBlocking {
        val a = node("Alice")
        val b = node("Bob")
        val c = node("Carol")
        connect(a, b)
        connect(b, c)
        val longAgo = 1_000L

        // Whoever carries the frame rewrites every routing field the old protocol trusted.
        hub.tap = { frame ->
            if (frame.header.type == PacketType.TEXT) {
                frame.copy(
                    header = frame.header.copy(
                        groupId = c.id,
                        groupName = "Renamed",
                        createdAt = longAgo,
                        meta = "{\"fileId\":\"x\"}"
                    )
                )
            } else {
                frame
            }
        }
        a.chats.sendText(b.id, "from Alice, to Bob")
        hub.pump()

        assertTrue("nothing may appear in the chat with Carol", b.messagesWith(c.id).isEmpty())
        val received = b.messagesWith(a.id).single()
        assertEquals("from Alice, to Bob", received.body)
        assertEquals(a.id, received.senderDeviceId)
        assertTrue("the sender's timestamp is used, not the relay's", received.timestamp > longAgo)
        assertEquals("Carol", b.db.conversationDao().getById(c.id)?.title)
    }

    @Test
    fun aRelayCannotRedirectAMessageToAnotherMessageId() = runBlocking {
        val a = node("Alice")
        val b = node("Bob")
        connect(a, b)
        a.chats.sendText(b.id, "first")
        hub.pump()
        val first = b.messagesWith(a.id).single()

        hub.tap = { frame ->
            if (frame.header.type == PacketType.TEXT) frame.copy(header = frame.header.copy(msgId = first.id)) else frame
        }
        a.chats.sendText(b.id, "second")
        hub.pump()

        assertEquals(setOf("first", "second"), b.messagesWith(a.id).map { it.body }.toSet())
        assertEquals(2, b.messagesWith(a.id).size)
    }

    @Test
    fun aContactCannotWriteIntoSomebodyElsesDirectChat() = runBlocking {
        val a = node("Alice")
        val b = node("Bob")
        val mallory = node("Mallory")
        connect(a, b)
        connect(mallory, b)

        // Bob's chat with Alice has Alice's device id as its conversation id. Mallory, a contact
        // of Bob with a valid session, names that id as a "group".
        mallory.sendCrafted(
            to = b,
            type = PacketType.TEXT,
            payload = "Alice says: send me the code".toByteArray(),
            groupId = a.id,
            groupName = "Alice",
            groupMembers = listOf(mallory.id, b.id)
        )
        hub.pump()

        assertTrue(b.messagesWith(a.id).isEmpty())
        assertEquals("Alice", b.db.conversationDao().getById(a.id)?.title)
    }

    @Test
    fun onlyMembersCanWriteInAGroupAndMembersCanReply() = runBlocking {
        val a = node("Alice")
        val b = node("Bob")
        val c = node("Carol")
        val mallory = node("Mallory")
        connect(a, b)
        connect(a, c)
        connect(b, c)
        connect(mallory, b)

        val group = a.chats.createGroup("Trip", listOf(b.id, c.id))
        a.chats.sendText(group, "who is driving?")
        hub.pump()

        assertEquals(listOf("who is driving?"), b.messagesWith(group).map { it.body })
        assertEquals(listOf("who is driving?"), c.messagesWith(group).map { it.body })
        assertEquals("Trip", b.db.conversationDao().getById(group)?.title)

        // A receiver learns the members with the first message, so it can answer everybody.
        b.chats.sendText(group, "I am")
        hub.pump()
        assertEquals(setOf("who is driving?", "I am"), a.messagesWith(group).map { it.body }.toSet())
        assertEquals(2, a.messagesWith(group).size)
        assertEquals(setOf("who is driving?", "I am"), c.messagesWith(group).map { it.body }.toSet())
        assertEquals(2, c.messagesWith(group).size)

        // Somebody who is not in the group, but has a session with Bob, tries to post in it.
        mallory.sendCrafted(
            to = b,
            type = PacketType.TEXT,
            payload = "I am in this group too".toByteArray(),
            groupId = group,
            groupName = "Hijacked",
            groupMembers = listOf(mallory.id, b.id)
        )
        hub.pump()

        assertEquals(setOf("who is driving?", "I am"), b.messagesWith(group).map { it.body }.toSet())
        assertEquals(2, b.messagesWith(group).size)
        assertEquals("Trip", b.db.conversationDao().getById(group)?.title)
    }

    // ------------------------------------------------------------------ files

    @Test
    fun aFileArrivesIntactAndNoPlaintextIsLeftBehind() = runBlocking {
        val a = node("Alice")
        val b = node("Bob")
        connect(a, b)
        val bytes = Random(42).nextBytes(FileChunker.CHUNK_SIZE * 3 + 1234)
        val source = File(root, "outgoing.bin").apply {
            parentFile?.mkdirs()
            writeBytes(bytes)
        }

        a.chats.sendFile(b.id, source, "report.pdf")
        hub.pump()

        val received = b.messagesWith(a.id).single()
        assertEquals("report.pdf", received.fileName)
        val sealed = File(requireNotNull(received.filePath))
        assertTrue("stored inside Bob's own attachment directory", sealed.canonicalPath.startsWith(b.root.canonicalPath))
        assertFalse("stored encrypted", sealed.readBytes().contentEquals(bytes))
        val opened = requireNotNull(b.files.exportForOpening(sealed, "report.pdf"))
        assertArrayEquals(bytes, opened.readBytes())

        assertTrue("reassembly chunks are removed", b.stagingEntries().isEmpty())
        assertFalse("the sender's plaintext copy is removed", source.exists())
        assertEquals(MessageStatus.DELIVERED, a.messagesWith(b.id).single().status)
        assertEquals(0, a.db.relayDao().count())
    }

    @Test
    fun aFileTransferCannotWriteOutsideItsStagingDirectory() = runBlocking {
        val a = node("Alice")
        val b = node("Bob")
        val mallory = node("Mallory")
        connect(a, b)
        connect(mallory, b)
        val chunk = ByteArray(100) { 1 }

        fun meta(fileId: String, index: Int = 0, total: Int = 1, size: Long = 100, name: String = "x.bin") =
            json.encodeToString(FileChunkMeta.serializer(), FileChunkMeta(fileId, name, index, total, size, false))

        val honestId = UUID.randomUUID().toString()
        // A transfer id that walks out of the staging directory.
        mallory.sendCrafted(b, PacketType.FILE_CHUNK, chunk, msgId = honestId, meta = meta("../../../files/evil"))
        mallory.sendCrafted(b, PacketType.FILE_CHUNK, chunk, msgId = "../../../files/evil", meta = meta("../../../files/evil"))
        // Indexes and sizes that do not describe a real transfer.
        mallory.sendCrafted(b, PacketType.FILE_CHUNK, chunk, msgId = honestId, meta = meta(honestId, index = -1))
        mallory.sendCrafted(b, PacketType.FILE_CHUNK, chunk, msgId = honestId, meta = meta(honestId, index = 5, total = 2))
        mallory.sendCrafted(b, PacketType.FILE_CHUNK, chunk, msgId = honestId, meta = meta(honestId, total = 2_000_000, size = 1L shl 40))
        mallory.sendCrafted(
            b, PacketType.FILE_CHUNK, ByteArray(FileChunker.CHUNK_SIZE + 1), msgId = honestId,
            meta = meta(honestId, size = (FileChunker.CHUNK_SIZE + 1).toLong())
        )
        hub.pump()

        assertTrue("nothing was accepted", b.messagesWith(mallory.id).isEmpty())
        assertTrue("nothing was staged", b.stagingEntries().isEmpty())
        val escaped = root.walkTopDown().filter { it.name.contains("evil") }.toList()
        assertTrue("no file outside the staging directory: $escaped", escaped.isEmpty())

        // A file name that is a path arrives as a plain name.
        val named = UUID.randomUUID().toString()
        mallory.sendCrafted(b, PacketType.FILE_CHUNK, chunk, msgId = named, meta = meta(named, name = "../../etc/passwd"))
        hub.pump()
        assertEquals("passwd", b.messagesWith(mallory.id).single().fileName)
    }

    @Test
    fun aContactCannotOverwriteAnotherContactsTransfer() = runBlocking {
        val a = node("Alice")
        val b = node("Bob")
        val mallory = node("Mallory")
        connect(a, b)
        connect(mallory, b)
        val bytes = Random(7).nextBytes(FileChunker.CHUNK_SIZE + 10)
        val source = File(root, "two-chunks.bin").apply {
            parentFile?.mkdirs()
            writeBytes(bytes)
        }

        // Only the first chunk of Alice's file gets through for now.
        var chunksSeen = 0
        hub.tap = { frame ->
            if (frame.header.type == PacketType.FILE_CHUNK && frame.header.from == a.id) {
                if (chunksSeen++ == 0) frame else null
            } else {
                frame
            }
        }
        a.chats.sendFile(b.id, source, "contract.pdf")
        hub.pump()
        hub.tap = { it }
        val transferId = a.messagesWith(b.id).single().id
        assertEquals(1, b.stagingEntries().size)

        // Mallory supplies "the rest" of Alice's transfer.
        val meta = json.encodeToString(
            FileChunkMeta.serializer(),
            FileChunkMeta(transferId, "contract.pdf", 1, 2, bytes.size.toLong(), false)
        )
        mallory.sendCrafted(b, PacketType.FILE_CHUNK, ByteArray(10) { 9 }, msgId = transferId, meta = meta)
        hub.pump()
        assertTrue("the transfer is not completed with foreign data", b.messagesWith(a.id).isEmpty())
        assertTrue(b.messagesWith(mallory.id).isEmpty())

        // Alice repeats what was lost and the file is hers, byte for byte.
        a.chats.onPeerReady(b.peerInfo())
        hub.pump()
        val received = b.messagesWith(a.id).single()
        val opened = requireNotNull(b.files.exportForOpening(File(requireNotNull(received.filePath)), "contract.pdf"))
        assertArrayEquals(bytes, opened.readBytes())
    }

    // ------------------------------------------------------------------ harness

    /** A frame on the air, as an eavesdropper or a relay sees it. */
    data class Frame(val from: String, val to: String, val header: PacketHeader, val body: ByteArray)

    inner class Hub {
        val nodes = LinkedHashMap<String, Node>()
        private val queue = ArrayDeque<Frame>()

        /** Sees every frame before it is delivered; returns it (possibly changed) or null to drop it. */
        var tap: (Frame) -> Frame? = { it }

        fun enqueue(frame: Frame) {
            queue.addLast(frame)
        }

        /** Delivers frames until the air is quiet. */
        suspend fun pump() {
            var delivered = 0
            while (queue.isNotEmpty()) {
                check(delivered++ < 20_000) { "frames keep circulating" }
                val frame = tap(queue.removeFirst()) ?: continue
                nodes[frame.to]?.receive(frame.from, PacketCodec.encode(frame.header, frame.body))
            }
        }
    }

    inner class Node(val name: String) : MeshLink {
        val id: String = UUID.randomUUID().toString()
        val root: File = File(this@MeshProtocolInstrumentedTest.root, id)
        val db: AppDatabase = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).build()
        val crypto = CryptoManager(db, SignalStore(db))
        val files = FileRepository(context, baseDir = root)
        private val settings = SettingsRepository(context, deviceIdOverride = id)
        private val contacts = ContactRepository(db, crypto, settings)
        private val online = OnlineSync(context, db, crypto, settings)
        val chats = ChatRepository(db, crypto, this, files, settings, contacts, online, null)

        private val seen = SeenPacketCache()
        val neighbours = LinkedHashSet<String>()

        override val peerFlow: StateFlow<List<PeerInfo>> = MutableStateFlow(emptyList())
        override val running: StateFlow<Boolean> = MutableStateFlow(true)

        override fun deviceId(): String = id

        override fun sendTo(deviceId: String, header: PacketHeader, body: ByteArray): Boolean {
            if (deviceId !in neighbours) return false
            hub.enqueue(Frame(id, deviceId, header, body))
            return true
        }

        override fun broadcast(header: PacketHeader, body: ByteArray, exceptEndpointId: String?): Int {
            var sent = 0
            for (neighbour in neighbours) {
                if (neighbour == exceptEndpointId) continue
                hub.enqueue(Frame(id, neighbour, header, body))
                sent++
            }
            return sent
        }

        override fun isConnected(deviceId: String): Boolean = deviceId in neighbours

        override fun connectedDeviceIds(): Set<String> = neighbours.toSet()

        fun peerInfo() = PeerInfo(endpointId = id, deviceId = id, displayName = name, connectedAt = System.currentTimeMillis())

        /** What MeshManager does with a frame from a neighbour, minus the radio. */
        suspend fun receive(fromNeighbour: String, frame: ByteArray) {
            val packet = try {
                PacketCodec.decode(frame)
            } catch (e: Exception) {
                return
            }
            val header = packet.header
            val now = System.currentTimeMillis()
            if (!FrameRouting.acceptable(header, id, now)) return
            val duplicate = !seen.markSeen(header.packetId, now)
            when (FrameRouting.decide(header, id, now, duplicate)) {
                FrameAction.DROP, FrameAction.HELLO -> Unit
                FrameAction.ACK_FOR_ME -> chats.onAck(header, packet.body)
                FrameAction.DUPLICATE_FOR_ME -> chats.onDuplicate(header, packet.body)
                FrameAction.DELIVER -> chats.onPacket(header, packet.body)
                FrameAction.RELAY -> relay(fromNeighbour, header, packet.body)
                FrameAction.DELIVER_AND_RELAY -> {
                    chats.onPacket(header, packet.body)
                    relay(fromNeighbour, header, packet.body)
                }
            }
        }

        private suspend fun relay(fromNeighbour: String, header: PacketHeader, body: ByteArray) {
            chats.onRelay(header, body)
            broadcast(RoutingPolicy.forwarded(header), body, exceptEndpointId = fromNeighbour)
        }

        suspend fun messagesWith(conversationId: String): List<MessageEntity> =
            db.messageDao().page(conversationId, Long.MAX_VALUE, 200).sortedBy { it.timestamp }

        fun stagingEntries(): List<String> =
            File(root, "cache/staging").list()?.toList().orEmpty()

        /**
         * Sends a packet the way a modified client would: correctly encrypted for [to] under this
         * node's own session - so it authenticates - but with whatever the caller wants inside.
         */
        suspend fun sendCrafted(
            to: Node,
            type: PacketType,
            payload: ByteArray,
            msgId: String = UUID.randomUUID().toString(),
            groupId: String? = null,
            groupName: String? = null,
            groupMembers: List<String> = emptyList(),
            meta: String = ""
        ) {
            val now = System.currentTimeMillis()
            val header = PacketHeader(
                packetId = UUID.randomUUID().toString(),
                type = type,
                from = id,
                to = to.id,
                groupId = groupId,
                groupName = groupName,
                msgId = msgId,
                createdAt = now,
                expiresAt = RoutingPolicy.expiryFrom(now),
                meta = meta
            )
            val sealed = SealedHeader(
                type = type,
                to = to.id,
                packetId = header.packetId,
                msgId = msgId,
                createdAt = now,
                groupId = groupId,
                groupName = groupName,
                groupMembers = groupMembers,
                meta = meta
            )
            val body = crypto.encrypt(to.id, SealedPayload.seal(sealed, payload))
            check(sendTo(to.id, header, body)) { "$name is not connected to ${to.name}" }
        }
    }

    private fun node(name: String): Node = runBlocking {
        Node(name).also {
            it.crypto.ensureIdentity()
            hub.nodes[it.id] = it
        }
    }

    /** The two devices have met before: each holds a session with the other. */
    private suspend fun introduce(first: Node, second: Node) {
        first.crypto.processPeerBundle(second.id, PreKeyBundleCodec.encode(second.crypto.currentBundle()))
        second.crypto.processPeerBundle(first.id, PreKeyBundleCodec.encode(first.crypto.currentBundle()))
    }

    /** The two devices come into radio range: handshake, then everything queued is exchanged. */
    private suspend fun connect(first: Node, second: Node) {
        if (!first.crypto.hasSession(second.id)) introduce(first, second)
        first.neighbours += second.id
        second.neighbours += first.id
        first.chats.onPeerReady(second.peerInfo())
        second.chats.onPeerReady(first.peerInfo())
        hub.pump()
    }
}
