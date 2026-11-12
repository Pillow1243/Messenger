package com.offlinemesh.messenger.data

import android.content.Context
import android.util.Base64
import android.util.Log
import com.offlinemesh.messenger.BuildConfig
import com.offlinemesh.messenger.call.WebRtcCallManager
import com.offlinemesh.messenger.core.crypto.CryptoManager
import com.offlinemesh.messenger.core.crypto.DuplicateCiphertextException
import com.offlinemesh.messenger.core.crypto.E2eeMessagePayload
import com.offlinemesh.messenger.core.crypto.IdentityChangedException
import com.offlinemesh.messenger.core.crypto.NoSessionYetException
import com.offlinemesh.messenger.core.crypto.OnlinePayloadCodec
import com.offlinemesh.messenger.core.db.AppDatabase
import com.offlinemesh.messenger.core.db.ConversationEntity
import com.offlinemesh.messenger.core.db.GroupSkdmStateEntity
import com.offlinemesh.messenger.core.db.MessageEntity
import com.offlinemesh.messenger.core.db.OutboxEntity
import com.offlinemesh.messenger.core.db.PeerDeviceEntity
import com.offlinemesh.messenger.core.db.PeerProfileEntity
import com.offlinemesh.messenger.core.db.SyncStateEntity
import com.offlinemesh.messenger.core.security.SecretStore
import com.offlinemesh.messenger.domain.Backoff
import com.offlinemesh.messenger.domain.EditPolicy
import com.offlinemesh.messenger.domain.HybridReconciler
import com.offlinemesh.messenger.domain.OnlineState
import com.offlinemesh.messenger.network.AuthException
import com.offlinemesh.messenger.network.PostgrestClient
import com.offlinemesh.messenger.network.RealtimeProtocol
import com.offlinemesh.messenger.network.SupabaseAuth
import com.offlinemesh.messenger.network.SupabaseConfig
import com.offlinemesh.messenger.network.SupabaseException
import com.offlinemesh.messenger.network.SupabaseHttp
import com.offlinemesh.messenger.network.SupabaseRealtime
import com.offlinemesh.messenger.push.FirebasePushInitializer
import com.offlinemesh.messenger.push.ValidatedPushWakeup
import com.offlinemesh.messenger.service.MessageNotifier
import com.offlinemesh.messenger.work.MediaTransferWorker
import com.offlinemesh.messenger.work.OutboxWorker
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import okhttp3.HttpUrl
import java.io.File
import java.util.Locale
import java.util.UUID

/** Result of one online delivery attempt. */
data class OnlineSendResult(val ok: Boolean, val remoteId: String?, val error: String?) {
    companion object {
        fun success(remoteId: String) = OnlineSendResult(true, remoteId, null)
        fun failure(error: String) = OnlineSendResult(false, null, error)
    }
}

object OnlineError {
    const val NETWORK = "network"
    const val NOT_LINKED = "not_linked"
    const val NOT_SIGNED_IN = "not_signed_in"
    const val IDENTITY_CHANGED = "identity_changed"
    const val BLOCKED = "blocked"
    const val RATE_LIMITED = "rate_limited"
    const val NO_DEVICES = "no_devices"
    const val DEVICE_LIMIT_REACHED = "device_limit_reached"
    const val DEVICE_REVOKED = "device_revoked"
    const val FORBIDDEN = "forbidden"
    const val UNKNOWN = "unknown"
}

/**
 * The online E2EE transport coordinator:
 *  - Authenticated Supabase session + Realtime WebSocket subscriptions + reconnect backfill.
 *  - Multi-device identity/pre-key registration (5-device cap), signed/Kyber pre-key rotation,
 *    remote revocation, and self-revocation detection.
 *  - Pairwise Signal PQXDH/Double-Ratchet envelopes (`v1`) AND audited Signal Sender Keys (`v2`)
 *    group encryption with automatic epoch rotation on member removal / device revocation.
 *  - Streaming AES-256-GCM encrypted media uploads/downloads via private `mm-media` bucket.
 *  - Zero-plaintext FCM wakeup registration/dispatch + 1:1 WebRTC call signaling bridge.
 */
