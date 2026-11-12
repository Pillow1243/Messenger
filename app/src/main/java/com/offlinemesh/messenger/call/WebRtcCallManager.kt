package com.offlinemesh.messenger.call

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.offlinemesh.messenger.core.db.AppDatabase
import com.offlinemesh.messenger.core.db.CallLogEntity
import com.offlinemesh.messenger.data.ConversationType
import com.offlinemesh.messenger.data.OnlineSync
import com.offlinemesh.messenger.data.RemoteCall
import com.offlinemesh.messenger.data.RemoteCallSignal
import com.offlinemesh.messenger.data.SettingsRepository
import com.offlinemesh.messenger.data.TurnCredentialsResponse
import com.offlinemesh.messenger.service.MessageNotifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import org.webrtc.AudioSource
import org.webrtc.AudioTrack
import org.webrtc.Camera2Enumerator
import org.webrtc.CameraVideoCapturer
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Signal kind that tells the peer the call is over. It is the only kind the backend accepts for a
 * call that already reached a terminal state (`mm_call_signals_kind_check`, `mm_send_call_signal`).
 * Older builds sent "hangup", which the database rejected; it is still understood when received.
 */
private const val SIGNAL_BYE = "bye"

/**
 * Production 1:1 WebRTC Voice & Video Calling coordinator backed by `org.webrtc.*` and
 * authenticated Supabase signaling (`mm_calls` + `mm_call_signals` + `mm-turn-credentials`).
 *
 * Group calling is rejected at both the client (`ConversationType.DIRECT` guard) and database
 * (`mm_create_call` RPC) layers.
 */
