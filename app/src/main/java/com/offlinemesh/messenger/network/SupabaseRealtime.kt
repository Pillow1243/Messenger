package com.offlinemesh.messenger.network

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.atomic.AtomicLong

/**
 * WebSocket subscription to Postgres changes.
 *
 * Realtime is treated as a *hint*, never as the source of truth: every change is persisted, and a
 * full backfill runs on each (re)connection, so a dropped socket can never lose a message. The
 * client reconnects with exponential backoff, sends heartbeats, and rejoins with a fresh access
 * token when the session is refreshed.
 */
class SupabaseRealtime(private val http: SupabaseHttp) {

    enum class State { STOPPED, CONNECTING, JOINED, RECONNECTING, FAILED }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val refs = AtomicLong(0)

    private val _changes = MutableSharedFlow<RealtimeProtocol.Change>(
        extraBufferCapacity = 512,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST
    )
    val changes: SharedFlow<RealtimeProtocol.Change> = _changes.asSharedFlow()

    private val _state = MutableStateFlow(State.STOPPED)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var accessToken: String? = null
    @Volatile private var subscriptions: List<RealtimeProtocol.Subscription> = emptyList()
    @Volatile private var running = false
    @Volatile private var attempts = 0

    private var socket: WebSocket? = null
    private var heartbeatJob: Job? = null
    private var reconnectJob: Job? = null

    fun start(accessToken: String, subscriptions: List<RealtimeProtocol.Subscription>) {
        this.accessToken = accessToken
        this.subscriptions = subscriptions
        running = true
        if (socket == null) connect()
    }

    /** Called when the session token is refreshed; forces a rejoin with the new token. */
    fun updateToken(token: String) {
        if (accessToken == token) return
        accessToken = token
        if (running) {
            socket?.close(1000, "token refreshed")
            socket = null
        }
    }

    fun stop() {
        running = false
        reconnectJob?.cancel()
        heartbeatJob?.cancel()
        socket?.close(1000, "client closing")
        socket = null
        _state.value = State.STOPPED
    }

    private fun connect() {
        val token = accessToken
        val url = SupabaseConfig.realtimeUrl()
        if (token.isNullOrBlank() || url == null) {
            _state.value = State.FAILED
            return
        }
        _state.value = if (attempts == 0) State.CONNECTING else State.RECONNECTING
        val request = Request.Builder().url(url).build()
        socket = http.okHttpClient().newWebSocket(request, listener)
    }

    private fun scheduleReconnect() {
        if (!running) return
        if (reconnectJob?.isActive == true) return
        val backoff = (1_000L shl attempts.coerceAtMost(5)).coerceAtMost(30_000L)
        attempts += 1
        _state.value = State.RECONNECTING
        reconnectJob = scope.launch {
            delay(backoff)
            if (running) {
                socket = null
                connect()
            }
        }
    }

    private fun startHeartbeat(webSocket: WebSocket) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                delay(RealtimeProtocol.HEARTBEAT_INTERVAL_MS)
                runCatching { webSocket.send(RealtimeProtocol.heartbeatFrame(refs.incrementAndGet())) }
            }
        }
    }

    private val listener = object : WebSocketListener() {

        override fun onOpen(webSocket: WebSocket, response: Response) {
            attempts = 0
            val token = accessToken.orEmpty()
            subscriptions.forEach { subscription ->
                webSocket.send(RealtimeProtocol.joinFrame(subscription, token, refs.incrementAndGet()))
            }
            startHeartbeat(webSocket)
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            when (val frame = RealtimeProtocol.parse(text)) {
                is RealtimeProtocol.Frame.Changes -> frame.changes.forEach { _changes.tryEmit(it) }
                is RealtimeProtocol.Frame.Joined -> _state.value = State.JOINED
                is RealtimeProtocol.Frame.JoinFailed -> {
                    Log.w(TAG, "Realtime join failed for ${frame.topic}: ${frame.reason}")
                    // Usually an expired access token: drop the socket so the reconnect uses a
                    // freshly refreshed one.
                    webSocket.close(1008, "join failed")
                }
                is RealtimeProtocol.Frame.Other -> Unit
                null -> Log.w(TAG, "Unparseable realtime frame")
            }
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            Log.w(TAG, "Realtime socket failed: ${t.message}")
            heartbeatJob?.cancel()
            scheduleReconnect()
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            heartbeatJob?.cancel()
            scheduleReconnect()
        }
    }

    private companion object {
        const val TAG = "SupabaseRealtime"
    }
}
