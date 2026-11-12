package com.offlinemesh.messenger.core.mesh

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.offlinemesh.messenger.core.crypto.CryptoManager
import com.offlinemesh.messenger.core.crypto.IdentityChangedException
import com.offlinemesh.messenger.core.db.SeenPacketDao
import com.offlinemesh.messenger.core.db.SeenPacketEntity
import com.offlinemesh.messenger.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class HelloPayload(
    val protocol: Int = MESH_PROTOCOL_VERSION,
    val deviceId: String,
    val name: String,
    /** Base64 of the 33-byte identity public key. */
    val identityKey: String,
    val registrationId: Int,
    /** Base64 of the encoded Signal pre-key bundle. */
    val bundle: String
)

data class PeerInfo(
    val endpointId: String,
    val deviceId: String,
    val displayName: String,
    val connectedAt: Long
)

/**
 * Google Nearby Connections transport (BLE advertising/discovery + Wi-Fi Direct links).
 *
 * The manager is deliberately transport-plumbing only:
 *  - it exchanges HELLOs (device id, name, identity key, pre-key bundle) once a link is up,
 *  - it frames and routes packets with [PacketCodec] / [RoutingPolicy],
 *  - it suppresses duplicates and forwards packets that are not addressed to us,
 *  - it re-establishes links when the radios come back.
 * Message content, encryption and persistence live in the data layer.
 */
