package com.offlinemesh.messenger.core.mesh

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionsClient
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.Strategy
import com.offlinemesh.messenger.core.crypto.CryptoManager
import com.offlinemesh.messenger.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

interface MeshPacketHandler {
    suspend fun onPacketReceived(packet: MeshPacket, fromEndpoint: String)
    suspend fun onPeerHello(peerDeviceId: String, displayName: String, endpointId: String)
}

data class PeerInfo(
    val endpointId: String,
    val deviceId: String,
    val displayName: String,
    val lastSeenAt: Long
)

class MeshManager(
    private val context: Context,
    private val settings: SettingsRepository,
    private val crypto: CryptoManager
) {
    private val client: ConnectionsClient = Nearby.getConnectionsClient(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val json = Json { ignoreUnknownKeys = true }
    private var handler: MeshPacketHandler? = null

    private val peers = ConcurrentHashMap<String, PeerInfo>()
    private val endpointToDevice = ConcurrentHashMap<String, String>()

    private val _peersFlow = MutableStateFlow<List<PeerInfo>>(emptyList())
    val peersFlow: StateFlow<List<PeerInfo>> = _peersFlow.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private var myDeviceId: String = ""
    private var myDisplayName: String = ""

    fun setPacketHandler(handler: MeshPacketHandler) {
        this.handler = handler
    }

    suspend fun initIdentity() {
        myDeviceId = settings.getDeviceId()
        myDisplayName = settings.getDisplayName()
    }

    fun refreshIdentity() {
        scope.launch {
            myDeviceId = settings.getDeviceId()
            myDisplayName = settings.getDisplayName()
        }
    }

    fun start() {
        if (_isRunning.value) return
        if (myDeviceId.isEmpty()) {
            scope.launch { initIdentity(); startInternal() }
        } else {
            startInternal()
        }
    }

    private fun startInternal() {
        if (_isRunning.value) return
        try {
            client.startAdvertising(
                myDisplayName.ifEmpty { "Mesh" },
                SERVICE_ID,
                advertisingCallback,
                AdvertisingOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
            )
            client.startDiscovery(
                SERVICE_ID,
                discoveryCallback,
                DiscoveryOptions.Builder().setStrategy(Strategy.P2P_CLUSTER).build()
            )
            _isRunning.value = true
            Log.i(TAG, "Mesh started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start mesh", e)
        }
    }

    fun stop() {
        try {
            client.stopAdvertising()
            client.stopDiscovery()
            client.stopAllEndpoints()
        } catch (e: Exception) {
            Log.e(TAG, "Stop error", e)
        }
        peers.clear()
        endpointToDevice.clear()
        refreshPeers()
        _isRunning.value = false
    }

    fun sendPacket(packet: MeshPacket, endpointId: String? = null) {
        val bytes = json.encodeToString(packet).toByteArray(Charsets.UTF_8)
        val payload = Payload(bytes)
        if (endpointId != null) {
            try {
                client.sendPayload(endpointId, payload)
            } catch (e: Exception) {
                Log.e(TAG, "Send failed to $endpointId", e)
            }
        } else {
            for (endpoint in peers.keys) {
                try {
                    client.sendPayload(endpoint, payload)
                } catch (e: Exception) {
                    Log.e(TAG, "Broadcast send failed to $endpoint", e)
                }
            }
        }
    }

    fun sendToDevice(packet: MeshPacket, deviceId: String): Boolean {
        val endpoint = endpointToDevice.entries.firstOrNull { it.value == deviceId }?.key
            ?: return false
        sendPacket(packet, endpoint)
        return true
    }

    fun broadcast(packet: MeshPacket) {
        sendPacket(packet, null)
    }

    private fun refreshPeers() {
        _peersFlow.value = peers.values.toList()
    }

    private fun sendHello(endpointId: String) {
        try {
            val hello = HelloInfo(
                displayName = myDisplayName,
                identityKey = Base64.encodeToString(crypto.getIdentityPublicKeyBytes(), Base64.NO_WRAP),
                prekeyBundle = com.offlinemesh.messenger.core.crypto.PreKeyBundleCodec.encode(crypto.buildPreKeyBundle())
            )
            val packet = MeshPacket(
                packetId = java.util.UUID.randomUUID().toString(),
                messageId = "",
                fromDeviceId = myDeviceId,
                toDeviceId = null,
                groupId = null,
                type = PacketType.HELLO,
                ttl = MeshPacket.DEFAULT_TTL,
                hopCount = 0,
                timestamp = System.currentTimeMillis(),
                payload = json.encodeToString(hello)
            )
            sendPacket(packet, endpointId)
        } catch (e: Exception) {
            Log.e(TAG, "HELLO failed", e)
        }
    }

    private fun onPayloadReceived(endpointId: String, payload: Payload) {
        val bytes = payload.asBytes() ?: return
        val text = String(bytes, Charsets.UTF_8)
        val packet: MeshPacket = try {
            json.decodeFromString(text)
        } catch (e: Exception) {
            Log.e(TAG, "Bad packet", e)
            return
        }
        scope.launch {
            if (packet.type == PacketType.HELLO) {
                try {
                    val hello: HelloInfo = json.decodeFromString(packet.payload)
                    val deviceId = packet.fromDeviceId
                    endpointToDevice[endpointId] = deviceId
                    peers[endpointId] = PeerInfo(endpointId, deviceId, hello.displayName, System.currentTimeMillis())
                    refreshPeers()
                    try {
                        crypto.processIdentity(deviceId, Base64.decode(hello.identityKey, Base64.NO_WRAP))
                    } catch (e: Exception) {
                        Log.e(TAG, "Identity process failed", e)
                    }
                    try {
                        crypto.processPreKeyBundle(deviceId, hello.prekeyBundle)
                    } catch (e: Exception) {
                        Log.e(TAG, "PreKeyBundle process failed", e)
                    }
                    handler?.onPeerHello(deviceId, hello.displayName, endpointId)
                } catch (e: Exception) {
                    Log.e(TAG, "HELLO parse failed", e)
                }
            } else {
                handler?.onPacketReceived(packet, endpointId)
                if (packet.toDeviceId != null && packet.toDeviceId != myDeviceId && packet.ttl > 0) {
                    val forwarded = packet.forwarded()
                    for (other in peers.keys) {
                        if (other != endpointId) {
                            sendPacket(forwarded, other)
                        }
                    }
                }
            }
        }
    }

    private val advertisingCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            client.acceptConnection(endpointId, connectionCallback)
        }

        override fun onConnectionResult(endpointId: String, result: com.google.android.gms.nearby.connection.ConnectionResult) {
            if (result.status.isSuccess) {
                sendHello(endpointId)
            } else {
                Log.w(TAG, "Advertising connection failed: ${result.status.statusMessage}")
            }
        }

        override fun onDisconnected(endpointId: String) {
            peers.remove(endpointId)
            endpointToDevice.remove(endpointId)
            refreshPeers()
        }
    }

    private val discoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointDiscovered(endpointId: String, info: DiscoveredEndpointInfo) {
            try {
                client.requestConnection(info.endpointName, endpointId, connectionCallback)
            } catch (e: Exception) {
                Log.e(TAG, "requestConnection failed", e)
            }
        }

        override fun onEndpointUndiscovered(endpointId: String) {
        }
    }

    private val connectionCallback = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            client.acceptConnection(endpointId, connectionCallback)
        }

        override fun onConnectionResult(endpointId: String, result: com.google.android.gms.nearby.connection.ConnectionResult) {
            if (result.status.isSuccess) {
                sendHello(endpointId)
            } else {
                Log.w(TAG, "Connection failed: ${result.status.statusMessage}")
            }
        }

        override fun onDisconnected(endpointId: String) {
            peers.remove(endpointId)
            endpointToDevice.remove(endpointId)
            refreshPeers()
        }
    }

    companion object {
        private const val TAG = "MeshManager"
        private const val SERVICE_ID = "com.offlinemesh.messenger.mesh"
    }
}