class WebRtcCallManager(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val onlineProvider: () -> OnlineSync
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val stateMachine = CallStateMachine()

    private val _state = MutableStateFlow(CallSessionState())
    val state: StateFlow<CallSessionState> = _state.asStateFlow()

    private val _localVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val localVideoTrack: StateFlow<VideoTrack?> = _localVideoTrack.asStateFlow()

    private val _remoteVideoTrack = MutableStateFlow<VideoTrack?>(null)
    val remoteVideoTrack: StateFlow<VideoTrack?> = _remoteVideoTrack.asStateFlow()

    val recentCalls: Flow<List<CallLogEntity>> = db.callLogDao().observeRecent()

    private var eglBase: EglBase? = null
    private var peerFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var audioSource: AudioSource? = null
    private var localAudioTrack: AudioTrack? = null
    private var videoCapturer: CameraVideoCapturer? = null
    private var surfaceHelper: SurfaceTextureHelper? = null
    private var videoSource: VideoSource? = null

    private val pendingRemoteIce = mutableListOf<IceCandidate>()
    private var remoteDescriptionSet = false
    private var lastSignalCursorId = 0L

    private var ringTimeoutJob: Job? = null
    private var signalPollJob: Job? = null
    private var iceDisconnectJob: Job? = null
    private var savedAudioMode: Int? = null

    @Synchronized
    fun eglBaseContext(): EglBase.Context {
        val base = eglBase ?: EglBase.create().also { eglBase = it }
        return base.eglBaseContext
    }

    fun hasRequiredPermissions(isVideo: Boolean): Boolean {
        val hasMic = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) return false
        if (!isVideo) return true
        return ContextCompat.checkSelfPermission(
            context, Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    /** Starts an outgoing 1:1 voice or video call in a `DIRECT` online-linked conversation. */
    suspend fun startOutgoingCall(localConversationId: String, isVideo: Boolean): Result<String> =
        mutex.withLock {
            val conv = db.conversationDao().getById(localConversationId)
                ?: return Result.failure(IllegalArgumentException("conversation_not_found"))
            stateMachine.canStartCall(_state.value, conv.type).onFailure { return Result.failure(it) }
            if (!hasRequiredPermissions(isVideo)) {
                return Result.failure(SecurityException("call_permissions_missing"))
            }

            val online = onlineProvider()
            if (!online.isSignedIn) {
                return Result.failure(IllegalStateException("online_sign_in_required"))
            }
            val onlineConvId = online.ensureOnlineConversationIdFor(localConversationId)
                ?: return Result.failure(IllegalStateException("peer_not_linked_online"))
            val peerUserId = db.conversationDao().getById(localConversationId)?.peerUserId
                ?: conv.peerUserId
                ?: return Result.failure(IllegalStateException("missing_peer_user"))

            return try {
                val turnResp = online.fetchTurnCredentials()
                val callId = online.createRemoteCall(
                    onlineConversationId = onlineConvId,
                    kind = if (isVideo) "video" else "voice"
                )
                lastSignalCursorId = 0L
                remoteDescriptionSet = false
                pendingRemoteIce.clear()

                _state.value = stateMachine.onOutgoingCreated(
                    callId = callId,
                    localConversationId = localConversationId,
                    onlineConversationId = onlineConvId,
                    peerUserId = peerUserId,
                    peerName = conv.title,
                    isVideo = isVideo,
                    turnConfigured = turnResp.turnConfigured
                )

                configureAudioForCall(enableSpeaker = isVideo)
                setupPeerConnection(
                    callId = callId,
                    peerUserId = peerUserId,
                    isVideo = isVideo,
                    iceServers = buildIceServers(turnResp)
                )
                CallForegroundService.start(context, conv.title, isVideo)

                val offer = createOfferSdp()
                setLocalSdp(offer)
                online.sendCallSignal(
                    callId = callId,
                    toUserId = peerUserId,
                    kind = "offer",
                    payload = buildJsonObject {
                        put("sdpType", offer.type.canonicalForm())
                        put("sdp", offer.description)
                    }
                )
                online.dispatchCallWakeup(onlineConvId, callId)

                startSignalPolling(callId)
                startRingTimeout(callId)
                Result.success(callId)
            } catch (e: Exception) {
                teardownWebRtc()
                _state.value = CallSessionState()
                Result.failure(e)
            }
        }

    /** Invoked by Realtime or FCM wakeup when an incoming `mm_calls` row is observed. */
    suspend fun onIncomingCallObserved(remoteCall: RemoteCall) {
        val online = onlineProvider()
        val myId = online.currentUserId ?: return
        if (remoteCall.initiatorId == myId) return

        if (stateMachine.isTerminalRemoteState(remoteCall.state)) {
            if (_state.value.callId == remoteCall.id) {
                finishCallInternal(remoteCall.endReason ?: remoteCall.state, notifyRemote = false)
            }
            MessageNotifier.cancelCall(context, remoteCall.id)
            return
        }

        if (remoteCall.state != "ringing") return
        if (stateMachine.isExpiredRing(remoteCall.startedAtMs)) return

        val localConv = online.resolveLocalConversationForCall(remoteCall.conversationId, remoteCall.initiatorId)
            ?: return
        if (localConv.type != ConversationType.DIRECT) return

        val isVideo = remoteCall.kind.equals("video", ignoreCase = true)
        val next = mutex.withLock {
            stateMachine.onIncomingOffer(
                current = _state.value,
                callId = remoteCall.id,
                localConversationId = localConv.id,
                onlineConversationId = remoteCall.conversationId,
                peerUserId = remoteCall.initiatorId,
                peerName = localConv.title,
                isVideo = isVideo
            )
        }

        if (next == null) {
            runCatching { online.updateRemoteCallState(remoteCall.id, "declined", "busy") }
            return
        }

        _state.value = next
        startSignalPolling(remoteCall.id)
        startRingTimeout(remoteCall.id)

        val currentSettings = runCatching { settings.snapshot() }.getOrNull()
        MessageNotifier.notifyIncomingCall(
            context = context,
            callId = remoteCall.id,
            conversationId = localConv.id,
            callerName = localConv.title,
            isVideo = isVideo,
            settings = currentSettings
        )
    }

    /** Invoked when a remote `mm_calls` UPDATE arrives via Realtime. */
    suspend fun onRemoteCallUpdated(remoteCall: RemoteCall) {
        val current = _state.value
        if (current.callId != remoteCall.id || !current.isInProgress) return
        if (stateMachine.isTerminalRemoteState(remoteCall.state)) {
            MessageNotifier.cancelCall(context, remoteCall.id)
            finishCallInternal(remoteCall.endReason ?: remoteCall.state, notifyRemote = false)
        }
    }

    /** Accepts a ringing incoming call once microphone (+ camera for video) permissions are granted. */
    suspend fun acceptIncomingCall(callId: String): Result<Unit> = mutex.withLock {
        val current = _state.value
        if (current.callId != callId || current.phase != CallPhase.INCOMING_RINGING) {
            return Result.failure(IllegalStateException("call_no_longer_ringing"))
        }
        if (!hasRequiredPermissions(current.isVideo)) {
            return Result.failure(SecurityException("call_permissions_missing"))
        }
        MessageNotifier.cancelCall(context, callId)
        ringTimeoutJob?.cancel()

        val online = onlineProvider()
        return try {
            val turnResp = online.fetchTurnCredentials()
            _state.value = stateMachine.onConnecting(current, turnConfigured = turnResp.turnConfigured)

            configureAudioForCall(enableSpeaker = current.isVideo)
            remoteDescriptionSet = false
            pendingRemoteIce.clear()
            setupPeerConnection(
                callId = callId,
                peerUserId = current.peerUserId,
                isVideo = current.isVideo,
                iceServers = buildIceServers(turnResp)
            )
            CallForegroundService.start(context, current.peerName, current.isVideo)

            val signals = online.fetchCallSignals(callId, 0L)
            val offerSig = signals.lastOrNull { it.kind == "offer" && it.fromUserId != online.currentUserId }
                ?: throw IllegalStateException("missing_call_offer")
            stateMachine.markSignalProcessed(offerSig.id)
            val sdpText = offerSig.payload.strField("sdp")
                ?: throw IllegalStateException("empty_offer_sdp")
            setRemoteSdp(SessionDescription(SessionDescription.Type.OFFER, sdpText))
            remoteDescriptionSet = true

            signals.filter { it.kind == "ice" && it.fromUserId != online.currentUserId }.forEach { sig ->
                if (stateMachine.markSignalProcessed(sig.id)) {
                    applyRemoteIcePayload(sig.payload)
                }
            }
            if (signals.isNotEmpty()) {
                lastSignalCursorId = maxOf(lastSignalCursorId, signals.maxOf { it.id })
            }

            val answer = createAnswerSdp()
            setLocalSdp(answer)
            online.sendCallSignal(
                callId = callId,
                toUserId = current.peerUserId,
                kind = "answer",
                payload = buildJsonObject {
                    put("sdpType", answer.type.canonicalForm())
                    put("sdp", answer.description)
                }
            )
            online.updateRemoteCallState(callId, "active", null)
            startSignalPolling(callId)
            Result.success(Unit)
        } catch (e: Exception) {
            runCatching { online.updateRemoteCallState(callId, "failed", "setup_failed") }
            finishCallInternal("failed", notifyRemote = false)
            Result.failure(e)
        }
    }

    suspend fun declineIncomingCall(callId: String) {
        MessageNotifier.cancelCall(context, callId)
        val online = onlineProvider()
        val peerId = _state.value.takeIf { it.callId == callId }?.peerUserId
            ?: runCatching { online.fetchRemoteCall(callId)?.initiatorId }.getOrNull()
        runCatching {
            online.updateRemoteCallState(callId, "declined", "declined")
            if (!peerId.isNullOrBlank()) {
                online.sendCallSignal(
                    callId = callId,
                    toUserId = peerId,
                    kind = SIGNAL_BYE,
                    payload = buildJsonObject { put("reason", "declined") }
                )
            }
        }
        if (_state.value.callId == callId) {
            finishCallInternal("declined", notifyRemote = false)
        }
    }

    suspend fun endCall(reason: String = "hangup") {
        val current = _state.value
        if (!current.isInProgress) {
            if (current.phase == CallPhase.ENDED) _state.value = CallSessionState()
            return
        }
        MessageNotifier.cancelCall(context, current.callId)
        finishCallInternal(reason, notifyRemote = true)
    }

    fun dismissEndedBanner() {
        if (_state.value.phase == CallPhase.ENDED) {
            _state.value = CallSessionState()
        }
    }

    fun toggleMicMute() {
        val current = _state.value
        if (!current.isInProgress) return
        val nextMuted = !current.micMuted
        localAudioTrack?.setEnabled(!nextMuted)
        _state.value = current.copy(micMuted = nextMuted)
    }

    fun toggleCamera() {
        val current = _state.value
        if (!current.isInProgress || !current.isVideo) return
        val nextEnabled = !current.cameraEnabled
        _localVideoTrack.value?.setEnabled(nextEnabled)
        _state.value = current.copy(cameraEnabled = nextEnabled)
    }

    fun switchCamera() {
        val capturer = videoCapturer ?: return
        capturer.switchCamera(object : CameraVideoCapturer.CameraSwitchHandler {
            override fun onCameraSwitchDone(isFrontCamera: Boolean) {
                val cur = _state.value
                if (cur.isInProgress) {
                    _state.value = cur.copy(usingFrontCamera = isFrontCamera)
                }
            }
            override fun onCameraSwitchError(errorDescription: String?) = Unit
        })
    }

    fun toggleSpeakerphone() {
        val current = _state.value
        if (!current.isInProgress) return
        val nextSpeaker = !current.speakerEnabled
        applySpeakerphone(nextSpeaker)
        _state.value = current.copy(speakerEnabled = nextSpeaker)
    }

    /** Processes a single incoming signal row from Realtime or polling. */
    suspend fun onRemoteSignalReceived(signal: RemoteCallSignal) {
        val online = onlineProvider()
        if (signal.fromUserId == online.currentUserId) return
        val current = _state.value
        if (current.callId != signal.callId || !current.isInProgress) return
        if (!stateMachine.markSignalProcessed(signal.id)) return
        lastSignalCursorId = maxOf(lastSignalCursorId, signal.id)

        when (signal.kind) {
            "answer" -> {
                ringTimeoutJob?.cancel()
                val sdp = signal.payload.strField("sdp") ?: return
                runCatching {
                    _state.value = stateMachine.onConnecting(_state.value)
                    setRemoteSdp(SessionDescription(SessionDescription.Type.ANSWER, sdp))
                    remoteDescriptionSet = true
                    pendingRemoteIce.forEach { peerConnection?.addIceCandidate(it) }
                    pendingRemoteIce.clear()
                }
            }
            "offer", "renegotiate" -> {
                if (current.phase == CallPhase.ACTIVE || current.phase == CallPhase.RECONNECTING) {
                    val sdp = signal.payload.strField("sdp") ?: return
                    runCatching {
                        setRemoteSdp(SessionDescription(SessionDescription.Type.OFFER, sdp))
                        val answer = createAnswerSdp()
                        setLocalSdp(answer)
                        online.sendCallSignal(
                            callId = current.callId,
                            toUserId = current.peerUserId,
                            kind = "answer",
                            payload = buildJsonObject {
                                put("sdpType", answer.type.canonicalForm())
                                put("sdp", answer.description)
                            }
                        )
                    }
                }
            }
            "ice" -> applyRemoteIcePayload(signal.payload)
            "hangup", SIGNAL_BYE -> {
                val reason = signal.payload.strField("reason") ?: "ended"
                finishCallInternal(reason, notifyRemote = false)
            }
        }
    }

    private fun applyRemoteIcePayload(payload: JsonObject) {
        val sdp = payload.strField("candidate") ?: return
        val mid = payload.strField("sdpMid") ?: "0"
        val mLine = (payload["sdpMLineIndex"] as? JsonPrimitive)?.intOrNull ?: 0
        val candidate = IceCandidate(mid, mLine, sdp)
        if (remoteDescriptionSet) {
            peerConnection?.addIceCandidate(candidate)
        } else {
            pendingRemoteIce += candidate
        }
    }

    private fun startRingTimeout(callId: String) {
        ringTimeoutJob?.cancel()
        ringTimeoutJob = scope.launch {
            delay(CallStateMachine.RING_TIMEOUT_MS)
            val cur = _state.value
            if (cur.callId == callId &&
                (cur.phase == CallPhase.OUTGOING_RINGING || cur.phase == CallPhase.INCOMING_RINGING)
            ) {
                MessageNotifier.cancelCall(context, callId)
                if (cur.isOutgoing) {
                    runCatching { onlineProvider().updateRemoteCallState(callId, "missed", "no_answer") }
                }
                finishCallInternal("missed", notifyRemote = false)
            }
        }
    }

    private fun startSignalPolling(callId: String) {
        signalPollJob?.cancel()
        signalPollJob = scope.launch {
            while (isActive && _state.value.callId == callId && _state.value.isInProgress) {
                runCatching {
                    val online = onlineProvider()
                    val remoteCall = online.fetchRemoteCall(callId)
                    if (remoteCall != null && stateMachine.isTerminalRemoteState(remoteCall.state)) {
                        onRemoteCallUpdated(remoteCall)
                        return@launch
                    }
                    if (_state.value.phase != CallPhase.INCOMING_RINGING) {
                        val signals = online.fetchCallSignals(callId, lastSignalCursorId)
                        signals.forEach { onRemoteSignalReceived(it) }
                    }
                }
                delay(1_800L)
            }
        }
    }

    private suspend fun finishCallInternal(reason: String, notifyRemote: Boolean) {
        ringTimeoutJob?.cancel()
        signalPollJob?.cancel()
        iceDisconnectJob?.cancel()

        val previous = _state.value
        if (!previous.isInProgress) return
        val ended = stateMachine.onEnded(previous, reason)
        _state.value = ended

        if (notifyRemote && previous.callId.isNotBlank()) {
            val online = onlineProvider()
            val mappedState = when (reason) {
                "declined" -> "declined"
                "missed" -> "missed"
                "failed", "network_failed", "connection_lost" -> "failed"
                else -> "ended"
            }
            scope.launch {
                runCatching {
                    online.updateRemoteCallState(previous.callId, mappedState, reason)
                    if (previous.peerUserId.isNotBlank()) {
                        online.sendCallSignal(
                            callId = previous.callId,
                            toUserId = previous.peerUserId,
                            kind = SIGNAL_BYE,
                            payload = buildJsonObject { put("reason", reason) }
                        )
                    }
                }
            }
        }

        runCatching {
            db.callLogDao().upsert(
                CallLogEntity(
                    callId = previous.callId.ifBlank { UUID.randomUUID().toString() },
                    conversationId = previous.localConversationId,
                    peerUserId = previous.peerUserId,
                    peerName = previous.peerName,
                    kind = if (previous.isVideo) "video" else "voice",
                    direction = if (previous.isOutgoing) "outgoing" else "incoming",
                    state = reason,
                    startedAt = previous.startedAtMs,
                    answeredAt = previous.connectedAtMs,
                    endedAt = System.currentTimeMillis(),
                    durationSec = ended.durationSec
                )
            )
        }

        teardownWebRtc()
        restoreAudioMode()
        CallForegroundService.stop(context)

        scope.launch {
            delay(2_200L)
            if (_state.value.callId == ended.callId && _state.value.phase == CallPhase.ENDED) {
                _state.value = CallSessionState()
            }
        }
    }

    @Synchronized
    private fun ensureFactory(): PeerConnectionFactory {
        peerFactory?.let { return it }
        PeerConnectionFactory.initialize(
            PeerConnectionFactory.InitializationOptions.builder(context.applicationContext)
                .setEnableInternalTracer(false)
                .createInitializationOptions()
        )
        val eglCtx = eglBaseContext()
        val audioModule = JavaAudioDeviceModule.builder(context.applicationContext)
            .setUseHardwareAcousticEchoCanceler(true)
            .setUseHardwareNoiseSuppressor(true)
            .createAudioDeviceModule()
        val encoderFactory = DefaultVideoEncoderFactory(eglCtx, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglCtx)
        val factory = PeerConnectionFactory.builder()
            .setAudioDeviceModule(audioModule)
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
        audioModule.release()
        peerFactory = factory
        return factory
    }

    private fun setupPeerConnection(
        callId: String,
        peerUserId: String,
        isVideo: Boolean,
        iceServers: List<PeerConnection.IceServer>
    ) {
        teardownWebRtc()
        val factory = ensureFactory()
        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val pc = factory.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(newState: PeerConnection.SignalingState?) = Unit

            override fun onIceConnectionChange(newState: PeerConnection.IceConnectionState?) {
                when (newState) {
                    PeerConnection.IceConnectionState.CONNECTED,
                    PeerConnection.IceConnectionState.COMPLETED -> {
                        iceDisconnectJob?.cancel()
                        ringTimeoutJob?.cancel()
                        _state.value = stateMachine.onIceConnected(_state.value)
                    }
                    PeerConnection.IceConnectionState.DISCONNECTED -> {
                        _state.value = stateMachine.onIceDisconnected(_state.value)
                        scheduleIceRestartOrFail(callId, peerUserId)
                    }
                    PeerConnection.IceConnectionState.FAILED -> {
                        scope.launch { finishCallInternal("network_failed", notifyRemote = true) }
                    }
                    else -> Unit
                }
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) = Unit
            override fun onIceGatheringChange(newState: PeerConnection.IceGatheringState?) = Unit

            override fun onIceCandidate(candidate: IceCandidate?) {
                if (candidate == null) return
                scope.launch {
                    runCatching {
                        onlineProvider().sendCallSignal(
                            callId = callId,
                            toUserId = peerUserId,
                            kind = "ice",
                            payload = buildJsonObject {
                                put("candidate", candidate.sdp)
                                put("sdpMid", candidate.sdpMid ?: "0")
                                put("sdpMLineIndex", candidate.sdpMLineIndex)
                            }
                        )
                    }
                }
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) = Unit
            override fun onAddStream(stream: MediaStream?) = Unit
            override fun onRemoveStream(stream: MediaStream?) = Unit
            override fun onDataChannel(dataChannel: DataChannel?) = Unit
            override fun onRenegotiationNeeded() = Unit

            override fun onAddTrack(receiver: RtpReceiver?, mediaStreams: Array<out MediaStream>?) {
                val track = receiver?.track()
                if (track is VideoTrack) {
                    track.setEnabled(true)
                    _remoteVideoTrack.value = track
                }
            }
        }) ?: throw IllegalStateException("webrtc_peer_connection_unavailable")

        peerConnection = pc

        val audioConstraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("googEchoCancellation", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googAutoGainControl", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("googNoiseSuppression", "true"))
        }
        val aSource = factory.createAudioSource(audioConstraints).also { audioSource = it }
        val aTrack = factory.createAudioTrack("ARDAMSa0", aSource).also {
            it.setEnabled(true)
            localAudioTrack = it
        }
        pc.addTrack(aTrack, listOf("ARDAMS"))

        if (isVideo) {
            val enumerator = Camera2Enumerator(context)
            val camName = enumerator.deviceNames.firstOrNull { enumerator.isFrontFacing(it) }
                ?: enumerator.deviceNames.firstOrNull()
            if (camName != null) {
                val capturer = enumerator.createCapturer(camName, null) as? CameraVideoCapturer
                if (capturer != null) {
                    val helper = SurfaceTextureHelper.create("WebRtcCamThread", eglBaseContext())
                    val vSource = factory.createVideoSource(capturer.isScreencast)
                    capturer.initialize(helper, context.applicationContext, vSource.capturerObserver)
                    runCatching { capturer.startCapture(1280, 720, 30) }
                    val vTrack = factory.createVideoTrack("ARDAMSv0", vSource).apply { setEnabled(true) }
                    pc.addTrack(vTrack, listOf("ARDAMS"))

                    videoCapturer = capturer
                    surfaceHelper = helper
                    videoSource = vSource
                    _localVideoTrack.value = vTrack
                }
            }
        }
    }

    private fun scheduleIceRestartOrFail(callId: String, peerUserId: String) {
        iceDisconnectJob?.cancel()
        iceDisconnectJob = scope.launch {
            delay(4_000L)
            val cur = _state.value
            if (cur.callId != callId || cur.phase != CallPhase.RECONNECTING) return@launch
            if (cur.isOutgoing) {
                runCatching {
                    val constraints = MediaConstraints().apply {
                        mandatory.add(MediaConstraints.KeyValuePair("IceRestart", "true"))
                    }
                    val restartOffer = createOfferSdp(constraints)
                    setLocalSdp(restartOffer)
                    onlineProvider().sendCallSignal(
                        callId = callId,
                        toUserId = peerUserId,
                        kind = "renegotiate",
                        payload = buildJsonObject {
                            put("sdpType", restartOffer.type.canonicalForm())
                            put("sdp", restartOffer.description)
                        }
                    )
                }
            }
            delay(14_000L)
            if (_state.value.callId == callId && _state.value.phase == CallPhase.RECONNECTING) {
                finishCallInternal("connection_lost", notifyRemote = true)
            }
        }
    }

    private fun buildIceServers(turn: TurnCredentialsResponse): List<PeerConnection.IceServer> {
        if (turn.iceServers.isEmpty()) {
            return listOf(
                PeerConnection.IceServer.builder(
                    listOf("stun:stun.cloudflare.com:3478", "stun:stun.l.google.com:19302")
                ).createIceServer()
            )
        }
        return turn.iceServers.mapNotNull { entry ->
            if (entry.urls.isEmpty()) return@mapNotNull null
            PeerConnection.IceServer.builder(entry.urls).apply {
                if (!entry.username.isNullOrBlank()) setUsername(entry.username)
                if (!entry.credential.isNullOrBlank()) setPassword(entry.credential)
            }.createIceServer()
        }
    }

    private suspend fun createOfferSdp(constraints: MediaConstraints = defaultSdpConstraints()): SessionDescription =
        suspendCancellableCoroutine { cont ->
            val pc = peerConnection ?: run {
                cont.resumeWithException(IllegalStateException("no_peer_connection"))
                return@suspendCancellableCoroutine
            }
            pc.createOffer(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    if (desc != null) cont.resume(desc)
                    else cont.resumeWithException(IllegalStateException("empty_offer"))
                }
                override fun onSetSuccess() = Unit
                override fun onCreateFailure(error: String?) {
                    cont.resumeWithException(IllegalStateException(error ?: "offer_failed"))
                }
                override fun onSetFailure(error: String?) = Unit
            }, constraints)
        }

    private suspend fun createAnswerSdp(): SessionDescription =
        suspendCancellableCoroutine { cont ->
            val pc = peerConnection ?: run {
                cont.resumeWithException(IllegalStateException("no_peer_connection"))
                return@suspendCancellableCoroutine
            }
            pc.createAnswer(object : SdpObserver {
                override fun onCreateSuccess(desc: SessionDescription?) {
                    if (desc != null) cont.resume(desc)
                    else cont.resumeWithException(IllegalStateException("empty_answer"))
                }
                override fun onSetSuccess() = Unit
                override fun onCreateFailure(error: String?) {
                    cont.resumeWithException(IllegalStateException(error ?: "answer_failed"))
                }
                override fun onSetFailure(error: String?) = Unit
            }, defaultSdpConstraints())
        }

    private suspend fun setLocalSdp(desc: SessionDescription) =
        suspendCancellableCoroutine<Unit> { cont ->
            val pc = peerConnection ?: run {
                cont.resumeWithException(IllegalStateException("no_peer_connection"))
                return@suspendCancellableCoroutine
            }
            pc.setLocalDescription(object : SdpObserver {
                override fun onCreateSuccess(p0: SessionDescription?) = Unit
                override fun onSetSuccess() { cont.resume(Unit) }
                override fun onCreateFailure(p0: String?) = Unit
                override fun onSetFailure(error: String?) {
                    cont.resumeWithException(IllegalStateException(error ?: "set_local_sdp_failed"))
                }
            }, desc)
        }

    private suspend fun setRemoteSdp(desc: SessionDescription) =
        suspendCancellableCoroutine<Unit> { cont ->
            val pc = peerConnection ?: run {
                cont.resumeWithException(IllegalStateException("no_peer_connection"))
                return@suspendCancellableCoroutine
            }
            pc.setRemoteDescription(object : SdpObserver {
                override fun onCreateSuccess(p0: SessionDescription?) = Unit
                override fun onSetSuccess() { cont.resume(Unit) }
                override fun onCreateFailure(p0: String?) = Unit
                override fun onSetFailure(error: String?) {
                    cont.resumeWithException(IllegalStateException(error ?: "set_remote_sdp_failed"))
                }
            }, desc)
        }

    private fun defaultSdpConstraints(): MediaConstraints = MediaConstraints().apply {
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", if (_state.value.isVideo) "true" else "false"))
    }

    private fun configureAudioForCall(enableSpeaker: Boolean) {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        if (savedAudioMode == null) savedAudioMode = am.mode
        runCatching { am.mode = AudioManager.MODE_IN_COMMUNICATION }
        applySpeakerphone(enableSpeaker)
    }

    @Suppress("DEPRECATION")
    private fun applySpeakerphone(enable: Boolean) {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (enable) {
                    val speaker = am.availableCommunicationDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
                    }
                    if (speaker != null) am.setCommunicationDevice(speaker)
                } else {
                    am.clearCommunicationDevice()
                }
            } else {
                am.isSpeakerphoneOn = enable
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun restoreAudioMode() {
        val am = context.getSystemService(AudioManager::class.java) ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                am.clearCommunicationDevice()
            } else {
                am.isSpeakerphoneOn = false
            }
            savedAudioMode?.let { am.mode = it }
            savedAudioMode = null
        }
    }

    private fun teardownWebRtc() {
        _localVideoTrack.value = null
        _remoteVideoTrack.value = null

        runCatching { videoCapturer?.stopCapture() }
        runCatching { videoCapturer?.dispose() }
        videoCapturer = null

        runCatching { videoSource?.dispose() }
        videoSource = null

        runCatching { surfaceHelper?.dispose() }
        surfaceHelper = null

        runCatching { localAudioTrack?.dispose() }
        localAudioTrack = null

        runCatching { audioSource?.dispose() }
        audioSource = null

        runCatching { peerConnection?.close() }
        runCatching { peerConnection?.dispose() }
        peerConnection = null
    }

    private fun JsonObject.strField(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull
}
