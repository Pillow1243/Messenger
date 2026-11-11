package com.offlinemesh.messenger.data

import android.content.Context
import android.util.Base64
import android.util.Log
import com.offlinemesh.messenger.core.crypto.CryptoManager
import com.offlinemesh.messenger.core.crypto.IdentityChangedException
import com.offlinemesh.messenger.core.db.AppDatabase
import com.offlinemesh.messenger.core.db.ConversationEntity
import com.offlinemesh.messenger.core.db.MessageEntity
import com.offlinemesh.messenger.core.db.OutboxEntity
import com.offlinemesh.messenger.core.db.PeerDeviceEntity
import com.offlinemesh.messenger.core.db.PeerProfileEntity
import com.offlinemesh.messenger.core.db.SyncStateEntity
import com.offlinemesh.messenger.domain.Backoff
import com.offlinemesh.messenger.domain.OnlineState
import com.offlinemesh.messenger.network.AuthException
import com.offlinemesh.messenger.network.PostgrestClient
import com.offlinemesh.messenger.network.RealtimeProtocol
import com.offlinemesh.messenger.network.SupabaseAuth
import com.offlinemesh.messenger.network.SupabaseConfig
import com.offlinemesh.messenger.network.SupabaseException
import com.offlinemesh.messenger.network.SupabaseHttp
import com.offlinemesh.messenger.network.SupabaseRealtime
import com.offlinemesh.messenger.core.security.SecretStore
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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

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
    const val FORBIDDEN = "forbidden"
    const val UNKNOWN = "unknown"
}