class MeshManager(
    private val context: Context,
    private val settings: SettingsRepository,
    private val crypto: CryptoManager,
    private val seenPacketDao: SeenPacketDao? = null
) : MeshLink {

    interface PacketHandler {
        /** A link is up and the peer's pre-key bundle has been processed. */
        suspend fun onPeerReady(peer: PeerInfo)

        /** A packet addressed to this device. Body is Signal ciphertext. */
        suspend fun onPacket(header: PacketHeader, body: ByteArray)

        /** A packet we are relaying for someone else; persist it for store-and-forward. */
        suspend fun onRelay(header: PacketHeader, body: ByteArray)

        /**
         * A delivery receipt addressed to this device. [body] is Signal ciphertext: what is being
         * acknowledged is only known, and only believed, after it decrypts.
         */
        suspend fun onAck(header: PacketHeader, body: ByteArray)

        /** A packet addressed to this device that was already seen once (a retransmission). */
        suspend fun onDuplicate(header: PacketHeader, body: ByteArray) {}

        suspend fun onPeerGone(deviceId: String)

        /** Called every [WATCHDOG_INTERVAL_MS] while at least one peer is connected. */
        suspend fun onTick() {}
    }

    private val client: ConnectionsClient = Nearby.getConnectionsClient(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true }
    private val seen = SeenPacketCache()

    private val peers = ConcurrentHashMap<String, PeerInfo>()          // endpointId -> peer
    private val endpointByDevice = ConcurrentHashMap<String, String>() // deviceId -> endpointId
    private val discovered = ConcurrentHashMap<String, String>()       // endpointId -> name
    private val pendingRequests = Collections.synchronizedSet(mutableSetOf<String>())
    private val retryState = ConcurrentHashMap<String, Long>()         // endpointId -> next attempt

    private val _peerFlow = MutableStateFlow<List<PeerInfo>>(emptyList())
    override val peerFlow: StateFlow<List<PeerInfo>> = _peerFlow.asStateFlow()

    private val _running = MutableStateFlow(false)
    override val running: StateFlow<Boolean> = _running.asStateFlow()

    @Volatile private var handler: PacketHandler? = null
    @Volatile private var advertising = false
    @Volatile private var discovering = false
    @Volatile private var discoveryPaused = false
    @Volatile private var myDeviceId: String = ""
    @Volatile private var myName: String = ""
    @Volatile private var helloBundle: String? = null
    @Volatile private var helloIdentity: String? = null

    @Volatile private var seenPreloaded = false
    private var watchdog: Job? = null

    fun setHandler(newHandler: PacketHandler) {
        handler = newHandler
    }

    /** Loads identity and prepares the HELLO material. Safe to call repeatedly. */
    suspend fun refreshIdentity() = kotlinx.coroutines.withContext(Dispatchers.IO) {
        if (!seenPreloaded) {
            seenPacketDao?.let { dao ->
                runCatching {
                    val now = System.currentTimeMillis()
                    dao.deleteOlderThan(now - RoutingPolicy.PACKET_TTL_MS)
                    dao.recentPacketIds(4096).forEach { id -> seen.markSeen(id, now) }
                    seenPreloaded = true
                }
            }
        }
        myDeviceId = settings.deviceId()
        myName = settings.snapshot().displayName.ifBlank { "Mesh device" }
        crypto.ensureIdentity()
        helloIdentity = Base64.encodeToString(crypto.myIdentityKey().serialize(), Base64.NO_WRAP)
        helloBundle = try {
            Base64.encodeToString(
                com.offlinemesh.messenger.core.crypto.PreKeyBundleCodec.encode(crypto.currentBundle()),
                Base64.NO_WRAP
            )
        } catch (e: Exception) {
            Log.e(TAG, "Could not build pre-key bundle", e)
            null
        }
        Unit
    }

    override fun deviceId(): String = myDeviceId

    // --------------------------------------------------------------- lifecycle

    fun start() {
        if (_running.value) return
        scope.launch {
            if (myDeviceId.isEmpty()) refreshIdentity()
            startRadios()
            if (watchdog == null) watchdog = launchWatchdog()
        }
    }

    fun pause() {
        _running.value = false
        stopAdvertisingInternal()
        stopDiscoveryInternal()
        try {
            client.stopAllEndpoints()
        } catch (e: Exception) {
            Log.w(TAG, "stopAllEndpoints failed", e)
        }
        peers.clear()
        endpointByDevice.clear()
        publishPeers()
    }

    fun stop() {
        pause()
        watchdog?.cancel()
        watchdog = null
    }

    fun setDiscoveryActive(active: Boolean) {
        discoveryPaused = !active
        if (!_running.value) return
        if (active) startDiscoveryInternal() else stopDiscoveryInternal()
    }

    private fun startRadios() {
        _running.value = true
        startAdvertisingInternal()
        if (!discoveryPaused) startDiscoveryInternal()
    }

    private fun startAdvertisingInternal() {
        if (advertising) return
        try {
            client.startAdvertising(
                myName.ifBlank { "Mesh device" },
                SERVICE_ID,
                connectionLifecycleCallback,
                AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
            ).addOnSuccessListener {
                advertising = true
                Log.i(TAG, "Advertising started")
            }.addOnFailureListener { e ->
                advertising = false
                Log.w(TAG, "Advertising failed", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Advertising threw", e)
        }
    }

    private fun stopAdvertisingInternal() {
        try {
            client.stopAdvertising()
        } catch (e: Exception) {
            Log.w(TAG, "stopAdvertising failed", e)
        }
        advertising = false
    }

    private fun startDiscoveryInternal() {
        if (discovering || discoveryPaused) return
        try {
            client.startDiscovery(
                SERVICE_ID,
                endpointDiscoveryCallback,
                DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
            ).addOnSuccessListener {
                discovering = true
                Log.i(TAG, "Discovery started")
            }.addOnFailureListener { e ->
                discovering = false
                Log.w(TAG, "Discovery failed", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Discovery threw", e)
        }
    }

    private fun stopDiscoveryInternal() {
        try {
            client.stopDiscovery()
        } catch (e: Exception) {
            Log.w(TAG, "stopDiscovery failed", e)
        }
        discovering = false
    }

    /**
     * Keeps the radios alive: Nearby stops advertising after a while, links drop when a device
     * walks away, and Wi-Fi Direct tears down on its own. Every 15 s we re-arm what is off and
     * re-request connections to endpoints that are still around but no longer connected.
     */
    private fun launchWatchdog(): Job = scope.launch {
        while (isActive) {
            delay(WATCHDOG_INTERVAL_MS)
            if (!_running.value) continue
            if (!advertising) startAdvertisingInternal()
            if (!discovering && !discoveryPaused) startDiscoveryInternal()
            val now = System.currentTimeMillis()
            for ((endpointId, name) in discovered) {
                if (peers.containsKey(endpointId)) continue
                val nextAttempt = retryState[endpointId] ?: 0L
                if (now < nextAttempt) continue
                retryState[endpointId] = now + RECONNECT_INTERVAL_MS
                requestConnection(endpointId, name)
            }
            // Rebuild HELLO material periodically: pre-keys get consumed as peers connect.
            runCatching { refreshIdentity() }
            runCatching { seenPacketDao?.deleteOlderThan(now - RoutingPolicy.PACKET_TTL_MS) }
            // Unacknowledged packets are repeated from here; without this pass a packet written
            // to a link that dropped a moment later would wait for the next app start.
            if (peers.isNotEmpty()) runCatching { handler?.onTick() }
        }
    }

    private fun requestConnection(endpointId: String, endpointName: String) {
        if (!pendingRequests.add(endpointId)) return
        try {
            client.requestConnection(endpointName, endpointId, connectionLifecycleCallback)
                .addOnFailureListener { e ->
                    pendingRequests.remove(endpointId)
                    Log.w(TAG, "requestConnection to $endpointName failed", e)
                }
        } catch (e: Exception) {
            pendingRequests.remove(endpointId)
            Log.e(TAG, "requestConnection threw", e)
        }
    }

    // ----------------------------------------------------------------- callbacks

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            // Nearby proves both sides share the advertising service id; accept and authenticate
            // at the application layer with the Signal identity keys exchanged in HELLO.
            client.acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener { e -> Log.w(TAG, "acceptConnection failed", e) }
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            pendingRequests.remove(endpointId)
            if (resolution.status.isSuccess) {
                Log.i(TAG, "Connected to $endpointId")
                scope.launch { sendHello(endpointId) }
            } else {
                retryState[endpointId] = System.currentTimeMillis() + RECONNECT_INTERVAL_MS
                Log.w(TAG, "Connection to $endpointId rejected: ${resolution.status.statusMessage}")
            }
        }

        override fun onDisconnected(endpointId: String) {
            pendingRequests.remove(endpointId)
            val peer = peers.remove(endpointId)
            if (peer != null) {
                endpointByDevice.remove(peer.deviceId)
                publishPeers()
                handler?.let { h -> scope.launch { h.onPeerGone(peer.deviceId) } }
            }
            retryState[endpointId] = System.currentTimeMillis() + RECONNECT_INTERVAL_MS
            Log.i(TAG, "Disconnected from $endpointId")
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            discovered[endpointId] = info.endpointName
            if (peers.containsKey(endpointId)) return
            val nextAttempt = retryState[endpointId] ?: 0L
            if (System.currentTimeMillis() < nextAttempt) return
            retryState[endpointId] = System.currentTimeMillis() + RECONNECT_INTERVAL_MS
            requestConnection(endpointId, info.endpointName)
        }

        override fun onEndpointLost(endpointId: String) {
            discovered.remove(endpointId)
        }
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type != Payload.Type.BYTES) return
            val bytes = payload.asBytes() ?: return
            scope.launch { handleFrame(endpointId, bytes) }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {
            // Byte payloads are delivered atomically; nothing to do.
        }
    }

    // -------------------------------------------------------------- packet input

    private suspend fun handleFrame(endpointId: String, frame: ByteArray) {
        val packet = try {
            PacketCodec.decode(frame)
        } catch (e: PacketFormatException) {
            Log.w(TAG, "Dropping malformed frame: ${e.message}")
            return
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Dropping oversized frame", e)
            return
        }
        val header = packet.header
        val now = System.currentTimeMillis()

        if (!FrameRouting.acceptable(header, myDeviceId, now)) return
        val duplicate = !seen.markSeen(header.packetId, now) || (
            seenPacketDao?.let { dao ->
                runCatching {
                    dao.insertIfAbsent(SeenPacketEntity(header.packetId, now))
                }.getOrDefault(1L) == -1L
            } ?: false
            )

        when (FrameRouting.decide(header, myDeviceId, now, duplicate)) {
            FrameAction.DROP -> Unit
            FrameAction.HELLO -> handleHello(endpointId, packet.body)
            FrameAction.ACK_FOR_ME -> handler?.onAck(header, packet.body)
            FrameAction.DUPLICATE_FOR_ME -> handler?.onDuplicate(header, packet.body)
            FrameAction.DELIVER -> handler?.onPacket(header, packet.body)
            FrameAction.RELAY -> relay(endpointId, header, packet.body)
            FrameAction.DELIVER_AND_RELAY -> {
                handler?.onPacket(header, packet.body)
                relay(endpointId, header, packet.body)
            }
        }
    }

    private suspend fun relay(fromEndpointId: String, header: PacketHeader, body: ByteArray) {
        handler?.onRelay(header, body)
        broadcast(RoutingPolicy.forwarded(header), body, exceptEndpointId = fromEndpointId)
    }

    private suspend fun handleHello(endpointId: String, body: ByteArray) {
        val hello = try {
            json.decodeFromString(HelloPayload.serializer(), String(body, Charsets.UTF_8))
        } catch (e: Exception) {
            Log.w(TAG, "Bad HELLO from $endpointId", e)
            return
        }
        if (hello.deviceId.isBlank() || hello.deviceId == myDeviceId) return
        if (hello.protocol != MESH_PROTOCOL_VERSION) {
            Log.w(TAG, "Peer speaks protocol ${hello.protocol}, ours is $MESH_PROTOCOL_VERSION")
            return
        }

        // The bundle is checked before the device id is bound to this link. A HELLO is not signed,
        // so the id in it is only a claim; an endpoint that presents a different identity key for
        // a device we already know must not take over that device's route (it would receive -
        // and could silently discard - everything queued for the real one).
        try {
            val bundle = Base64.decode(hello.bundle, Base64.NO_WRAP)
            crypto.processPeerBundle(hello.deviceId, bundle)
        } catch (e: IdentityChangedException) {
            Log.w(TAG, "Identity changed for ${hello.deviceId}; link not trusted")
            return
        } catch (e: Exception) {
            Log.e(TAG, "Could not process pre-key bundle from ${hello.deviceId}", e)
        }

        // A device may reconnect with a new endpoint id; keep a single mapping per device.
        peers[endpointId] = PeerInfo(endpointId, hello.deviceId, hello.name, System.currentTimeMillis())
        endpointByDevice[hello.deviceId] = endpointId
        publishPeers()

        handler?.onPeerReady(peers[endpointId] ?: return)
    }

    // -------------------------------------------------------------- packet output

    private suspend fun sendHello(endpointId: String) {
        if (myDeviceId.isEmpty()) refreshIdentity()
        val identity = helloIdentity ?: return
        val bundle = helloBundle ?: return
        val hello = HelloPayload(
            deviceId = myDeviceId,
            name = myName,
            identityKey = identity,
            registrationId = crypto.registrationId(),
            bundle = bundle
        )
        val body = json.encodeToString(HelloPayload.serializer(), hello).toByteArray(Charsets.UTF_8)
        val header = PacketHeader(
            packetId = java.util.UUID.randomUUID().toString(),
            type = PacketType.HELLO,
            from = myDeviceId,
            to = null,
            msgId = "",
            createdAt = System.currentTimeMillis(),
            expiresAt = System.currentTimeMillis() + 60_000L,
            ttl = 1
        )
        sendFrame(endpointId, header, body)
    }

    /** @return true when the payload left the device. */
    override fun sendTo(deviceId: String, header: PacketHeader, body: ByteArray): Boolean {
        val endpointId = endpointByDevice[deviceId] ?: return false
        return sendFrame(endpointId, header, body)
    }

    override fun broadcast(header: PacketHeader, body: ByteArray, exceptEndpointId: String?): Int {
        var sent = 0
        for (endpointId in peers.keys) {
            if (endpointId == exceptEndpointId) continue
            if (sendFrame(endpointId, header, body)) sent++
        }
        return sent
    }

    private fun sendFrame(endpointId: String, header: PacketHeader, body: ByteArray): Boolean {
        val frame = try {
            PacketCodec.encode(header, body)
        } catch (e: Exception) {
            Log.e(TAG, "Frame too large for the transport", e)
            return false
        }
        return try {
            val now = System.currentTimeMillis()
            seen.markSeen(header.packetId, now)
            seenPacketDao?.let { dao ->
                scope.launch { runCatching { dao.insertIfAbsent(SeenPacketEntity(header.packetId, now)) } }
            }
            client.sendPayload(endpointId, Payload.fromBytes(frame))
                .addOnFailureListener { e -> Log.w(TAG, "sendPayload to $endpointId failed", e) }
            true
        } catch (e: Exception) {
            Log.e(TAG, "sendPayload threw", e)
            false
        }
    }

    override fun isConnected(deviceId: String): Boolean = endpointByDevice.containsKey(deviceId)

    override fun connectedDeviceIds(): Set<String> = endpointByDevice.keys.toSet()

    fun disconnect(deviceId: String) {
        val endpointId = endpointByDevice[deviceId] ?: return
        try {
            client.disconnectFromEndpoint(endpointId)
        } catch (e: Exception) {
            Log.w(TAG, "disconnect failed", e)
        }
        peers.remove(endpointId)
        endpointByDevice.remove(deviceId)
        publishPeers()
    }

    private fun publishPeers() {
        _peerFlow.value = peers.values.sortedBy { it.displayName.lowercase() }
    }

    private companion object {
        const val TAG = "MeshManager"

        /** Must be <= 32 characters and only contain letters, digits, '-' and '_'. */
        const val SERVICE_ID = "com.offlinemesh.mesh.v1"
        const val WATCHDOG_INTERVAL_MS = 15_000L
        const val RECONNECT_INTERVAL_MS = 20_000L
    }
}