class OnlineSync(
    private val context: Context,
    private val db: AppDatabase,
    private val crypto: CryptoManager,
    private val settings: SettingsRepository
) {
    private val secrets = SecretStore(context, "messenger_auth_v1")
    private val http = SupabaseHttp()
    val auth = SupabaseAuth(http, secrets)
    private val rest = PostgrestClient(http, auth)
    private val api = OnlineApi(rest)
    private val realtime = SupabaseRealtime(http)
    private val connectivity = com.offlinemesh.messenger.network.ConnectivityMonitor(context)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sendMutex = Mutex()
    private val activationMutex = Mutex()

    private val conversationsDao = db.conversationDao()
    private val messagesDao = db.messageDao()
    private val contactsDao = db.contactDao()
    private val outboxDao = db.outboxDao()
    private val syncDao = db.syncStateDao()
    private val peersDao = db.peerDao()
    private val groupSkdmDao = db.groupSkdmDao()

    private val _state = MutableStateFlow<OnlineState>(OnlineState.SignedOut)
    val state: StateFlow<OnlineState> = _state.asStateFlow()

    private val _typing = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val typing: StateFlow<Map<String, List<String>>> = _typing.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _blockedUsers = MutableStateFlow<Set<String>>(emptySet())
    val blockedUsersFlow: StateFlow<Set<String>> = _blockedUsers.asStateFlow()

    private var outboxJob: Job? = null
    private var keepAliveJob: Job? = null
    private var changeJob: Job? = null

    @Volatile private var deviceRowId: String? = null
    @Volatile private var myUserId: String? = null
    @Volatile private var started = false
    @Volatile private var subscribedToken: String? = null
    @Volatile private var callManager: WebRtcCallManager? = null
    @Volatile private var fileRepo: FileRepository? = null

    val currentUserId: String? get() = myUserId ?: auth.currentUserId()
    val currentDeviceRowId: String? get() = deviceRowId

    fun attachCallManager(manager: WebRtcCallManager) {
        callManager = manager
    }

    fun attachFiles(files: FileRepository) {
        fileRepo = files
    }

    // ------------------------------------------------------------------ lifecycle

    fun start() {
        if (started) return
        started = true
        FirebasePushInitializer.ensureInitialized(context)
        connectivity.start()
        scope.launch {
            connectivity.online.collect { online ->
                if (online && auth.session.value != null) {
                    runCatching { activate() }.onFailure { Log.w(TAG, "activate failed", it) }
                    runCatching { drainOutbox() }
                } else if (!online && auth.session.value != null) {
                    _state.value = OnlineState.Offline
                }
            }
        }
        scope.launch {
            auth.session.collect { session ->
                if (session == null) {
                    _state.value = OnlineState.SignedOut
                    realtime.stop()
                } else {
                    myUserId = session.userId
                    runCatching { activate() }
                }
            }
        }
        scope.launch {
            auth.sessionExpired.collect { expired ->
                if (expired) {
                    _lastError.value = OnlineError.NOT_SIGNED_IN
                    realtime.stop()
                    _state.value = OnlineState.SignedOut
                    auth.acknowledgeSessionExpired()
                }
            }
        }
    }

    fun stop() {
        started = false
        outboxJob?.cancel()
        keepAliveJob?.cancel()
        changeJob?.cancel()
        realtime.stop()
        connectivity.stop()
    }

    val isSignedIn: Boolean get() = auth.session.value != null

    val networkAvailable: Boolean get() = connectivity.online.value

    fun networkFlow(): StateFlow<Boolean> = connectivity.online

    private suspend fun activate() = activationMutex.withLock {
        val session = auth.session.value ?: return@withLock
        myUserId = session.userId
        _state.value = OnlineState.Connecting
        try {
            ensureRegistered()
            rotatePreKeysIfDue(force = false)
            refreshConversations()
            runCatching { _blockedUsers.value = api.blockedUserIds(session.userId) }
            backfillAll()
            val token = auth.validAccessToken() ?: run {
                _state.value = OnlineState.SignedOut
                return
            }
            subscribe(token)
            _state.value = OnlineState.Connected(session.userId, session.email)
            startBackgroundWork()
            scope.launch {
                runCatching { syncPushToken() }
                runCatching { syncNotificationPrefs(settings.snapshot()) }
                runCatching { checkPendingIncomingCalls(session.userId) }
            }
        } catch (e: SupabaseException) {
            val reason = reasonOf(e)
            if (reason == OnlineError.DEVICE_REVOKED) {
                _lastError.value = OnlineError.DEVICE_REVOKED
                signOutLocalOnly()
                return@withLock
            }
            _lastError.value = if (e.code == 0) null else reason
            _state.value = if (e.code == 0) OnlineState.Offline else OnlineState.Error(reason)
        } catch (e: Exception) {
            Log.w(TAG, "Online bootstrap failed", e)
            _state.value = OnlineState.Error(reasonOf(e))
        }
    }

    // ------------------------------------------------------------------- auth (UI)

    suspend fun signUp(email: String, password: String, displayName: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val session = auth.signUp(email, password)
                myUserId = session.userId
                linkDeviceAndProfile(displayName)
                activate()
            }.onFailure { _lastError.value = reasonOf(it) }
        }

    suspend fun signIn(email: String, password: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val session = auth.signIn(email, password)
            myUserId = session.userId
            ensureRegistered(force = true)
            activate()
        }.onFailure {
            val reason = reasonOf(it)
            _lastError.value = reason
            if (reason == OnlineError.DEVICE_LIMIT_REACHED || reason == OnlineError.DEVICE_REVOKED) {
                runCatching { auth.signOut() }
            }
            _state.value = if ((it as? SupabaseException)?.code == 0) OnlineState.Offline else OnlineState.SignedOut
        }
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        realtime.stop()
        outboxJob?.cancel()
        runCatching { callManager?.endCall("signed_out") }
        runCatching { api.revokePushToken(settings.deviceId()) }
        runCatching { FirebasePushInitializer.deleteLocalToken(context) }
        MessageNotifier.cancelAll(context)
        runCatching { auth.signOut() }
        syncDao.remove(KEY_DEVICE_ROW)
        syncDao.remove(KEY_REGISTERED_AT)
        deviceRowId = null
        myUserId = null
        subscribedToken = null
        _state.value = OnlineState.SignedOut
    }

    private suspend fun signOutLocalOnly() {
        realtime.stop()
        outboxJob?.cancel()
        MessageNotifier.cancelAll(context)
        runCatching { auth.signOut() }
        syncDao.remove(KEY_DEVICE_ROW)
        syncDao.remove(KEY_REGISTERED_AT)
        deviceRowId = null
        myUserId = null
        subscribedToken = null
        _state.value = OnlineState.SignedOut
    }

    /**
     * Permanently deletes the server account (including storage objects, push tokens, devices, and
     * `auth.users` via `mm-account-delete` / `mm_delete_me`) and wipes local online state & files.
     */
    suspend fun deleteAccount(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            runCatching { callManager?.endCall("account_deleted") }
            runCatching { api.revokePushToken(settings.deviceId()) }
            api.deleteAccount()
            signOutLocalOnly()
            outboxDao.clear()
            syncDao.clear()
            peersDao.clearDevices()
            peersDao.clearProfiles()
            groupSkdmDao.clear()
            db.callLogDao().clear()
            fileRepo?.cleanupStaleTempFiles(0L)
            Unit
        }.onFailure { _lastError.value = reasonOf(it) }
    }

    fun clearError() {
        _lastError.value = null
    }

    suspend fun updateProfile(displayName: String, username: String? = null) =
        withContext(Dispatchers.IO) {
            if (!isSignedIn) return@withContext
            runCatching { api.ensureProfile(displayName, username) }
        }

    // --------------------------------------------------- device bootstrap & key rotation

    private suspend fun linkDeviceAndProfile(displayName: String) {
        val name = displayName.ifBlank { settings.snapshot().displayName.ifBlank { "Messenger" } }
        api.ensureProfile(name, null)
        ensureRegistered(force = true)
    }

    private suspend fun ensureRegistered(force: Boolean = false) {
        val userId = myUserId ?: auth.currentUserId() ?: return
        val myDevId = settings.deviceId()

        // Verify this installation was not remotely revoked by another device on the same account.
        val cached = syncDao.get(KEY_DEVICE_ROW)
        if (cached != null && !force) {
            val remoteSelf = runCatching { api.deviceByRowId(cached) }.getOrNull()
            if (remoteSelf?.revokedAtMs != null) {
                throw SupabaseException(403, "device_revoked", "device_revoked")
            }
        }

        val replenished = crypto.replenishPreKeysIfLow()
        val lastRegistered = syncDao.get(KEY_REGISTERED_AT)?.toLongOrNull() ?: 0L
        val stale = System.currentTimeMillis() - lastRegistered > REGISTRATION_TTL_MS
        val preKeysLow = replenished || crypto.oneTimePreKeyCount() < MIN_PREKEYS
        if (!force && cached != null && !stale && !preKeysLow) {
            deviceRowId = cached
            api.touchLastSeen()
            return
        }
        val bundle = crypto.publishedBundle()
        val preKeys = crypto.preKeyPublics()
        val rowId = api.registerDevice(
            ownerUserId = userId,
            deviceId = myDevId,
            deviceName = android.os.Build.MODEL ?: "Android device",
            bundle = bundle,
            preKeys = preKeys
        )
        deviceRowId = rowId
        if (cached != rowId) syncDao.put(SyncStateEntity(KEY_DEVICE_ROW, rowId))
        syncDao.put(SyncStateEntity(KEY_REGISTERED_AT, System.currentTimeMillis().toString()))
        api.touchLastSeen()
    }

    /** Periodically rotates signed & Kyber-1024 pre-keys while keeping prior keys locally for 30d. */
    suspend fun rotatePreKeysIfDue(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (!isSignedIn) return@withContext false
        val rotated = runCatching { crypto.rotateSignedAndKyberPreKeysIfDue(force = force) }.getOrNull()
            ?: return@withContext false
        runCatching {
            api.rotateSignedPreKeys(settings.deviceId(), rotated.first, rotated.second)
            true
        }.getOrDefault(false)
    }

    // --------------------------------------------------------- push notifications & wakeups

    suspend fun syncPushToken() = withContext(Dispatchers.IO) {
        if (!isSignedIn) return@withContext
        val token = secrets.get(KEY_FCM_TOKEN)?.takeIf { it.isNotBlank() }
            ?: FirebasePushInitializer.fetchCurrentToken(context)
            ?: return@withContext
        secrets.put(KEY_FCM_TOKEN, token)
        runCatching {
            api.registerPushToken(
                deviceId = settings.deviceId(),
                provider = "fcm",
                token = token,
                appId = BuildConfig.APPLICATION_ID,
                locale = Locale.getDefault().language.ifBlank { "en" }
            )
            FirebasePushInitializer.markRegistered()
        }.onFailure { Log.w(TAG, "Push token registration failed", it) }
    }

    suspend fun onNewFcmToken(token: String) = withContext(Dispatchers.IO) {
        if (token.isBlank()) return@withContext
        secrets.put(KEY_FCM_TOKEN, token)
        if (isSignedIn) syncPushToken()
    }

    suspend fun syncNotificationPrefs(appSettings: AppSettings) = withContext(Dispatchers.IO) {
        val uid = currentUserId ?: return@withContext
        runCatching {
            api.syncNotificationPrefs(
                userId = uid,
                enabled = appSettings.notificationsEnabled,
                directEnabled = appSettings.notifyDirect,
                groupEnabled = appSettings.notifyGroups,
                channelEnabled = appSettings.notifyChannels,
                callsEnabled = appSettings.notifyCalls
            )
        }
    }

    /** Hydrates encrypted content locally after a zero-plaintext FCM `mm_wakeup` arrives. */
    suspend fun handlePushWakeup(wakeup: ValidatedPushWakeup) = withContext(Dispatchers.IO) {
        val uid = currentUserId ?: return@withContext
        when (wakeup.eventKind) {
            "call" -> {
                val remoteCall = runCatching { api.callById(wakeup.targetId) }.getOrNull() ?: return@withContext
                callManager?.onIncomingCallObserved(remoteCall)
            }
            "message" -> {
                val localConvId = ensureLocalConversation(wakeup.conversationId)
                val conv = conversationsDao.getById(localConvId) ?: return@withContext
                backfill(conv, uid)
            }
        }
    }

    private suspend fun checkPendingIncomingCalls(myUid: String) {
        val active = runCatching { api.activeIncomingCalls(myUid) }.getOrDefault(emptyList())
        active.firstOrNull()?.let { callManager?.onIncomingCallObserved(it) }
    }

    // ------------------------------------------------------------------ subscriptions

    private fun subscribe(token: String) {
        val userId = myUserId ?: return
        if (subscribedToken == token && realtime.state.value != SupabaseRealtime.State.STOPPED) return
        subscribedToken = token
        realtime.start(
            token,
            listOf(
                RealtimeProtocol.Subscription("mm_message_envelopes", "recipient_user_id=eq.$userId"),
                RealtimeProtocol.Subscription("mm_sender_key_distributions", "recipient_user_id=eq.$userId"),
                RealtimeProtocol.Subscription("mm_messages"),
                RealtimeProtocol.Subscription("mm_conversations"),
                RealtimeProtocol.Subscription("mm_message_receipts"),
                RealtimeProtocol.Subscription("mm_typing"),
                RealtimeProtocol.Subscription("mm_reactions"),
                RealtimeProtocol.Subscription("mm_calls"),
                RealtimeProtocol.Subscription("mm_call_signals", "to_user=eq.$userId")
            )
        )
        if (changeJob?.isActive != true) {
            changeJob = scope.launch {
                realtime.changes.collect { change ->
                    runCatching { handleChange(change) }
                        .onFailure { Log.w(TAG, "Realtime change failed: ${change.table}", it) }
                }
            }
        }
    }

    private suspend fun handleChange(change: RealtimeProtocol.Change) {
        val userId = myUserId ?: return
        when (change.table) {
            "mm_message_envelopes" -> if (change.type == "INSERT") {
                val messageId = change.record.str("message_id") ?: return
                val ciphertext = change.record.str("ciphertext") ?: return
                ingestEnvelope(messageId, ciphertext)
            }

            "mm_sender_key_distributions" -> if (change.type == "INSERT") {
                val id = (change.record["id"] as? JsonPrimitive)?.longOrNull ?: return
                val convId = change.record.str("conversation_id") ?: return
                val epoch = (change.record["epoch"] as? JsonPrimitive)?.intOrNull ?: 1
                val distId = change.record.str("distribution_id") ?: return
                val senderUid = change.record.str("sender_user_id") ?: return
                val senderDevRow = change.record.str("sender_device") ?: return
                val recipientDevRow = change.record.str("recipient_device")
                if (recipientDevRow != null && deviceRowId != null && recipientDevRow != deviceRowId) return
                val cipherType = (change.record["cipher_type"] as? JsonPrimitive)?.intOrNull ?: 3
                val ciphertext = change.record.str("ciphertext") ?: return
                processIncomingSkdm(
                    RemoteSkdm(id, convId, epoch, distId, senderUid, senderDevRow, cipherType, ciphertext)
                )
            }

            "mm_messages" -> if (change.type == "INSERT") {
                onMessageRow(change.record)
            } else if (change.type == "UPDATE") {
                val remoteId = change.record.str("id") ?: return
                if (!change.record.str("deleted_at").isNullOrBlank()) {
                    messagesDao.getByRemoteId(remoteId)?.let { local ->
                        messagesDao.markDeleted(local.id, System.currentTimeMillis())
                    }
                }
            }

            "mm_conversations" -> if (change.type == "UPDATE") {
                val onlineConvId = change.record.str("id") ?: return
                val fresh = runCatching { api.conversationById(onlineConvId) }.getOrNull() ?: return
                ensureLocalConversation(onlineConvId, knownRemote = fresh)
            }

            "mm_message_receipts" -> {
                val messageId = change.record.str("message_id") ?: return
                val reader = change.record.str("user_id") ?: return
                if (reader == userId) return
                val local = messagesDao.getByRemoteId(messageId) ?: return
                val readAt = change.record.str("read_at")?.let { parseIso(it) }
                val deliveredAt = change.record.str("delivered_at")?.let { parseIso(it) }
                when {
                    readAt != null -> {
                        messagesDao.markDelivered(
                            local.id,
                            HybridReconciler.reconcileTimestamp(local.deliveredAt, deliveredAt ?: readAt) ?: readAt
                        )
                        messagesDao.markReadAt(
                            local.id,
                            HybridReconciler.reconcileTimestamp(local.readAt, readAt) ?: readAt
                        )
                    }
                    deliveredAt != null -> {
                        messagesDao.markDelivered(
                            local.id,
                            HybridReconciler.reconcileTimestamp(local.deliveredAt, deliveredAt) ?: deliveredAt
                        )
                    }
                }
            }

            "mm_typing" -> {
                val conversationId = change.record.str("conversation_id") ?: return
                val typer = change.record.str("user_id") ?: return
                if (typer == userId) return
                locallyLinked(conversationId)?.let { localId -> markTyping(localId, typer) }
            }

            "mm_reactions" -> {
                val messageId = change.record.str("message_id") ?: return
                refreshReactions(messageId)
            }

            "mm_calls" -> {
                val remoteCall = api.callOf(change.record) ?: return
                when (change.type) {
                    "INSERT" -> callManager?.onIncomingCallObserved(remoteCall)
                    "UPDATE" -> callManager?.onRemoteCallUpdated(remoteCall)
                }
            }

            "mm_call_signals" -> if (change.type == "INSERT") {
                val sig = api.callSignalOf(change.record) ?: return
                callManager?.onRemoteSignalReceived(sig)
            }
        }
    }

    private suspend fun onMessageRow(record: JsonObject) {
        val senderId = record.str("sender_id") ?: return
        val clientMsgId = record.str("client_msg_id")
        val remoteId = record.str("id") ?: return
        if (senderId == myUserId && clientMsgId != null) {
            messagesDao.getByClientMsgId(clientMsgId)?.let { local ->
                val nextStatus = HybridReconciler.reconcileStatus(local.status, MessageStatus.SENT)
                messagesDao.markUploaded(local.id, remoteId, nextStatus)
            }
            return
        }
        // Group Sender Key message (`group_ciphertext` populated directly on `mm_messages`).
        val groupCiphertext = record.str("group_ciphertext")
        if (!groupCiphertext.isNullOrBlank()) {
            val remote = api.messageById(remoteId) ?: return
            ingestGroupMessage(remote)
        }
    }

    // --------------------------------------------------- SenderKey SKDM + ingest

    private suspend fun processIncomingSkdm(skdm: RemoteSkdm): Boolean {
        val senderDevice = resolveDeviceByRowId(skdm.senderDeviceRowId, skdm.senderUserId) ?: return false
        val cipherBytes = runCatching { Base64.decode(skdm.ciphertextBase64, Base64.NO_WRAP) }.getOrNull()
            ?: return false
        return try {
            crypto.decryptAndProcessSkdmFromPeer(senderDevice.deviceId, skdm.cipherType, cipherBytes)
            api.deleteSenderKeyDistribution(skdm.id)
            true
        } catch (e: DuplicateCiphertextException) {
            api.deleteSenderKeyDistribution(skdm.id)
            true
        } catch (e: IdentityChangedException) {
            markConversationError(skdm.conversationId, OnlineError.IDENTITY_CHANGED)
            false
        } catch (e: Exception) {
            Log.w(TAG, "Failed to process SKDM ${skdm.id}: ${e.message}")
            false
        }
    }

    private suspend fun pullAndProcessSkdms(onlineConversationId: String, epoch: Int) {
        val uid = myUserId ?: return
        val pending = api.senderKeyDistributionsFor(onlineConversationId, epoch, uid, deviceRowId)
        for (skdm in pending) {
            processIncomingSkdm(skdm)
        }
    }

    private suspend fun ingestGroupMessage(remote: RemoteMessage) {
        if (remote.senderId == myUserId) return
        if (_blockedUsers.value.contains(remote.senderId)) return
        if (reconcileAlreadyPresentIncoming(remote)) return

        val cipherB64 = remote.groupCiphertext ?: return
        val cipherBytes = runCatching { Base64.decode(cipherB64, Base64.NO_WRAP) }.getOrNull() ?: return

        val decrypted = try {
            crypto.decryptGroup(remote.senderDeviceId, cipherBytes)
        } catch (noKey: NoSessionYetException) {
            // Sender's SKDM for this epoch may still be sitting in `mm_sender_key_distributions`
            pullAndProcessSkdms(remote.conversationId, remote.groupCipherEpoch ?: 1)
            try {
                crypto.decryptGroup(remote.senderDeviceId, cipherBytes)
            } catch (e: Exception) {
                Log.w(TAG, "Group message ${remote.id} awaiting SKDM: ${e.message}")
                return
            }
        } catch (dup: DuplicateCiphertextException) {
            return
        } catch (e: Exception) {
            Log.w(TAG, "Could not decrypt group message ${remote.id}: ${e.message}")
            return
        }

        persistDecryptedIncoming(remote, decrypted)
    }

    private suspend fun ingestEnvelope(messageId: String, ciphertextBase64: String) {
        val remote = api.messageById(messageId) ?: return
        if (remote.senderId == myUserId) return
        if (_blockedUsers.value.contains(remote.senderId)) {
            runCatching { api.deleteEnvelope(messageId, myUserId ?: return) }
            return
        }
        if (reconcileAlreadyPresentIncoming(remote)) {
            runCatching { api.deleteEnvelope(messageId, myUserId ?: return) }
            return
        }
        val envelope = try {
            Base64.decode(ciphertextBase64, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "Bad envelope encoding for $messageId", e)
            return
        }
        val plaintext = try {
            crypto.decrypt(remote.senderDeviceId, envelope)
        } catch (dup: DuplicateCiphertextException) {
            runCatching { api.deleteEnvelope(messageId, myUserId ?: return) }
            return
        } catch (e: IdentityChangedException) {
            markConversationError(remote.conversationId, OnlineError.IDENTITY_CHANGED)
            return
        } catch (e: Exception) {
            Log.w(TAG, "Could not decrypt $messageId: ${e.message}")
            return
        }

        persistDecryptedIncoming(remote, plaintext)
        runCatching { api.deleteEnvelope(remote.id, myUserId ?: return) }
    }

    /**
     * Cross-transport deduplication: if a message already arrived over the offline mesh with the
     * same `clientMsgId` (or `remoteId`), merges its transport badge to `BOTH` and records its
     * `remoteId` without creating a duplicate row or firing a second notification.
     */
    private suspend fun reconcileAlreadyPresentIncoming(remote: RemoteMessage): Boolean {
        val existing = messagesDao.getByRemoteId(remote.id)
            ?: remote.clientMsgId.takeIf { it.isNotBlank() }?.let { cid ->
                messagesDao.getByClientMsgId(cid) ?: messagesDao.getById(cid)
            }
            ?: return false

        val mergedTransport = HybridReconciler.mergeTransport(existing.transport, "ONLINE")
        if (existing.remoteId != remote.id || existing.transport != mergedTransport) {
            messagesDao.upsert(
                existing.copy(
                    remoteId = remote.id,
                    clientMsgId = existing.clientMsgId.ifBlank { remote.clientMsgId },
                    transport = mergedTransport
                )
            )
        }
        myUserId?.let { uid -> runCatching { api.markDelivered(remote.id, uid) } }
        return true
    }

    private suspend fun persistDecryptedIncoming(remote: RemoteMessage, plaintext: ByteArray) {
        val fallbackType = when (remote.kind) {
            "voice" -> MessageType.VOICE
            "image" -> MessageType.IMAGE
            "video" -> MessageType.VIDEO
            "audio" -> MessageType.AUDIO
            "file" -> MessageType.FILE
            else -> MessageType.TEXT
        }
        val decoded = OnlinePayloadCodec.decode(plaintext, fallbackType)

        if (remote.kind == "text-edit") {
            val target = remote.meta.str("target")
            val editedAt = remote.meta["edited_at"]?.jsonPrimitive?.longOrNull ?: remote.sentAtMs
            val local = target?.let { messagesDao.getByRemoteId(it) } ?: return
            val allowed = EditPolicy.mayApply(
                targetOutgoing = local.outgoing,
                targetDeleted = local.deletedAt != null,
                targetIsText = local.type == MessageType.TEXT,
                sameConversation = local.conversationId == ensureLocalConversation(remote.conversationId),
                targetSenderDeviceId = local.senderDeviceId,
                editSenderDeviceId = remote.senderDeviceId,
                targetSenderUserId = peersDao.deviceByDeviceId(local.senderDeviceId)?.userId,
                editSenderUserId = peersDao.deviceByDeviceId(remote.senderDeviceId)?.userId
                    ?.takeIf { it == remote.senderId }
            )
            if (allowed && decoded.body.isNotBlank()) {
                messagesDao.editBody(local.id, decoded.body, editedAt)
            } else if (!allowed) {
                Log.w(TAG, "Ignored an edit that did not come from the author of ${local.id}")
            }
            return
        }

        val localConversationId = ensureLocalConversation(remote.conversationId)
        val localId = "srv:" + remote.id
        val hasRemoteMedia = !decoded.objectPath.isNullOrBlank() && !decoded.mediaKeyBase64.isNullOrBlank()
        val msgType = decoded.type.ifBlank { fallbackType }
        val displayBody = decoded.body.ifBlank {
            decoded.fileName ?: when (msgType) {
                MessageType.IMAGE -> "Photo"
                MessageType.VIDEO -> "Video"
                MessageType.VOICE -> "Voice message"
                MessageType.AUDIO -> "Audio"
                MessageType.FILE -> "Attachment"
                else -> ""
            }
        }

        val entity = MessageEntity(
            id = localId,
            conversationId = localConversationId,
            senderDeviceId = remote.senderDeviceId,
            outgoing = false,
            type = msgType,
            body = displayBody,
            timestamp = remote.sentAtMs,
            status = MessageStatus.RECEIVED,
            fileName = decoded.fileName,
            fileMime = decoded.fileMime,
            fileSize = decoded.fileSize,
            fileSha256 = decoded.fileSha256.orEmpty(),
            durationMs = decoded.durationMs,
            clientMsgId = remote.clientMsgId.ifBlank { remote.id },
            remoteId = remote.id,
            transport = "ONLINE",
            deliveredAt = System.currentTimeMillis(),
            replyToId = remote.meta.str("reply_to")?.let { remoteReply ->
                messagesDao.getByRemoteId(remoteReply)?.id
            },
            attachmentRemoteId = decoded.attachmentId ?: remote.attachmentId,
            remoteObjectPath = decoded.objectPath,
            mediaKeyBase64 = decoded.mediaKeyBase64,
            mediaIvBase64 = decoded.mediaIvBase64,
            cipherSha256 = decoded.cipherSha256,
            cipherSize = decoded.cipherSize,
            thumbnailBase64 = decoded.thumbnailBase64,
            mediaWidth = decoded.width,
            mediaHeight = decoded.height,
            transferState = if (hasRemoteMedia) MediaTransferWorker.STATE_QUEUED else null,
            transferBytes = 0L
        )
        messagesDao.upsert(entity)
        conversationsDao.updateLastMessage(
            localConversationId,
            remote.sentAtMs,
            displayBody.replace('\n', ' ').take(120),
            1
        )
        rememberSender(remote.senderId, remote.senderDeviceId)
        notifyIncoming(localConversationId, remote.senderId, displayBody)
        myUserId?.let { uid -> runCatching { api.markDelivered(remote.id, uid) } }

        // Auto-fetch voice notes and compact media (<= 4 MiB) in the background; larger videos/files
        // display their E2EE preview thumbnail + metadata and download on tap.
        if (hasRemoteMedia && (decoded.fileSize ?: 0L) in 1L..(4L * 1024 * 1024L)) {
            MediaTransferWorker.enqueueDownload(context, localId)
        }
    }

    private suspend fun notifyIncoming(conversationId: String, senderId: String, body: String) {
        val conversation = conversationsDao.getById(conversationId) ?: return
        val appSettings = runCatching { settings.snapshot() }.getOrNull()
        if (!MessageNotifier.shouldNotifyConversation(conversation, appSettings)) return
        val title = conversation.title.ifBlank { runCatching { displayNameOf(senderId) }.getOrDefault(senderId) }
        runCatching {
            MessageNotifier.notifyMessage(
                context = context,
                conversationId = conversationId,
                title = title,
                body = body,
                conversation = conversation,
                settings = appSettings
            )
        }.onFailure { Log.w(TAG, "Could not post notification", it) }
    }

    // --------------------------------------------------------------------- sending

    suspend fun deliver(message: MessageEntity, conversation: ConversationEntity): OnlineSendResult =
        withContext(Dispatchers.IO) {
            if (!isSignedIn) return@withContext OnlineSendResult.failure(OnlineError.NOT_SIGNED_IN)
            if (!SupabaseConfig.configured) return@withContext OnlineSendResult.failure(OnlineError.NOT_LINKED)
            val userId = myUserId ?: return@withContext OnlineSendResult.failure(OnlineError.NOT_SIGNED_IN)
            val onlineConversationId = conversation.onlineId
                ?: return@withContext OnlineSendResult.failure(OnlineError.NOT_LINKED)

            // Media messages wait for MediaTransferWorker to finish encrypting & uploading the blob.
            if (message.type != MessageType.TEXT && message.remoteObjectPath.isNullOrBlank()) {
                MediaTransferWorker.enqueueUpload(context, message.id)
                return@withContext OnlineSendResult.failure("media_uploading")
            }

            sendMutex.withLock {
                try {
                    if (deviceRowId == null) ensureRegistered()
                    val payloadBytes = encodeOutgoingPayload(message)

                    val latestConv = conversationsDao.getById(conversation.id) ?: conversation
                    val remote = if (latestConv.type == ConversationType.GROUP && latestConv.encryptionVersion >= 2) {
                        deliverViaSenderKeys(message, latestConv, onlineConversationId, userId, payloadBytes)
                    } else {
                        val envelopes = envelopesFor(latestConv, userId, payloadBytes)
                        if (envelopes.isEmpty()) {
                            markFailed(message.id, OnlineError.NO_DEVICES)
                            return@withLock OnlineSendResult.failure(OnlineError.NO_DEVICES)
                        }
                        val uploaded = api.uploadMessage(
                            senderUserId = userId,
                            senderDeviceId = settings.deviceId(),
                            conversationId = onlineConversationId,
                            clientMsgId = message.clientMsgId.ifBlank { message.id },
                            kind = kindOf(message),
                            meta = messageMeta(message),
                            senderDeviceRowId = deviceRowId,
                            attachmentId = message.attachmentRemoteId
                        )
                        api.putEnvelopes(envelopes.map { it.copy(messageId = uploaded.id) })
                        uploaded
                    }

                    val nextStatus = HybridReconciler.reconcileStatus(message.status, MessageStatus.SENT)
                    val mergedTransport = if (message.transport == "MESH" && message.status == MessageStatus.DELIVERED) {
                        "BOTH"
                    } else if (message.transport == "BOTH") {
                        "BOTH"
                    } else {
                        "ONLINE"
                    }
                    messagesDao.upsert(
                        message.copy(
                            remoteId = remote.id,
                            status = nextStatus,
                            transport = mergedTransport,
                            error = null
                        )
                    )
                    outboxDao.deleteForMessage(message.id)

                    // Trigger zero-plaintext remote push wakeup for offline/backgrounded recipients.
                    scope.launch {
                        api.dispatchPushWakeup(
                            eventKind = "message",
                            conversationId = onlineConversationId,
                            messageId = remote.id
                        )
                    }

                    OnlineSendResult.success(remote.id)
                } catch (e: IdentityChangedException) {
                    markFailed(message.id, OnlineError.IDENTITY_CHANGED)
                    OnlineSendResult.failure(OnlineError.IDENTITY_CHANGED)
                } catch (e: SupabaseException) {
                    val reason = reasonOf(e)
                    markFailed(message.id, reason)
                    OnlineSendResult.failure(reason)
                } catch (e: Exception) {
                    Log.w(TAG, "Online send failed", e)
                    markFailed(message.id, OnlineError.UNKNOWN)
                    OnlineSendResult.failure(OnlineError.UNKNOWN)
                }
            }
        }

    /**
     * Audited Signal Sender Keys (`v2`) group send:
     *  1. Refreshes `(senderKeyEpoch, distributionId)` so any member removal or device revocation
     *     is reflected before encrypting.
     *  2. Distributes our pairwise-encrypted `SenderKeyDistributionMessage` ONLY to active member
     *     devices that have not yet received it in `(conversationId, epoch, distributionId)`.
     *  3. Encrypts the inner payload ONCE (`O(1)`) via `GroupCipher.encrypt` and uploads a single
     *     `mm_messages` row carrying `group_ciphertext`.
     */
    private suspend fun deliverViaSenderKeys(
        message: MessageEntity,
        conversation: ConversationEntity,
        onlineConversationId: String,
        myUserId: String,
        payloadBytes: ByteArray
    ): RemoteMessage {
        val freshRemote = api.conversationById(onlineConversationId)
        val epoch = freshRemote?.senderKeyEpoch ?: conversation.senderKeyEpoch.coerceAtLeast(1)
        val distIdStr = freshRemote?.distributionId
            ?: conversation.distributionId
            ?: UUID.randomUUID().toString()
        val distUuid = runCatching { UUID.fromString(distIdStr) }.getOrElse {
            UUID.nameUUIDFromBytes(distIdStr.toByteArray(Charsets.UTF_8))
        }

        if (freshRemote != null) {
            conversationsDao.updateEncryptionState(
                id = conversation.id,
                version = freshRemote.encryptionVersion,
                epoch = epoch,
                distributionId = distUuid.toString(),
                myRole = freshRemote.memberRoles[myUserId] ?: conversation.myRole
            )
            groupSkdmDao.pruneOldEpochs(conversation.id, epoch)
        }

        val myDevId = settings.deviceId()
        val skdmBytes = crypto.createGroupDistribution(myDevId, distUuid)

        val recipientUsers = (freshRemote?.memberUserIds ?: recipientUsersOf(conversation, myUserId))
            .filter { it != myUserId }
        if (recipientUsers.isNotEmpty()) {
            // Always fetch fresh active devices when epoch advances so revoked devices are excluded.
            val epochChanged = epoch != conversation.senderKeyEpoch
            val activeDevices = devicesFor(recipientUsers, forceRefresh = epochChanged)
            val alreadySentRows = groupSkdmDao.sentDevicesForEpoch(
                conversationId = conversation.id,
                epoch = epoch,
                distributionId = distUuid.toString()
            ).toSet()

            val unsentDevices = activeDevices.filter { !it.revoked && it.id !in alreadySentRows }
            if (unsentDevices.isNotEmpty()) {
                val senderRow = deviceRowId ?: throw SupabaseException(0, "device", "Missing sender device row")
                val outgoingSkdms = ArrayList<OutgoingSkdm>(unsentDevices.size)
                for (dev in unsentDevices) {
                    if (!crypto.hasSession(dev.deviceId)) {
                        crypto.processBundleJson(dev.deviceId, api.claimBundle(dev.id))
                    }
                    val (cipherType, cipherBytes) = crypto.encryptSkdmForPeer(skdmBytes, dev.deviceId)
                    outgoingSkdms += OutgoingSkdm(
                        conversationId = onlineConversationId,
                        epoch = epoch,
                        distributionId = distUuid.toString(),
                        senderUserId = myUserId,
                        senderDeviceRowId = senderRow,
                        recipientUserId = dev.userId,
                        recipientDeviceRowId = dev.id,
                        cipherType = cipherType,
                        ciphertextBase64 = Base64.encodeToString(cipherBytes, Base64.NO_WRAP)
                    )
                }
                api.putSenderKeyDistributions(outgoingSkdms)
                val now = System.currentTimeMillis()
                for (dev in unsentDevices) {
                    groupSkdmDao.markSent(
                        GroupSkdmStateEntity(
                            conversationId = conversation.id,
                            epoch = epoch,
                            distributionId = distUuid.toString(),
                            recipientDeviceRowId = dev.id,
                            sentAt = now
                        )
                    )
                }
            }
        }

        val groupCipherBytes = crypto.encryptGroup(myDevId, distUuid, payloadBytes)
        return api.uploadMessage(
            senderUserId = myUserId,
            senderDeviceId = myDevId,
            conversationId = onlineConversationId,
            clientMsgId = message.clientMsgId.ifBlank { message.id },
            kind = kindOf(message),
            meta = messageMeta(message),
            senderDeviceRowId = deviceRowId,
            attachmentId = message.attachmentRemoteId,
            groupCiphertext = Base64.encodeToString(groupCipherBytes, Base64.NO_WRAP),
            groupCipherEpoch = epoch,
            groupDistributionId = distUuid.toString()
        )
    }

    private fun encodeOutgoingPayload(message: MessageEntity): ByteArray {
        if (message.type == MessageType.TEXT && message.remoteObjectPath.isNullOrBlank()) {
            return OnlinePayloadCodec.encodeText(message.body)
        }
        return OnlinePayloadCodec.encodeStructured(
            E2eeMessagePayload(
                type = message.type,
                body = message.body,
                fileName = message.fileName,
                fileMime = message.fileMime,
                fileSize = message.fileSize,
                fileSha256 = message.fileSha256.takeIf { it.isNotBlank() },
                cipherSha256 = message.cipherSha256,
                cipherSize = message.cipherSize,
                attachmentId = message.attachmentRemoteId,
                objectPath = message.remoteObjectPath,
                mediaKeyBase64 = message.mediaKeyBase64,
                mediaIvBase64 = message.mediaIvBase64,
                thumbnailBase64 = message.thumbnailBase64,
                width = message.mediaWidth,
                height = message.mediaHeight,
                durationMs = message.durationMs
            )
        )
    }

    private suspend fun messageMeta(message: MessageEntity): JsonObject {
        val builder = buildJsonObject { }
        val replyLocalId = message.replyToId ?: return builder
        val target = messagesDao.getById(replyLocalId) ?: return builder
        val targetRemoteId = target.remoteId ?: return builder
        return buildJsonObject { put("reply_to", targetRemoteId) }
    }

    private suspend fun envelopesFor(
        conversation: ConversationEntity,
        userId: String,
        plaintext: ByteArray
    ): List<OutgoingEnvelope> {
        val recipientUsers = recipientUsersOf(conversation, userId)
        if (recipientUsers.isEmpty()) return emptyList()
        val devices = devicesFor(recipientUsers)
        if (devices.isEmpty()) return emptyList()
        val senderRow = deviceRowId.orEmpty()
        val list = ArrayList<OutgoingEnvelope>(devices.size)
        for (device in devices) {
            if (device.revoked) continue
            if (!crypto.hasSession(device.deviceId)) {
                crypto.processBundleJson(device.deviceId, api.claimBundle(device.id))
            }
            val ciphertext = crypto.encrypt(device.deviceId, plaintext)
            list += OutgoingEnvelope(
                messageId = "",
                recipientUserId = device.userId,
                recipientDeviceRowId = device.id,
                senderDeviceRowId = senderRow,
                cipherType = ciphertext.firstOrNull()?.toInt() ?: 3,
                ciphertextBase64 = Base64.encodeToString(ciphertext, Base64.NO_WRAP)
            )
        }
        return list
    }

    private suspend fun recipientUsersOf(conversation: ConversationEntity, userId: String): List<String> {
        val cached = conversation.memberUserIds.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        if (cached.isNotEmpty()) return cached.filter { it != userId }
        val onlineId = conversation.onlineId ?: return emptyList()
        val members = api.membersOf(onlineId)
        conversationsDao.updateMembership(
            conversation.id,
            conversation.title,
            members.keys.joinedToString()
        )
        return members.keys.filter { it != userId }
    }

    private suspend fun devicesFor(
        userIds: List<String>,
        forceRefresh: Boolean = false
    ): List<PeerDeviceEntity> {
        if (!forceRefresh) {
            val cached = userIds.flatMap { peersDao.devicesFor(it) }
            if (userIds.all { uid -> cached.any { it.userId == uid } }) return cached
        }
        val remote = api.devicesOf(userIds, includeRevoked = true)
        if (remote.isEmpty()) return userIds.flatMap { peersDao.devicesFor(it) }
        val existingByRow = userIds.flatMap { peersDao.devicesFor(it) }.associateBy { it.id }
        val entities = remote.map {
            PeerDeviceEntity(
                id = it.rowId,
                userId = it.userId,
                deviceId = it.deviceId,
                identityKey = it.identityKey,
                revoked = it.revokedAtMs != null,
                lastSeenAt = it.lastSeenAtMs.takeIf { ts -> ts > 0 } ?: System.currentTimeMillis(),
                name = it.name,
                platform = it.platform,
                verified = existingByRow[it.rowId]?.verified ?: false
            )
        }
        peersDao.upsertDevices(entities)
        entities.filter { it.revoked }.forEach { groupSkdmDao.deleteForDevice(it.id) }
        return entities.filter { !it.revoked }
    }

    private suspend fun resolveDeviceByRowId(rowId: String, fallbackUserId: String): PeerDeviceEntity? {
        peersDao.devicesFor(fallbackUserId).firstOrNull { it.id == rowId }?.let { return it }
        val remote = api.deviceByRowId(rowId) ?: return null
        val entity = PeerDeviceEntity(
            id = remote.rowId,
            userId = remote.userId,
            deviceId = remote.deviceId,
            identityKey = remote.identityKey,
            revoked = remote.revokedAtMs != null,
            lastSeenAt = remote.lastSeenAtMs,
            name = remote.name,
            platform = remote.platform
        )
        peersDao.upsertDevices(listOf(entity))
        return entity
    }

    suspend fun peerDevicesForUser(userId: String, forceRefresh: Boolean = false): List<PeerDeviceEntity> =
        withContext(Dispatchers.IO) {
            if (forceRefresh && isSignedIn) {
                runCatching { devicesFor(listOf(userId), forceRefresh = true) }
            }
            peersDao.devicesFor(userId)
        }

    // -------------------------------------------------------------------- outbox

    fun enqueue(message: MessageEntity, conversation: ConversationEntity) {
        if (conversation.onlineId == null) return
        val id = "out:" + message.id
        scope.launch {
            outboxDao.upsert(
                OutboxEntity(
                    id = id,
                    kind = "MESSAGE",
                    conversationId = conversation.id,
                    messageId = message.id,
                    createdAt = System.currentTimeMillis(),
                    nextAttemptAt = 0L
                )
            )
            runCatching { drainOutbox() }
            runCatching { OutboxWorker.enqueueNow(context) }
        }
    }

    suspend fun drainOutbox(): Int = withContext(Dispatchers.IO) {
        if (!isSignedIn) return@withContext 0
        var sent = 0
        val now = System.currentTimeMillis()
        for (entry in outboxDao.due(now, 25)) {
            val message = messagesDao.getById(entry.messageId)
            val conversation = conversationsDao.getById(entry.conversationId)
            if (message == null || conversation == null || !message.outgoing) {
                outboxDao.delete(entry.id)
                continue
            }
            val result = deliver(message, conversation)
            if (result.ok) {
                sent++
            } else {
                val nextAttempt = now + backoff(entry.attempts + 1)
                outboxDao.bump(entry.id, nextAttempt, result.error)
                if (Backoff.exhausted(entry.attempts)) outboxDao.delete(entry.id)
            }
        }
        sent
    }

    private fun startBackgroundWork() {
        if (outboxJob?.isActive != true) {
            outboxJob = scope.launch {
                while (isActive) {
                    runCatching { drainOutbox() }
                    delay(OUTBOX_INTERVAL_MS)
                }
            }
        }
        if (keepAliveJob?.isActive != true) {
            keepAliveJob = scope.launch {
                while (isActive) {
                    delay(TOKEN_REFRESH_MS)
                    val token = auth.validAccessToken() ?: continue
                    realtime.updateToken(token)
                    runCatching { api.touchLastSeen() }
                    runCatching { rotatePreKeysIfDue(force = false) }
                }
            }
        }
    }

    // -------------------------------------------------------------------- backfill

    suspend fun backfillAll() = withContext(Dispatchers.IO) {
        val userId = myUserId ?: return@withContext
        for (conversation in conversationsDao.getAll().filter { it.onlineId != null }) {
            runCatching { backfill(conversation, userId) }
                .onFailure { Log.w(TAG, "Backfill failed for ${conversation.id}", it) }
        }
    }

    suspend fun backfill(conversation: ConversationEntity, userId: String? = null) {
        val uid = userId ?: myUserId ?: return
        val onlineId = conversation.onlineId ?: return

        // Pull any queued SKDMs for v2 Sender Key groups first so subsequent group messages decrypt.
        if (conversation.type == ConversationType.GROUP && conversation.encryptionVersion >= 2) {
            runCatching { pullAndProcessSkdms(onlineId, conversation.senderKeyEpoch) }
        }

        val key = "cursor:$onlineId"
        val since = syncDao.get(key)?.toLongOrNull() ?: 0L
        val remoteMessages = api.messagesSince(onlineId, since)
        if (remoteMessages.isEmpty()) return
        val incoming = remoteMessages.filter { it.senderId != uid }

        // 1. Ingest v2 Sender Key group messages directly from `group_ciphertext`
        val groupMsgs = incoming.filter { !it.groupCiphertext.isNullOrBlank() && messagesDao.getByRemoteId(it.id) == null }
        for (gm in groupMsgs) {
            runCatching { ingestGroupMessage(gm) }
                .onFailure { Log.w(TAG, "Could not ingest group msg ${gm.id}", it) }
        }

        // 2. Ingest v1 pairwise messages from `mm_message_envelopes`
        val pairwiseMissing = incoming.filter { it.groupCiphertext.isNullOrBlank() && messagesDao.getByRemoteId(it.id) == null }
        if (pairwiseMissing.isNotEmpty()) {
            val envelopes = api.envelopesFor(pairwiseMissing.map { it.id }, uid)
            envelopes.forEach { envelope ->
                runCatching { ingestEnvelope(envelope.messageId, envelope.ciphertext) }
                    .onFailure { Log.w(TAG, "Could not ingest ${envelope.messageId}", it) }
            }
        }

        // 3. Reconcile outgoing message receipts that arrived while offline
        val mySentIds = remoteMessages.filter { it.senderId == uid }.map { it.id }
        if (mySentIds.isNotEmpty()) {
            runCatching {
                val receipts = api.receiptsFor(mySentIds)
                receipts.filter { it.userId != uid }.forEach { r ->
                    val local = messagesDao.getByRemoteId(r.messageId) ?: return@forEach
                    r.deliveredAtMs?.let { dAt ->
                        messagesDao.markDelivered(
                            local.id,
                            HybridReconciler.reconcileTimestamp(local.deliveredAt, dAt) ?: dAt
                        )
                    }
                    r.readAtMs?.let { rAt ->
                        messagesDao.markDelivered(
                            local.id,
                            HybridReconciler.reconcileTimestamp(local.deliveredAt, rAt) ?: rAt
                        )
                        messagesDao.markReadAt(
                            local.id,
                            HybridReconciler.reconcileTimestamp(local.readAt, rAt) ?: rAt
                        )
                    }
                }
            }
        }

        val newest = remoteMessages.maxOf { it.sentAtMs }
        if (newest > since) syncDao.put(SyncStateEntity(key, newest.toString()))
        refreshReactionsFor(incoming.map { it.id })
    }

    suspend fun refreshConversations() = withContext(Dispatchers.IO) {
        val userId = myUserId ?: return@withContext
        val remote = api.listConversations(userId)
        if (remote.isEmpty()) return@withContext
        val profiles = api.profiles((remote.flatMap { it.memberUserIds } + remote.map { it.ownerId }).distinct())
        peersDao.upsertProfiles(
            profiles.map {
                PeerProfileEntity(
                    userId = it.userId,
                    username = it.username,
                    displayName = it.displayName,
                    avatarUrl = it.avatarUrl,
                    updatedAt = System.currentTimeMillis()
                )
            }
        )
        for (conversation in remote) {
            ensureLocalConversation(conversation.id, knownRemote = conversation)
        }
    }

    /**
     * Maps a server conversation UUID to a single unified local [ConversationEntity].
     *
     * Hybrid transition guarantee: if a direct peer already has a local offline-mesh conversation
     * (via `contacts.userId == peerUserId` or `peer_devices`), we link THAT existing mesh
     * conversation instead of spawning a duplicate `"online:$onlineId"` chat!
     */
    suspend fun ensureLocalConversation(
        onlineId: String,
        knownRemote: RemoteConversation? = null
    ): String {
        val userId = myUserId
        val remote = knownRemote ?: runCatching { api.conversationById(onlineId) }.getOrNull()
        val members = remote?.memberUserIds.orEmpty()
        val peerUserId = if (remote?.type == "direct") {
            members.firstOrNull { it != userId }
        } else {
            null
        }
        val myRole = (if (userId != null) remote?.memberRoles?.get(userId) else null) ?: "member"

        var existing = conversationsDao.getByOnlineId(onlineId)
        if (existing == null && peerUserId != null) {
            // Check if we already chatted with this user's device on the offline mesh!
            val linkedContact = contactsDao.getAll().firstOrNull { it.userId == peerUserId }
            val peerMeshDevId = linkedContact?.deviceId
                ?: peersDao.devicesFor(peerUserId).firstOrNull()?.deviceId
            if (!peerMeshDevId.isNullOrBlank()) {
                val meshConv = conversationsDao.getById(peerMeshDevId)
                if (meshConv != null && meshConv.onlineId == null) {
                    existing = meshConv
                }
            }
        }

        val title = when {
            !remote?.title.isNullOrBlank() -> remote?.title.orEmpty()
            peerUserId != null -> displayNameOf(peerUserId)
            existing != null -> existing.title
            else -> "Chat"
        }
        val localType = when (remote?.type) {
            "group" -> ConversationType.GROUP
            "channel" -> ConversationType.CHANNEL
            "direct" -> ConversationType.DIRECT
            else -> existing?.type ?: ConversationType.DIRECT
        }
        val encVersion = remote?.encryptionVersion ?: existing?.encryptionVersion ?: 1
        val epoch = remote?.senderKeyEpoch ?: existing?.senderKeyEpoch ?: 1
        val distId = remote?.distributionId ?: existing?.distributionId

        if (existing != null) {
            conversationsDao.linkOnline(existing.id, onlineId, peerUserId)
            conversationsDao.updateMembership(existing.id, title, members.joinedToString())
            conversationsDao.updateEncryptionState(existing.id, encVersion, epoch, distId, myRole)
            if (peerUserId != null) cacheProfile(peerUserId)
            return existing.id
        }

        val localId = "online:$onlineId"
        val now = System.currentTimeMillis()
        conversationsDao.upsert(
            ConversationEntity(
                id = localId,
                title = title,
                isGroup = localType != ConversationType.DIRECT,
                memberIds = "",
                createdAt = now,
                lastMessageAt = remote?.lastMessageAtMs ?: now,
                type = localType,
                onlineId = onlineId,
                peerUserId = peerUserId,
                memberUserIds = members.joinedToString(),
                encryptionVersion = encVersion,
                senderKeyEpoch = epoch,
                distributionId = distId,
                myRole = myRole
            )
        )
        if (peerUserId != null) cacheProfile(peerUserId)
        return localId
    }

    suspend fun ensureOnlineConversationIdFor(localConversationId: String): String? =
        withContext(Dispatchers.IO) {
            val conv = conversationsDao.getById(localConversationId) ?: return@withContext null
            conv.onlineId?.let { return@withContext it }
            if (!isSignedIn) return@withContext null
            val peerUserId = conv.peerUserId
                ?: conv.memberIds.split(',').map { it.trim() }.firstOrNull { it.isNotEmpty() }
                    ?.let { contactsDao.getByDeviceId(it)?.userId }
                ?: return@withContext null
            linkDirectConversation(conv, peerUserId)?.onlineId
        }

    private suspend fun locallyLinked(onlineId: String): String? =
        conversationsDao.getByOnlineId(onlineId)?.id

    private suspend fun cacheProfile(userId: String) {
        if (peersDao.profile(userId)?.displayName?.isNotBlank() == true) return
        val fetched = runCatching { api.profiles(listOf(userId)).firstOrNull() }.getOrNull() ?: return
        peersDao.upsertProfile(
            PeerProfileEntity(
                userId = fetched.userId,
                username = fetched.username,
                displayName = fetched.displayName,
                avatarUrl = fetched.avatarUrl,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    private suspend fun displayNameOf(userId: String): String {
        val cached = peersDao.profile(userId)
        if (cached != null && cached.displayName.isNotBlank()) return cached.displayName
        val fetched = api.profiles(listOf(userId)).firstOrNull() ?: return userId.take(8)
        peersDao.upsertProfile(
            PeerProfileEntity(
                userId = fetched.userId,
                username = fetched.username,
                displayName = fetched.displayName,
                avatarUrl = fetched.avatarUrl,
                updatedAt = System.currentTimeMillis()
            )
        )
        return fetched.displayName.ifBlank { fetched.username.ifBlank { userId.take(8) } }
    }

    private suspend fun rememberSender(userId: String, deviceId: String) {
        runCatching { cacheProfile(userId) }
        if (deviceId.isNotBlank()) {
            val contact = contactsDao.getByDeviceId(deviceId)
            if (contact != null && contact.userId == null) {
                contactsDao.upsert(
                    contact.copy(userId = userId, lastSeenAt = System.currentTimeMillis())
                )
            }
        }
    }

    // --------------------------------------------------------- media storage helpers

    suspend fun uploadEncryptedAttachment(
        messageId: String,
        onlineConversationId: String,
        objectPath: String,
        encryptedFile: File,
        cipherSha256: String,
        resumeTusUrl: String? = null,
        onTusUrlCreated: ((String) -> Unit)? = null,
        onProgress: ((Long, Long) -> Unit)? = null
    ): String = withContext(Dispatchers.IO) {
        val uid = currentUserId ?: throw SupabaseException(401, "auth", "Not signed in")
        val transferId = "upload:$messageId"
        api.clearCancelledTransfer(transferId)
        api.uploadEncryptedBlob(
            transferId = transferId,
            objectPath = objectPath,
            encryptedFile = encryptedFile,
            resumeTusUrl = resumeTusUrl,
            onTusUrlCreated = onTusUrlCreated,
            onProgress = onProgress
        )
        val attachmentId = UUID.randomUUID().toString()
        api.insertAttachmentRecord(
            attachmentId = attachmentId,
            conversationId = onlineConversationId,
            uploaderId = uid,
            objectPath = objectPath,
            cipherSizeBytes = encryptedFile.length(),
            cipherSha256 = cipherSha256
        )
    }

    suspend fun createSignedAttachmentUrl(objectPath: String): HttpUrl =
        withContext(Dispatchers.IO) {
            api.createSignedMediaUrl(objectPath, expiresInSeconds = 3600)
        }

    suspend fun downloadEncryptedAttachment(
        messageId: String,
        signedUrl: HttpUrl,
        destPartFile: File,
        expectedTotalBytes: Long? = null,
        onProgress: ((Long, Long) -> Unit)? = null
    ): File = withContext(Dispatchers.IO) {
        val transferId = "download:$messageId"
        api.clearCancelledTransfer(transferId)
        api.downloadEncryptedBlob(
            transferId = transferId,
            signedUrl = signedUrl,
            destPartFile = destPartFile,
            expectedTotalBytes = expectedTotalBytes,
            onProgress = onProgress
        )
    }

    fun cancelMediaTransfer(messageId: String) {
        api.cancelTransfer("upload:$messageId")
        api.cancelTransfer("download:$messageId")
        MediaTransferWorker.cancel(context, messageId)
    }

    // --------------------------------------------------------- WebRTC call signaling helpers

    suspend fun fetchTurnCredentials(): TurnCredentialsResponse =
        withContext(Dispatchers.IO) { api.fetchTurnCredentials() }

    suspend fun createRemoteCall(onlineConversationId: String, kind: String): String =
        withContext(Dispatchers.IO) { api.createCall(onlineConversationId, kind) }

    suspend fun updateRemoteCallState(callId: String, state: String, endReason: String?) =
        withContext(Dispatchers.IO) { api.setCallState(callId, state, endReason) }

    suspend fun sendCallSignal(callId: String, toUserId: String, kind: String, payload: JsonObject) =
        withContext(Dispatchers.IO) {
            api.sendCallSignal(
                callId = callId,
                toUserId = toUserId,
                kind = kind,
                payload = payload,
                clientSignalId = UUID.randomUUID().toString()
            )
        }

    suspend fun fetchRemoteCall(callId: String): RemoteCall? =
        withContext(Dispatchers.IO) { api.callById(callId) }

    suspend fun fetchCallSignals(callId: String, afterId: Long): List<RemoteCallSignal> =
        withContext(Dispatchers.IO) {
            val uid = currentUserId ?: return@withContext emptyList()
            api.callSignalsSince(callId, uid, afterId)
        }

    suspend fun dispatchCallWakeup(onlineConversationId: String, callId: String) =
        withContext(Dispatchers.IO) {
            api.dispatchPushWakeup("call", onlineConversationId, callId = callId)
        }

    suspend fun resolveLocalConversationForCall(
        onlineConversationId: String,
        callerUserId: String
    ): ConversationEntity? = withContext(Dispatchers.IO) {
        if (_blockedUsers.value.contains(callerUserId)) return@withContext null
        val localId = ensureLocalConversation(onlineConversationId)
        conversationsDao.getById(localId)
    }

    // ------------------------------------------------------------------ typing/read/reactions

    suspend fun notifyTyping(conversation: ConversationEntity) = withContext(Dispatchers.IO) {
        val userId = myUserId ?: return@withContext
        val onlineId = conversation.onlineId ?: return@withContext
        runCatching { api.setTyping(onlineId, userId) }
    }

    suspend fun markRead(conversation: ConversationEntity, message: MessageEntity) =
        withContext(Dispatchers.IO) {
            val userId = myUserId ?: return@withContext
            val onlineId = conversation.onlineId ?: return@withContext
            val remoteId = message.remoteId ?: return@withContext
            runCatching { api.markRead(onlineId, remoteId) }
            val now = System.currentTimeMillis()
            messagesDao.markReadAt(message.id, now)
            messagesDao.markDelivered(message.id, now)
            runCatching { api.markDelivered(remoteId, userId) }
        }

    suspend fun addReaction(message: MessageEntity, emoji: String) = withContext(Dispatchers.IO) {
        val userId = myUserId ?: return@withContext
        val remoteId = message.remoteId ?: return@withContext
        runCatching { api.addReaction(remoteId, userId, emoji) }
        refreshReactions(remoteId)
    }

    suspend fun removeReaction(message: MessageEntity, emoji: String) = withContext(Dispatchers.IO) {
        val userId = myUserId ?: return@withContext
        val remoteId = message.remoteId ?: return@withContext
        runCatching { api.removeReaction(remoteId, userId, emoji) }
        refreshReactions(remoteId)
    }

    private suspend fun refreshReactions(messageId: String) {
        val local = messagesDao.getByRemoteId(messageId) ?: return
        val reactions = api.reactionsFor(listOf(messageId))
        val summary = reactions.groupingBy { it.emoji }.eachCount()
            .entries.sortedByDescending { it.value }
            .joinToString(",") { "${it.key}:${it.value}" }
        messagesDao.setReactions(local.id, summary)
    }

    private suspend fun refreshReactionsFor(messageIds: List<String>) {
        if (messageIds.isEmpty()) return
        val reactions = api.reactionsFor(messageIds)
        reactions.groupBy { it.messageId }.forEach { (messageId, list) ->
            val local = messagesDao.getByRemoteId(messageId) ?: return@forEach
            val summary = list.groupingBy { it.emoji }.eachCount()
                .entries.sortedByDescending { it.value }
                .joinToString(",") { "${it.key}:${it.value}" }
            messagesDao.setReactions(local.id, summary)
        }
    }

    // --------------------------------------------------- social, groups, devices & blocks

    suspend fun searchUsers(query: String): List<RemoteProfile> = withContext(Dispatchers.IO) {
        runCatching { api.searchUsers(query) }.getOrDefault(emptyList())
    }

    suspend fun searchChannels(query: String): List<RemoteChannel> = withContext(Dispatchers.IO) {
        runCatching { api.searchChannels(query) }.getOrDefault(emptyList())
    }

    suspend fun linkDirectConversation(
        conversation: ConversationEntity,
        peerUserId: String
    ): ConversationEntity? = withContext(Dispatchers.IO) {
        if (!isSignedIn) return@withContext null
        val onlineId = runCatching { api.startDirectConversation(peerUserId) }.getOrNull()
            ?: return@withContext null
        conversationsDao.linkOnline(conversation.id, onlineId, peerUserId)
        conversationsDao.getById(conversation.id)
    }

    suspend fun openDirectChat(peerUserId: String): String? = withContext(Dispatchers.IO) {
        if (!isSignedIn) return@withContext null
        val onlineId = runCatching { api.startDirectConversation(peerUserId) }.getOrNull()
            ?: return@withContext null
        ensureLocalConversation(onlineId)
    }

    suspend fun createGroup(title: String, memberUserIds: List<String>): String? =
        withContext(Dispatchers.IO) {
            if (!isSignedIn) return@withContext null
            val onlineId = runCatching { api.createGroup(title, memberUserIds) }.getOrNull()
                ?: return@withContext null
            val fresh = runCatching { api.conversationById(onlineId) }.getOrNull()
            ensureLocalConversation(onlineId, knownRemote = fresh)
        }

    suspend fun addGroupMembers(conversation: ConversationEntity, userIds: List<String>): Boolean =
        withContext(Dispatchers.IO) {
            val onlineId = conversation.onlineId ?: return@withContext false
            val ok = runCatching { api.addGroupMembers(onlineId, userIds) > 0 }.getOrDefault(false)
            if (ok) {
                val fresh = runCatching { api.conversationById(onlineId) }.getOrNull()
                ensureLocalConversation(onlineId, knownRemote = fresh)
            }
            ok
        }

    /**
     * Removes a member from a group and immediately synchronizes the server's rotated
     * `(sender_key_epoch, distribution_id)` while pruning prior SKDM send state so the removed
     * member cannot decrypt any future group messages.
     */
    suspend fun removeGroupMember(conversation: ConversationEntity, memberUserId: String): Boolean =
        withContext(Dispatchers.IO) {
            val onlineId = conversation.onlineId ?: return@withContext false
            val removed = runCatching { api.removeGroupMember(onlineId, memberUserId) }.getOrDefault(false)
            if (removed) {
                val fresh = runCatching { api.conversationById(onlineId) }.getOrNull()
                ensureLocalConversation(onlineId, knownRemote = fresh)
                val newEpoch = fresh?.senderKeyEpoch ?: (conversation.senderKeyEpoch + 1)
                groupSkdmDao.pruneOldEpochs(conversation.id, newEpoch)
            }
            removed
        }

    /** Explicitly upgrades a legacy `v1` pairwise group to `v2` Signal Sender Keys. */
    suspend fun upgradeGroupToSenderKeys(conversation: ConversationEntity): Boolean =
        withContext(Dispatchers.IO) {
            val onlineId = conversation.onlineId ?: return@withContext false
            val upgraded = runCatching { api.upgradeGroupEncryption(onlineId) }.getOrNull()
                ?: return@withContext false
            conversationsDao.updateEncryptionState(
                id = conversation.id,
                version = 2,
                epoch = upgraded.first,
                distributionId = upgraded.second,
                myRole = conversation.myRole
            )
            groupSkdmDao.pruneOldEpochs(conversation.id, upgraded.first)
            true
        }

    suspend fun setConversationMuted(
        conversation: ConversationEntity,
        muted: Boolean,
        mutedUntilMs: Long? = null
    ) = withContext(Dispatchers.IO) {
        conversationsDao.setMuted(conversation.id, muted, mutedUntilMs)
        val uid = currentUserId ?: return@withContext
        val onlineId = conversation.onlineId ?: return@withContext
        runCatching { api.setConversationMute(onlineId, uid, muted, mutedUntilMs) }
    }

    suspend fun blockUser(targetUserId: String): Boolean = withContext(Dispatchers.IO) {
        val uid = currentUserId ?: return@withContext false
        runCatching {
            api.blockUser(uid, targetUserId)
            _blockedUsers.value = _blockedUsers.value + targetUserId
            true
        }.getOrDefault(false)
    }

    suspend fun unblockUser(targetUserId: String): Boolean = withContext(Dispatchers.IO) {
        val uid = currentUserId ?: return@withContext false
        runCatching {
            api.unblockUser(uid, targetUserId)
            _blockedUsers.value = _blockedUsers.value - targetUserId
            true
        }.getOrDefault(false)
    }

    suspend fun joinChannel(onlineId: String, invite: String? = null): String? =
        withContext(Dispatchers.IO) {
            if (!isSignedIn) return@withContext null
            val ok = runCatching { api.joinChannel(onlineId, invite) }.getOrDefault(false)
            if (!ok) return@withContext null
            refreshConversations()
            ensureLocalConversation(onlineId)
        }

    suspend fun createChannel(title: String, username: String?, isPublic: Boolean): String? =
        withContext(Dispatchers.IO) {
            if (!isSignedIn) return@withContext null
            val onlineId = runCatching { api.createChannel(title, username, isPublic, null) }.getOrNull()
                ?: return@withContext null
            ensureLocalConversation(onlineId)
        }

    suspend fun revokeDevice(rowId: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            api.revokeDevice(rowId)
            peersDao.markRevoked(rowId)
            groupSkdmDao.deleteForDevice(rowId)
            refreshConversations()
            if (rowId == deviceRowId) {
                signOut()
            }
        }
    }

    suspend fun myDevices(): List<RemoteDevice> = withContext(Dispatchers.IO) {
        val userId = myUserId ?: return@withContext emptyList()
        runCatching { api.myDevices(userId) }.getOrDefault(emptyList())
    }

    suspend fun refreshPeerDevices(userId: String) = withContext(Dispatchers.IO) {
        if (!isSignedIn || userId.isBlank()) return@withContext
        peerDevicesForUser(userId, forceRefresh = true)
        Unit
    }

    suspend fun leaveConversation(conversation: ConversationEntity) = withContext(Dispatchers.IO) {
        val onlineId = conversation.onlineId ?: return@withContext
        runCatching { api.leaveConversation(onlineId) }
        conversationsDao.updateMembership(conversation.id, conversation.title, conversation.memberUserIds)
    }

    suspend fun editMessage(message: MessageEntity, conversation: ConversationEntity) =
        withContext(Dispatchers.IO) {
            val userId = myUserId ?: return@withContext
            val onlineId = conversation.onlineId ?: return@withContext
            val remoteId = message.remoteId ?: return@withContext
            val editedAt = message.editedAt ?: System.currentTimeMillis()
            val editMeta = buildJsonObject {
                put("target", remoteId)
                put("edited_at", editedAt)
            }
            if (conversation.type == ConversationType.GROUP && conversation.encryptionVersion >= 2) {
                val distUuid = conversation.distributionId?.let { runCatching { UUID.fromString(it) }.getOrNull() }
                    ?: UUID.randomUUID()
                runCatching {
                    val cipher = crypto.encryptGroup(
                        settings.deviceId(),
                        distUuid,
                        OnlinePayloadCodec.encodeText(message.body)
                    )
                    api.uploadMessage(
                        senderUserId = userId,
                        senderDeviceId = settings.deviceId(),
                        conversationId = onlineId,
                        clientMsgId = "$remoteId:edit:$editedAt",
                        kind = "text-edit",
                        meta = editMeta,
                        senderDeviceRowId = deviceRowId,
                        groupCiphertext = Base64.encodeToString(cipher, Base64.NO_WRAP),
                        groupCipherEpoch = conversation.senderKeyEpoch,
                        groupDistributionId = distUuid.toString()
                    )
                    api.markMessageEdited(remoteId, onlineId, editedAt)
                }
                return@withContext
            }
            val envelopes = runCatching {
                envelopesFor(conversation, userId, OnlinePayloadCodec.encodeText(message.body))
            }.getOrDefault(emptyList())
            if (envelopes.isEmpty()) return@withContext
            val row = runCatching {
                api.uploadMessage(
                    senderUserId = userId,
                    senderDeviceId = settings.deviceId(),
                    conversationId = onlineId,
                    clientMsgId = "$remoteId:edit:$editedAt",
                    kind = "text-edit",
                    meta = editMeta,
                    senderDeviceRowId = deviceRowId
                )
            }.getOrNull() ?: return@withContext
            runCatching { api.putEnvelopes(envelopes.map { it.copy(messageId = row.id) }) }
            runCatching { api.markMessageEdited(remoteId, onlineId, editedAt) }
        }

    suspend fun deleteForEveryone(message: MessageEntity, conversation: ConversationEntity) =
        withContext(Dispatchers.IO) {
            val onlineId = conversation.onlineId ?: return@withContext
            val remoteId = message.remoteId ?: return@withContext
            runCatching { api.deleteMessageForEveryone(remoteId, onlineId) }
            val objectPath = message.remoteObjectPath
            if (!objectPath.isNullOrBlank()) {
                runCatching { api.deleteEncryptedBlob(objectPath, message.attachmentRemoteId) }
            }
        }

    private suspend fun markFailed(messageId: String, reason: String) {
        val retryable = reason == OnlineError.NETWORK || reason == OnlineError.NOT_SIGNED_IN ||
            reason == OnlineError.RATE_LIMITED || reason == OnlineError.UNKNOWN ||
            reason == "media_uploading"
        messagesDao.markFailed(
            messageId,
            if (retryable) MessageStatus.QUEUED else MessageStatus.FAILED,
            reason
        )
    }

    private suspend fun markConversationError(onlineId: String, reason: String) {
        locallyLinked(onlineId)?.let { localId ->
            Log.w(TAG, "Conversation $localId needs attention: $reason")
        }
    }

    private fun markTyping(conversationId: String, userId: String) {
        val current = _typing.value.toMutableMap()
        current[conversationId] = (current[conversationId].orEmpty() + userId).distinct()
        _typing.value = current
        scope.launch {
            delay(TYPING_TIMEOUT_MS)
            val updated = _typing.value.toMutableMap()
            val remaining = updated[conversationId].orEmpty().filterNot { it == userId }
            if (remaining.isEmpty()) updated.remove(conversationId) else updated[conversationId] = remaining
            _typing.value = updated
        }
    }

    private fun kindOf(message: MessageEntity): String = when (message.type) {
        MessageType.VOICE -> "voice"
        MessageType.IMAGE -> "image"
        MessageType.VIDEO -> "video"
        MessageType.AUDIO -> "audio"
        MessageType.FILE -> "file"
        else -> "text"
    }

    private fun reasonOf(error: Throwable): String = when (error) {
        is AuthException -> error.reason
        is SupabaseException -> when {
            error.code == 0 -> OnlineError.NETWORK
            error.message?.contains("device_limit_reached", true) == true -> OnlineError.DEVICE_LIMIT_REACHED
            error.message?.contains("device_revoked", true) == true -> OnlineError.DEVICE_REVOKED
            error.code == 401 -> OnlineError.NOT_SIGNED_IN
            error.code == 403 -> OnlineError.FORBIDDEN
            error.code == 429 -> OnlineError.RATE_LIMITED
            error.message?.contains("blocked", true) == true -> OnlineError.BLOCKED
            error.message?.contains("rate_limited", true) == true -> OnlineError.RATE_LIMITED
            else -> OnlineError.UNKNOWN
        }
        is IdentityChangedException -> OnlineError.IDENTITY_CHANGED
        else -> OnlineError.UNKNOWN
    }

    private fun backoff(attempts: Int): Long = Backoff.delayMillis(attempts)

    companion object {
        private const val TAG = "OnlineSync"
        private const val KEY_DEVICE_ROW = "device_row_id"
        private const val KEY_REGISTERED_AT = "device_registered_at"
        private const val KEY_FCM_TOKEN = "fcm_token_v1"
        private const val OUTBOX_INTERVAL_MS = 20_000L
        private const val TOKEN_REFRESH_MS = 15 * 60_000L
        private const val TYPING_TIMEOUT_MS = 8_000L
        private const val REGISTRATION_TTL_MS = 24 * 60 * 60 * 1000L
        private const val MIN_PREKEYS = 5

        fun parseIso(value: String): Long =
            runCatching { java.time.Instant.parse(value).toEpochMilli() }.getOrDefault(System.currentTimeMillis())

        private fun Collection<String>.joinedToString(): String = joinToString(",")
    }
}