/**
 * The online transport.
 *
 * Responsibilities:
 *  * own the authenticated session and the WebSocket subscription;
 *  * bootstrap this installation as a verified *device* of the account (identity key, signed
 *    pre-key, Kyber pre-key and a pool of one-time pre-keys) so peers can start a Signal session;
 *  * upload outgoing messages as metadata + one ciphertext envelope per recipient device;
 *  * backfill and decrypt incoming ciphertext, persist it locally, and acknowledge delivery;
 *  * drain the outbox with backoff so nothing is lost when the network drops.
 *
 * The server is never trusted with plaintext, and realtime events are treated as hints: a
 * backfill runs on every (re)connection, so a lost socket cannot lose a message.
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

    private val _state = MutableStateFlow<OnlineState>(OnlineState.SignedOut)
    val state: StateFlow<OnlineState> = _state.asStateFlow()

    private val _typing = MutableStateFlow<Map<String, List<String>>>(emptyMap())
    val typing: StateFlow<Map<String, List<String>>> = _typing.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var outboxJob: Job? = null
    private var keepAliveJob: Job? = null
    private var changeJob: Job? = null

    @Volatile private var deviceRowId: String? = null
    @Volatile private var myUserId: String? = null
    @Volatile private var started = false
    @Volatile private var subscribedToken: String? = null

    // ------------------------------------------------------------------ lifecycle

    /** Starts connectivity watching; the online transport activates itself when signed in. */
    fun start() {
        if (started) return
        started = true
        connectivity.start()
        scope.launch {
            connectivity.online.collect { online ->
                if (online && auth.session.value != null) {
                    runCatching { activate() }.onFailure { Log.w(TAG, "activate failed", it) }
                    // Connectivity is back: flush anything the user sent while offline.
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
            refreshConversations()
            backfillAll()
            val token = auth.validAccessToken() ?: run {
                _state.value = OnlineState.SignedOut
                return
            }
            subscribe(token)
            _state.value = OnlineState.Connected(session.userId, session.email)
            startBackgroundWork()
        } catch (e: SupabaseException) {
            _state.value = if (e.code == 0) OnlineState.Offline else OnlineState.Error(reasonOf(e))
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
            ensureRegistered()
            activate()
        }.onFailure {
            _lastError.value = reasonOf(it)
            _state.value = if ((it as? SupabaseException)?.code == 0) OnlineState.Offline else OnlineState.SignedOut
        }
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        realtime.stop()
        outboxJob?.cancel()
        runCatching { auth.signOut() }
        syncDao.remove(KEY_DEVICE_ROW)
        deviceRowId = null
        myUserId = null
        _state.value = OnlineState.SignedOut
    }

    suspend fun deleteAccount(): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            api.deleteAccount()
            signOut()
        }
    }

    fun clearError() {
        _lastError.value = null
    }

    /** Called from Settings when privacy settings change; keeps the server profile in sync. */
    suspend fun updateProfile(displayName: String, username: String? = null) =
        withContext(Dispatchers.IO) {
            if (!isSignedIn) return@withContext
            runCatching { api.ensureProfile(displayName, username) }
        }

    // -------------------------------------------------------------- device bootstrap

    private suspend fun linkDeviceAndProfile(displayName: String) {
        val name = displayName.ifBlank { settings.snapshot().displayName.ifBlank { "Messenger" } }
        api.ensureProfile(name, null)
        ensureRegistered(force = true)
    }

    /**
     * Publishes this installation's public keys once, then only refreshes them when the pre-key
     * pool runs low or the registration is a day old. The endpoint is rate limited on the server,
     * so re-registering on every connectivity change would burn the budget for no benefit.
     */
    private suspend fun ensureRegistered(force: Boolean = false) {
        val userId = myUserId ?: auth.currentUserId() ?: return
        val cached = syncDao.get(KEY_DEVICE_ROW)
        val lastRegistered = syncDao.get(KEY_REGISTERED_AT)?.toLongOrNull() ?: 0L
        val stale = System.currentTimeMillis() - lastRegistered > REGISTRATION_TTL_MS
        val preKeysLow = crypto.oneTimePreKeyCount() < MIN_PREKEYS
        if (!force && cached != null && !stale && !preKeysLow) {
            deviceRowId = cached
            api.touchLastSeen()
            return
        }
        val bundle = crypto.publishedBundle()
        val preKeys = crypto.preKeyPublics()
        val rowId = api.registerDevice(
            ownerUserId = userId,
            deviceId = settings.deviceId(),
            deviceName = android.os.Build.MODEL ?: "Android device",
            bundle = bundle,
            preKeys = preKeys
        )
        deviceRowId = rowId
        if (cached != rowId) syncDao.put(SyncStateEntity(KEY_DEVICE_ROW, rowId))
        syncDao.put(SyncStateEntity(KEY_REGISTERED_AT, System.currentTimeMillis().toString()))
        api.touchLastSeen()
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
                RealtimeProtocol.Subscription("mm_messages"),
                RealtimeProtocol.Subscription("mm_message_receipts"),
                RealtimeProtocol.Subscription("mm_typing"),
                RealtimeProtocol.Subscription("mm_reactions")
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

            "mm_message_receipts" -> {
                val messageId = change.record.str("message_id") ?: return
                val reader = change.record.str("user_id") ?: return
                if (reader == userId) return
                val local = messagesDao.getByRemoteId(messageId) ?: return
                val readAt = change.record.str("read_at")?.let { parseIso(it) }
                val deliveredAt = change.record.str("delivered_at")?.let { parseIso(it) }
                when {
                    readAt != null -> {
                        messagesDao.markDelivered(local.id, deliveredAt ?: readAt)
                        messagesDao.markReadAt(local.id, readAt)
                    }

                    deliveredAt != null -> messagesDao.markDelivered(local.id, deliveredAt)
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
        }
    }

    private suspend fun onMessageRow(record: JsonObject) {
        val senderId = record.str("sender_id") ?: return
        val clientMsgId = record.str("client_msg_id")
        val remoteId = record.str("id")
        if (senderId == myUserId && clientMsgId != null && remoteId != null) {
            // Our own message was accepted by the server: record the remote id.
            messagesDao.getByClientMsgId(clientMsgId)?.let { local ->
                messagesDao.markUploaded(local.id, remoteId, MessageStatus.SENT)
            }
        }
    }

    // -------------------------------------------------------------------- ingest

    private suspend fun ingestEnvelope(messageId: String, ciphertextBase64: String) {
        val remote = api.messageById(messageId) ?: return
        if (remote.senderId == myUserId) return
        val existing = messagesDao.getByRemoteId(messageId)
        if (existing != null) {
            // Already decrypted; drop our copy of the envelope so it cannot be replayed.
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
        } catch (e: IdentityChangedException) {
            markConversationError(remote.conversationId, OnlineError.IDENTITY_CHANGED)
            return
        } catch (e: Exception) {
            Log.w(TAG, "Could not decrypt $messageId: ${e.message}")
            return
        }
        val body = String(plaintext, Charsets.UTF_8)
        if (remote.kind == "text-edit") {
            val target = remote.meta.str("target")
            val editedAt = remote.meta["edited_at"]?.jsonPrimitive?.longOrNull ?: remote.sentAtMs
            if (target != null) {
                messagesDao.getByRemoteId(target)?.let { local ->
                    messagesDao.editBody(local.id, body, editedAt)
                }
            }
            runCatching { api.deleteEnvelope(remote.id, myUserId ?: return) }
            return
        }
        val localConversationId = ensureLocalConversation(remote.conversationId)
        val localId = "srv:" + remote.id
        messagesDao.upsert(
            MessageEntity(
                id = localId,
                conversationId = localConversationId,
                senderDeviceId = remote.senderDeviceId,
                outgoing = false,
                type = if (remote.kind == "voice") MessageType.VOICE else MessageType.TEXT,
                body = body,
                timestamp = remote.sentAtMs,
                status = MessageStatus.RECEIVED,
                clientMsgId = remote.clientMsgId,
                remoteId = remote.id,
                transport = "ONLINE",
                deliveredAt = System.currentTimeMillis()
            )
        )
        conversationsDao.updateLastMessage(
            localConversationId,
            remote.sentAtMs,
            body.replace('\n', ' ').take(120),
            1
        )
        rememberSender(remote.senderId, remote.senderDeviceId)
        runCatching { api.markDelivered(remote.id, myUserId ?: return) }
        runCatching { api.deleteEnvelope(remote.id, myUserId ?: return) }
    }

    // --------------------------------------------------------------------- sending

    /**
     * Uploads one locally stored message. Encryption happens here, per recipient device, with a
     * fresh session if needed - never ahead of time, so a rotated pre-key cannot produce stale
     * ciphertext.
     */
    suspend fun deliver(message: MessageEntity, conversation: ConversationEntity): OnlineSendResult =
        withContext(Dispatchers.IO) {
            if (!isSignedIn) return@withContext OnlineSendResult.failure(OnlineError.NOT_SIGNED_IN)
            if (!SupabaseConfig.configured) return@withContext OnlineSendResult.failure(OnlineError.NOT_LINKED)
            val userId = myUserId ?: return@withContext OnlineSendResult.failure(OnlineError.NOT_SIGNED_IN)
            val onlineConversationId = conversation.onlineId
                ?: return@withContext OnlineSendResult.failure(OnlineError.NOT_LINKED)
            sendMutex.withLock {
                try {
                    if (deviceRowId == null) ensureRegistered()
                    val plaintext = message.body.toByteArray(Charsets.UTF_8)
                    val envelopes = envelopesFor(conversation, userId, plaintext)
                    if (envelopes.isEmpty()) {
                        markFailed(message.id, OnlineError.NO_DEVICES)
                        return@withLock OnlineSendResult.failure(OnlineError.NO_DEVICES)
                    }
                    val remote = api.uploadMessage(
                        senderUserId = userId,
                        senderDeviceId = settings.deviceId(),
                        conversationId = onlineConversationId,
                        clientMsgId = message.clientMsgId.ifBlank { message.id },
                        kind = kindOf(message),
                        meta = JsonObject(emptyMap())
                    )
                    api.putEnvelopes(envelopes.map { it.copy(messageId = remote.id) })
                    messagesDao.markUploaded(message.id, remote.id, MessageStatus.SENT)
                    outboxDao.deleteForMessage(message.id)
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
     * Encrypts one plaintext for every device of every other member. Sessions are created lazily
     * from a freshly claimed pre-key bundle, so a rotated key can never yield stale ciphertext.
     */
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

    private suspend fun devicesFor(userIds: List<String>): List<PeerDeviceEntity> {
        val cached = userIds.flatMap { peersDao.devicesFor(it) }
        if (userIds.all { uid -> cached.any { it.userId == uid } }) return cached
        val remote = api.devicesOf(userIds)
        if (remote.isEmpty()) return cached
        val entities = remote.map {
            PeerDeviceEntity(
                id = it.rowId,
                userId = it.userId,
                deviceId = it.deviceId,
                identityKey = it.identityKey,
                lastSeenAt = System.currentTimeMillis()
            )
        }
        peersDao.upsertDevices(entities)
        return entities
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

    /** Retries every due outbox row once. Safe to call from workers, UI and connectivity events. */
    suspend fun drainOutbox(): Int = withContext(Dispatchers.IO) {
        if (!isSignedIn) return@withContext 0
        var sent = 0
        val now = System.currentTimeMillis()
        for (entry in outboxDao.due(now, 25)) {
            val message = messagesDao.getById(entry.messageId)
            val conversation = conversationsDao.getById(entry.conversationId)
            if (message == null || conversation == null) {
                outboxDao.delete(entry.id)
                continue
            }
            if (!message.outgoing) {
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
        val key = "cursor:$onlineId"
        val since = syncDao.get(key)?.toLongOrNull() ?: 0L
        val remoteMessages = api.messagesSince(onlineId, since)
        if (remoteMessages.isEmpty()) return
        val incoming = remoteMessages.filter { it.senderId != uid }
        val envelopeFetched = messagesDao.run {
            // Collect the ones we still need to decrypt.
            incoming.filter { getByRemoteId(it.id) == null }
        }
        if (envelopeFetched.isNotEmpty()) {
            val envelopes = api.envelopesFor(envelopeFetched.map { it.id }, userId)
            envelopes.forEach { envelope ->
                runCatching { ingestEnvelope(envelope.messageId, envelope.ciphertext) }
                    .onFailure { Log.w(TAG, "Could not ingest ${envelope.messageId}", it) }
            }
        }
        val newest = remoteMessages.maxOf { it.sentAtMs }
        if (newest > since) syncDao.put(SyncStateEntity(key, newest.toString()))
        refreshReactionsFor(remoteMessages.filter { it.senderId != userId }.map { it.id })
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

    /** Maps a server conversation uuid to a local row, creating it when it is new. */
    suspend fun ensureLocalConversation(
        onlineId: String,
        knownRemote: RemoteConversation? = null
    ): String {
        val existing = conversationsDao.getByOnlineId(onlineId)
        val userId = myUserId
        val remote = knownRemote ?: runCatching {
            api.listConversations(userId ?: return onlineId).firstOrNull { it.id == onlineId }
        }.getOrNull()

        val members = remote?.memberUserIds.orEmpty()
        val peerUserId = if (remote?.type == "direct") {
            members.firstOrNull { it != userId }
        } else {
            null
        }
        val title = when {
            !remote?.title.isNullOrBlank() -> remote?.title.orEmpty()
            peerUserId != null -> displayNameOf(peerUserId)
            else -> "Chat"
        }
        val localType = when (remote?.type) {
            "group" -> "GROUP"
            "channel" -> "CHANNEL"
            "direct" -> "DIRECT"
            else -> if (existing != null) existing.type else "DIRECT"
        }
        if (existing != null) {
            conversationsDao.linkOnline(existing.id, onlineId, peerUserId)
            conversationsDao.updateMembership(existing.id, title, members.joinedToString())
            if (peerUserId != null) cacheProfile(peerUserId)
            return existing.id
        }
        val localId = "online:$onlineId"
        val now = System.currentTimeMillis()
        conversationsDao.upsert(
            ConversationEntity(
                id = localId,
                title = title,
                isGroup = localType != "DIRECT",
                memberIds = "",
                createdAt = now,
                lastMessageAt = remote?.lastMessageAtMs ?: now,
                type = localType,
                onlineId = onlineId,
                peerUserId = peerUserId,
                memberUserIds = members.joinedToString()
            )
        )
        if (peerUserId != null) cacheProfile(peerUserId)
        return localId
    }

    /** Local row for a server conversation, if this device already knows it. */
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
        // The mesh contact row (keyed by device id) is shared by both transports, so a peer we
        // also met in the mesh keeps one identity and one display name.
        if (deviceId.isNotBlank()) {
            val contact = contactsDao.getByDeviceId(deviceId)
            if (contact != null && contact.userId == null) {
                contactsDao.upsert(
                    contact.copy(userId = userId, lastSeenAt = System.currentTimeMillis())
                )
            }
        }
    }

    // ------------------------------------------------------------------ typing/read

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
            messagesDao.markReadAt(message.id, System.currentTimeMillis())
            messagesDao.markDelivered(message.id, System.currentTimeMillis())
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

    // -------------------------------------------------------------- social features

    suspend fun searchUsers(query: String): List<RemoteProfile> = withContext(Dispatchers.IO) {
        runCatching { api.searchUsers(query) }.getOrDefault(emptyList())
    }

    suspend fun searchChannels(query: String): List<RemoteChannel> = withContext(Dispatchers.IO) {
        runCatching { api.searchChannels(query) }.getOrDefault(emptyList())
    }

    /**
     * Attaches an existing (mesh) conversation to its server counterpart instead of creating a
     * second local row for the same peer. Returns the refreshed conversation.
     */
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
        val onlineId = runCatching { api.startDirectConversation(peerUserId) }.getOrNull() ?: return@withContext null
        ensureLocalConversation(onlineId)
    }

    suspend fun createGroup(title: String, memberUserIds: List<String>): String? =
        withContext(Dispatchers.IO) {
            if (!isSignedIn) return@withContext null
            val onlineId = runCatching { api.createGroup(title, memberUserIds) }.getOrNull()
                ?: return@withContext null
            ensureLocalConversation(onlineId)
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
            if (rowId == deviceRowId) {
                deviceRowId = null
                syncDao.remove(KEY_DEVICE_ROW)
                ensureRegistered()
            }
        }
    }

    suspend fun myDevices(): List<RemoteDevice> = withContext(Dispatchers.IO) {
        val userId = myUserId ?: return@withContext emptyList()
        runCatching { api.devicesOf(listOf(userId)) }.getOrDefault(emptyList())
    }

    // --------------------------------------------------------------------- helpers

    suspend fun leaveConversation(conversation: ConversationEntity) = withContext(Dispatchers.IO) {
        val onlineId = conversation.onlineId ?: return@withContext
        runCatching { api.leaveConversation(onlineId) }
        conversationsDao.updateMembership(conversation.id, conversation.title, conversation.memberUserIds)
    }

    /**
     * Edits are delivered as a normal encrypted message whose kind is `text-edit` and whose meta
     * names the original message, so peers learn about the change through the same realtime
     * INSERT path and can re-verify the new ciphertext. The plaintext never leaves the device
     * unencrypted, exactly like a fresh message.
     */
    suspend fun editMessage(message: MessageEntity, conversation: ConversationEntity) =
        withContext(Dispatchers.IO) {
            val userId = myUserId ?: return@withContext
            val onlineId = conversation.onlineId ?: return@withContext
            val remoteId = message.remoteId ?: return@withContext
            val editedAt = message.editedAt ?: System.currentTimeMillis()
            val envelopes = runCatching {
                envelopesFor(conversation, userId, message.body.toByteArray(Charsets.UTF_8))
            }.getOrDefault(emptyList())
            if (envelopes.isEmpty()) return@withContext
            val row = runCatching {
                api.uploadMessage(
                    senderUserId = userId,
                    senderDeviceId = settings.deviceId(),
                    conversationId = onlineId,
                    clientMsgId = "$remoteId:edit:$editedAt",
                    kind = "text-edit",
                    meta = buildJsonObject {
                        put("target", remoteId)
                        put("edited_at", editedAt)
                    }
                )
            }.getOrNull() ?: return@withContext
            runCatching { api.putEnvelopes(envelopes.map { it.copy(messageId = row.id) }) }
            runCatching { api.editMessage(remoteId, onlineId, message.body, editedAt) }
        }

    suspend fun deleteForEveryone(message: MessageEntity, conversation: ConversationEntity) =
        withContext(Dispatchers.IO) {
            val onlineId = conversation.onlineId ?: return@withContext
            val remoteId = message.remoteId ?: return@withContext
            runCatching { api.deleteMessageForEveryone(remoteId, onlineId) }
        }

    private suspend fun markFailed(messageId: String, reason: String) {
        val retryable = reason == OnlineError.NETWORK || reason == OnlineError.NOT_SIGNED_IN ||
            reason == OnlineError.RATE_LIMITED || reason == OnlineError.UNKNOWN
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
        MessageType.FILE -> "file"
        else -> "text"
    }

    private fun reasonOf(error: Throwable): String = when (error) {
        is AuthException -> error.reason
        is SupabaseException -> when {
            error.code == 0 -> OnlineError.NETWORK
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
