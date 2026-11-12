package com.offlinemesh.messenger.presentation

import android.media.MediaPlayer
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.offlinemesh.messenger.MessengerApp
import com.offlinemesh.messenger.call.CallSessionState
import com.offlinemesh.messenger.core.db.CallLogEntity
import com.offlinemesh.messenger.core.db.ContactEntity
import com.offlinemesh.messenger.core.db.ConversationEntity
import com.offlinemesh.messenger.core.db.MessageEntity
import com.offlinemesh.messenger.core.db.PeerDeviceEntity
import com.offlinemesh.messenger.core.db.PeerProfileEntity
import com.offlinemesh.messenger.core.mesh.PeerInfo
import com.offlinemesh.messenger.data.AppSettings
import com.offlinemesh.messenger.data.ChatRepository
import com.offlinemesh.messenger.data.OnlineError
import com.offlinemesh.messenger.data.PairingCodec
import com.offlinemesh.messenger.data.RemoteChannel
import com.offlinemesh.messenger.data.RemoteDevice
import com.offlinemesh.messenger.data.RemoteProfile
import com.offlinemesh.messenger.domain.OnlineState
import com.offlinemesh.messenger.push.FirebasePushInitializer
import com.offlinemesh.messenger.push.PushRegistrationStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.webrtc.EglBase
import org.webrtc.VideoTrack
import java.io.File

/**
 * Single view-model for the whole app: coordinates offline mesh, online E2EE messaging,
 * streaming encrypted media transfers, FCM wakeup preferences, Signal Sender Keys groups,
 * 1:1 WebRTC voice/video calling, and multi-device lifecycle.
 */
class AppViewModel(private val app: MessengerApp) : ViewModel() {

    /** Null until the first DataStore emission, so the UI can avoid flashing onboarding. */
    val settings: StateFlow<AppSettings?> =
        app.settings.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val conversations: StateFlow<List<ConversationEntity>> =
        app.chats.observeConversations()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val contacts: StateFlow<List<ContactEntity>> =
        app.chats.observeContacts()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val peers: StateFlow<List<PeerInfo>> = app.mesh.peerFlow
    val meshRunning: StateFlow<Boolean> = app.mesh.running

    private val _pairingCode = MutableStateFlow("")
    val pairingCode: StateFlow<String> = _pairingCode.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _safetyNumbers = MutableStateFlow<Map<String, String>>(emptyMap())
    val safetyNumbers: StateFlow<Map<String, String>> = _safetyNumbers.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _playingMessageId = MutableStateFlow<String?>(null)
    val playingMessageId: StateFlow<String?> = _playingMessageId.asStateFlow()
    private var mediaPlayer: MediaPlayer? = null

    val isOnlineSignedIn: Boolean get() = app.online.isSignedIn
    val currentUserId: String? get() = app.online.currentUserId
    val currentDeviceRowId: String? get() = app.online.currentDeviceRowId

    init {
        refreshPairingCode()
    }

    override fun onCleared() {
        super.onCleared()
        stopAudioPlayback()
    }

    fun messages(conversationId: String): Flow<List<MessageEntity>> =
        app.chats.observeMessages(conversationId)

    fun conversation(conversationId: String): Flow<ConversationEntity?> =
        app.chats.observeConversation(conversationId)

    fun observePeerDevices(userId: String): Flow<List<PeerDeviceEntity>> =
        app.database.peerDao().observeDevicesFor(userId)

    fun clearError() {
        _error.value = null
        app.online.clearError()
    }

    // ------------------------------------------------------------------ onboarding

    fun completeOnboarding(name: String, language: String) {
        viewModelScope.launch {
            app.settings.setDisplayName(name)
            app.settings.setLanguage(language)
            app.settings.setOnboarded(true)
            app.crypto.ensureIdentity()
            app.mesh.refreshIdentity()
            app.applyMeshPreference()
            refreshPairingCode()
        }
    }

    // --------------------------------------------------------------------- settings & notifications

    fun setDisplayName(name: String) = viewModelScope.launch {
        app.settings.setDisplayName(name)
        app.mesh.refreshIdentity()
        app.online.updateProfile(name)
    }

    fun setLanguage(language: String) = viewModelScope.launch { app.settings.setLanguage(language) }

    fun setTheme(theme: String) = viewModelScope.launch { app.settings.setTheme(theme) }

    fun setMeshEnabled(enabled: Boolean) = viewModelScope.launch {
        app.settings.setMeshEnabled(enabled)
        app.applyMeshPreference()
    }

    fun setBluetoothEnabled(enabled: Boolean) = viewModelScope.launch {
        app.settings.setBluetoothEnabled(enabled)
        app.applyMeshPreference()
    }

    fun setWifiEnabled(enabled: Boolean) = viewModelScope.launch {
        app.settings.setWifiEnabled(enabled)
        app.applyMeshPreference()
    }

    fun setBatterySaver(enabled: Boolean) = viewModelScope.launch {
        app.settings.setBatterySaver(enabled)
        app.applyMeshPreference()
    }

    fun setBackgroundService(enabled: Boolean) = viewModelScope.launch {
        app.settings.setBackgroundService(enabled)
        app.applyMeshPreference()
    }

    fun setNotificationsEnabled(enabled: Boolean) = viewModelScope.launch {
        app.settings.setNotificationsEnabled(enabled)
        app.online.syncNotificationPrefs(app.settings.snapshot())
    }

    fun setNotifyDirect(enabled: Boolean) = viewModelScope.launch {
        app.settings.setNotifyDirect(enabled)
        app.online.syncNotificationPrefs(app.settings.snapshot())
    }

    fun setNotifyGroups(enabled: Boolean) = viewModelScope.launch {
        app.settings.setNotifyGroups(enabled)
        app.online.syncNotificationPrefs(app.settings.snapshot())
    }

    fun setNotifyChannels(enabled: Boolean) = viewModelScope.launch {
        app.settings.setNotifyChannels(enabled)
        app.online.syncNotificationPrefs(app.settings.snapshot())
    }

    fun setNotifyCalls(enabled: Boolean) = viewModelScope.launch {
        app.settings.setNotifyCalls(enabled)
        app.online.syncNotificationPrefs(app.settings.snapshot())
    }

    fun applyMeshPreference() = app.applyMeshPreference()

    // ------------------------------------------------------------------------ chats & media

    fun sendText(conversationId: String, text: String, replyToId: String? = null) =
        viewModelScope.launch {
            runCatching { app.chats.sendText(conversationId, text, replyToId) }
                .onFailure { _error.value = "error_unknown" }
        }

    fun sendAttachmentsFromUris(conversationId: String, uris: List<Uri>, caption: String = "") =
        viewModelScope.launch {
            if (uris.isEmpty()) return@launch
            _busy.value = true
            runCatching {
                val created = app.chats.sendAttachmentsFromUris(conversationId, uris, caption)
                if (created.isEmpty()) {
                    _error.value = "attachment_pick_failed"
                } else if (created.size < uris.size) {
                    // Some went out, some did not: say which problem stopped the rest.
                    _error.value = when (app.chats.lastAttachmentProblem()) {
                        "file_too_large", "file_too_large_for_mesh" -> "file_too_large"
                        "insufficient_storage" -> "insufficient_storage"
                        else -> "attachment_pick_failed"
                    }
                }
            }.onFailure { err ->
                _error.value = when (err.message) {
                    "file_too_large", "file_too_large_for_mesh" -> "file_too_large"
                    "insufficient_storage" -> "insufficient_storage"
                    else -> "attachment_pick_failed"
                }
            }
            _busy.value = false
        }

    fun sendFile(conversationId: String, file: File, displayName: String) = viewModelScope.launch {
        _busy.value = true
        runCatching { app.chats.sendFile(conversationId, file, displayName) }
            .onFailure {
                _error.value = if (it is IllegalArgumentException) "file_too_large" else "attachment_pick_failed"
                file.delete()
            }
        _busy.value = false
    }

    fun sendVoice(conversationId: String, file: File, durationMs: Long) = viewModelScope.launch {
        runCatching { app.chats.sendVoice(conversationId, file, durationMs) }
            .onFailure {
                _error.value = if (it is IllegalArgumentException) "file_too_large" else "attachment_pick_failed"
            }
    }

    fun downloadAttachment(messageId: String) = viewModelScope.launch {
        app.chats.downloadAttachment(messageId)
    }

    fun retryMediaTransfer(messageId: String) = viewModelScope.launch {
        app.chats.retryMediaTransfer(messageId)
    }

    fun cancelMediaTransfer(messageId: String) = viewModelScope.launch {
        app.chats.cancelMediaTransfer(messageId)
    }

    fun toggleAudioPlayback(message: MessageEntity) = viewModelScope.launch {
        if (_playingMessageId.value == message.id) {
            stopAudioPlayback()
            return@launch
        }
        stopAudioPlayback()
        val exported = app.chats.exportForOpening(message.filePath, message.fileName ?: "audio.m4a")
        if (exported == null) {
            if (message.remoteObjectPath != null) app.chats.downloadAttachment(message.id)
            else _error.value = "error_unknown"
            return@launch
        }
        runCatching {
            val player = MediaPlayer().apply {
                setDataSource(exported.absolutePath)
                setOnCompletionListener {
                    _playingMessageId.value = null
                    runCatching { release() }
                    mediaPlayer = null
                }
                prepare()
                start()
            }
            mediaPlayer = player
            _playingMessageId.value = message.id
        }.onFailure {
            stopAudioPlayback()
            _error.value = "error_unknown"
        }
    }

    fun stopAudioPlayback() {
        runCatching { mediaPlayer?.stop() }
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
        _playingMessageId.value = null
    }

    fun markRead(conversationId: String) = viewModelScope.launch { app.chats.markRead(conversationId) }

    /** [onDeleted] runs only when the chat is really gone, so the screen stays open on failure. */
    fun deleteConversation(conversationId: String, onDeleted: () -> Unit = {}) = viewModelScope.launch {
        val deleted = runCatching { app.chats.deleteConversation(conversationId) }.getOrDefault(false)
        if (deleted) onDeleted() else _error.value = "leave_failed"
    }

    fun toggleConversationMute(conversationId: String) = viewModelScope.launch {
        val conv = app.chats.conversation(conversationId) ?: return@launch
        app.chats.setConversationMuted(conversationId, !conv.muted, null)
    }

    fun toggleConversationPin(conversationId: String) = viewModelScope.launch {
        val conv = app.chats.conversation(conversationId) ?: return@launch
        app.database.conversationDao().setPinned(conversationId, !conv.pinned)
    }

    fun createGroup(name: String, memberIds: List<String>, onCreated: (String) -> Unit) =
        viewModelScope.launch {
            if (app.online.isSignedIn) {
                val allContacts = contacts.value.associateBy { it.deviceId }
                val userIds = memberIds.mapNotNull { allContacts[it]?.userId }.distinct()
                if (userIds.isNotEmpty() && userIds.size == memberIds.size) {
                    val onlineGroupId = app.online.createGroup(name, userIds)
                    if (onlineGroupId != null) {
                        val existing = app.database.conversationDao().getById(onlineGroupId)
                        if (existing != null) {
                            val meDev = app.settings.deviceId()
                            val combined = (memberIds + meDev).distinct().joinToString(",")
                            app.database.conversationDao().upsert(existing.copy(memberIds = combined))
                        }
                        onCreated(onlineGroupId)
                        return@launch
                    }
                }
            }
            val id = app.chats.createGroup(name, memberIds)
            onCreated(id)
        }

    fun openConversationWith(deviceId: String, onReady: (String) -> Unit) = viewModelScope.launch {
        val name = app.contacts.displayName(deviceId)
        onReady(app.chats.ensureDirectConversation(deviceId, name))
    }

    fun openAttachment(path: String?, displayName: String, onReady: (Uri) -> Unit) =
        viewModelScope.launch {
            val file = app.chats.exportForOpening(path, displayName)
            if (file == null) {
                _error.value = "error_unknown"
            } else {
                onReady(
                    FileProvider.getUriForFile(
                        app,
                        "${app.packageName}.fileprovider",
                        file
                    )
                )
            }
        }

    // --------------------------------------------------------- group Sender Keys & members

    fun upgradeGroupToSenderKeys(conversationId: String, onDone: (Boolean) -> Unit = {}) =
        viewModelScope.launch {
            val ok = app.chats.upgradeGroupEncryption(conversationId)
            onDone(ok)
        }

    fun addGroupMember(conversationId: String, userId: String, onDone: (Boolean) -> Unit = {}) =
        viewModelScope.launch {
            val ok = app.chats.addGroupMembers(conversationId, listOf(userId))
            onDone(ok)
        }

    fun removeGroupMember(conversationId: String, userId: String, onDone: (Boolean) -> Unit = {}) =
        viewModelScope.launch {
            val ok = app.chats.removeGroupMember(conversationId, userId)
            onDone(ok)
        }

    suspend fun groupMemberProfiles(conversation: ConversationEntity): List<PeerProfileEntity> =
        withContext(Dispatchers.IO) {
            val ids = conversation.memberUserIds.split(',')
                .map { it.trim() }
                .filter { it.isNotBlank() }
            ids.map { uid ->
                app.database.peerDao().profile(uid)
                    ?: PeerProfileEntity(
                        userId = uid,
                        username = "",
                        displayName = if (uid == currentUserId) {
                            settings.value?.displayName?.ifBlank { "Me" } ?: "Me"
                        } else {
                            uid.take(8)
                        },
                        updatedAt = 0L
                    )
            }
        }

    // --------------------------------------------------------- 1:1 WebRTC voice/video calling

    val callState: StateFlow<CallSessionState> = app.calls.state
    val localVideoTrack: StateFlow<VideoTrack?> = app.calls.localVideoTrack
    val remoteVideoTrack: StateFlow<VideoTrack?> = app.calls.remoteVideoTrack
    val recentCalls: StateFlow<List<CallLogEntity>> = app.calls.recentCalls
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun eglBaseContext(): EglBase.Context = app.calls.eglBaseContext()

    fun startCall(conversationId: String, isVideo: Boolean) = viewModelScope.launch {
        stopAudioPlayback()
        app.calls.startOutgoingCall(conversationId, isVideo).onFailure { err ->
            _error.value = err.message ?: "error_unknown"
        }
    }

    fun acceptIncomingCall(callId: String) = viewModelScope.launch {
        stopAudioPlayback()
        app.calls.acceptIncomingCall(callId).onFailure { err ->
            _error.value = err.message ?: "error_unknown"
        }
    }

    fun declineIncomingCall(callId: String) = viewModelScope.launch {
        app.calls.declineIncomingCall(callId)
    }

    fun endCall() = viewModelScope.launch {
        app.calls.endCall("hangup")
    }

    fun dismissEndedCall() = app.calls.dismissEndedBanner()

    fun toggleMicMute() = app.calls.toggleMicMute()

    fun toggleCamera() = app.calls.toggleCamera()

    fun switchCamera() = app.calls.switchCamera()

    fun toggleSpeakerphone() = app.calls.toggleSpeakerphone()

    // --------------------------------------------------------------------- contacts & safety

    fun refreshPairingCode() = viewModelScope.launch {
        runCatching { app.contacts.myPairingCode() }
            .onSuccess { _pairingCode.value = it }
    }

    fun addContactByCode(raw: String, onResult: (Boolean) -> Unit) = viewModelScope.launch {
        val payload = PairingCodec.decode(raw)
        if (payload == null) {
            _error.value = "invalid_code"
            onResult(false)
            return@launch
        }
        val contact = app.contacts.addFromPairing(payload)
        if (contact == null) {
            _error.value = "invalid_code"
            onResult(false)
        } else {
            refreshPairingCode()
            onResult(true)
        }
    }

    fun loadSafetyNumber(deviceId: String) = viewModelScope.launch {
        val number = app.contacts.safetyNumber(deviceId) ?: ""
        _safetyNumbers.value = _safetyNumbers.value + (deviceId to number)
    }

    suspend fun deviceSafetyNumber(remoteDeviceId: String, remoteIdentityKeyBase64: String): String? =
        app.crypto.safetyNumberForIdentityKey(
            remoteDeviceId = remoteDeviceId,
            remoteIdentityKeyBase64 = remoteIdentityKeyBase64,
            localDeviceId = app.settings.deviceId()
        )

    fun refreshPeerDevices(userId: String) = viewModelScope.launch {
        app.online.refreshPeerDevices(userId)
    }

    fun setPeerDeviceVerified(deviceRowId: String, verified: Boolean) = viewModelScope.launch {
        app.database.peerDao().setDeviceVerified(deviceRowId, verified)
    }

    fun setVerified(deviceId: String, verified: Boolean) = viewModelScope.launch {
        app.contacts.setVerified(deviceId, verified)
    }

    fun resetTrust(deviceId: String) = viewModelScope.launch {
        app.contacts.resetTrust(deviceId)
        loadSafetyNumber(deviceId)
    }

    fun contact(deviceId: String): ContactEntity? = contacts.value.firstOrNull { it.deviceId == deviceId }

    // ------------------------------------------------------------------ online mode & devices

    val onlineState: StateFlow<OnlineState> = app.online.state
    val networkAvailable: StateFlow<Boolean> = app.online.networkFlow()
    val onlineError: StateFlow<String?> = app.online.lastError
    val blockedUsers: StateFlow<Set<String>> = app.online.blockedUsersFlow
    val pushStatus: StateFlow<PushRegistrationStatus> = FirebasePushInitializer.status
    val typing: StateFlow<Map<String, List<String>>> = app.chats.observeTyping()
    val channels: StateFlow<List<ConversationEntity>> =
        app.chats.observeChannels()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _devices = MutableStateFlow<List<RemoteDevice>>(emptyList())
    val devices: StateFlow<List<RemoteDevice>> = _devices.asStateFlow()

    private val _localResults = MutableStateFlow(
        ChatRepository.LocalSearchResult(emptyList(), emptyList(), emptyList())
    )
    val localResults: StateFlow<ChatRepository.LocalSearchResult> = _localResults.asStateFlow()

    private val _accountResults = MutableStateFlow<List<RemoteProfile>>(emptyList())
    val accountResults: StateFlow<List<RemoteProfile>> = _accountResults.asStateFlow()

    private val _channelResults = MutableStateFlow<List<RemoteChannel>>(emptyList())
    val channelResults: StateFlow<List<RemoteChannel>> = _channelResults.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    fun signIn(email: String, password: String, onDone: (Boolean) -> Unit) = viewModelScope.launch {
        _busy.value = true
        val result = app.online.signIn(email, password)
        if (result.isSuccess) {
            runCatching { app.online.backfillAll() }
            runCatching { refreshDevices() }
            runCatching { app.online.syncNotificationPrefs(app.settings.snapshot()) }
        } else {
            _error.value = app.online.lastError.value ?: result.exceptionOrNull()?.message ?: "sign_in_failed"
        }
        _busy.value = false
        onDone(result.isSuccess)
    }

    fun signUp(email: String, password: String, displayName: String, onDone: (Boolean) -> Unit) =
        viewModelScope.launch {
            _busy.value = true
            val result = app.online.signUp(email, password, displayName)
            if (result.isSuccess) {
                runCatching { refreshDevices() }
                runCatching { app.online.syncNotificationPrefs(app.settings.snapshot()) }
            } else {
                _error.value = app.online.lastError.value ?: result.exceptionOrNull()?.message ?: "sign_up_failed"
            }
            _busy.value = false
            onDone(result.isSuccess)
        }

    fun signOut() = viewModelScope.launch {
        app.online.signOut()
        _devices.value = emptyList()
    }

    fun deleteAccount(onDone: (Boolean) -> Unit) = viewModelScope.launch {
        val result = app.online.deleteAccount()
        if (result.isFailure) _error.value = result.exceptionOrNull()?.message ?: "error_unknown"
        else _devices.value = emptyList()
        onDone(result.isSuccess)
    }

    fun updateOnlineProfile(displayName: String, username: String) = viewModelScope.launch {
        runCatching { app.online.updateProfile(displayName, username.ifBlank { null }) }
            .onFailure { _error.value = OnlineError.UNKNOWN }
    }

    fun refreshDevices() = viewModelScope.launch {
        runCatching { app.online.myDevices() }.onSuccess { _devices.value = it }
    }

    fun revokeDevice(rowId: String) = viewModelScope.launch {
        runCatching { app.online.revokeDevice(rowId) }.onFailure { _error.value = "error_unknown" }
        refreshDevices()
    }

    fun rotatePreKeysNow(onDone: (Boolean) -> Unit = {}) = viewModelScope.launch {
        val rotated = app.online.rotatePreKeysIfDue(force = true)
        onDone(rotated)
    }

    fun toggleBlockUser(userId: String) = viewModelScope.launch {
        if (blockedUsers.value.contains(userId)) {
            app.online.unblockUser(userId)
        } else {
            app.online.blockUser(userId)
        }
    }

    /** On-device search over decrypted data plus server search over public accounts/channels. */
    fun search(query: String) = viewModelScope.launch {
        _searching.value = true
        runCatching { app.chats.search(query) }
            .onSuccess { _localResults.value = it }
            .onFailure { android.util.Log.w("AppViewModel", "Local search failed", it) }
        if (query.trim().length >= 2 && app.online.isSignedIn) {
            runCatching { app.chats.searchAccounts(query.trim()) }
                .onSuccess { _accountResults.value = it }
            runCatching { app.chats.searchChannels(query.trim()) }
                .onSuccess { _channelResults.value = it }
        } else {
            _accountResults.value = emptyList()
            _channelResults.value = emptyList()
        }
        _searching.value = false
    }

    fun clearSearch() {
        _localResults.value = ChatRepository.LocalSearchResult(emptyList(), emptyList(), emptyList())
        _accountResults.value = emptyList()
        _channelResults.value = emptyList()
    }

    fun openAccountChat(userId: String, onReady: (String) -> Unit) = viewModelScope.launch {
        val id = runCatching { app.chats.openChatWithAccount(userId) }.getOrNull()
        if (id == null) _error.value = OnlineError.UNKNOWN else onReady(id)
    }

    fun createOnlineGroup(title: String, memberIds: List<String>, onCreated: (String) -> Unit) =
        viewModelScope.launch {
            val id = app.chats.createOnlineGroup(title, memberIds)
            if (id == null) _error.value = "error_unknown" else onCreated(id)
        }

    fun createChannel(title: String, username: String, isPublic: Boolean, onCreated: (String) -> Unit) =
        viewModelScope.launch {
            val id = app.chats.createChannel(title, username.ifBlank { null }, isPublic)
            if (id == null) _error.value = "error_unknown" else onCreated(id)
        }

    fun joinChannel(onlineId: String, onJoined: (String) -> Unit) = viewModelScope.launch {
        val id = app.chats.joinChannel(onlineId, null)
        if (id == null) _error.value = "error_unknown" else onJoined(id)
    }

    fun setDraft(conversationId: String, text: String) = viewModelScope.launch {
        runCatching { app.chats.saveDraft(conversationId, text) }
    }

    fun notifyTyping(conversationId: String) = viewModelScope.launch {
        runCatching { app.chats.notifyTyping(conversationId) }
    }

    fun toggleReaction(messageId: String, emoji: String) = viewModelScope.launch {
        val changed = runCatching { app.chats.toggleReaction(messageId, emoji) }.getOrDefault(false)
        if (!changed) _error.value = "reaction_unavailable"
    }

    fun editMessage(messageId: String, body: String) = viewModelScope.launch {
        if (!app.chats.editMessage(messageId, body)) _error.value = "edit_failed"
    }

    fun deleteForEveryone(messageId: String) = viewModelScope.launch {
        if (!app.chats.deleteForEveryone(messageId)) _error.value = "delete_failed"
    }

    fun setStarred(messageId: String, starred: Boolean) = viewModelScope.launch {
        app.chats.setStarred(messageId, starred)
    }

    fun starredMessages(): Flow<List<MessageEntity>> = app.chats.observeStarredMessages()

    val archivedConversations: StateFlow<List<ConversationEntity>> =
        app.chats.observeArchivedConversations()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val archivedCount: StateFlow<Int> =
        app.chats.observeArchivedCount()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    fun setConversationArchived(conversationId: String, archived: Boolean) = viewModelScope.launch {
        if (!app.chats.setConversationArchived(conversationId, archived)) {
            _error.value = "error_unknown"
        }
    }

    /** Removes a message from this device only (the peer keeps its copy). */
    fun deleteForMe(messageId: String) = viewModelScope.launch {
        if (!app.chats.deleteForMe(messageId)) _error.value = "error_unknown"
    }

    /**
     * Forwards a message into another chat. Returns nothing to the caller: the target chat's
     * message list is a database flow, so the new message appears there as soon as it is queued.
     */
    fun forwardMessage(messageId: String, targetConversationId: String) = viewModelScope.launch {
        if (!app.chats.forwardMessage(messageId, targetConversationId)) {
            _error.value = "forward_unavailable"
        }
    }

    // ------------------------------------------------------------------------ misc

    fun retryNow() = viewModelScope.launch { app.chats.retryPending() }

    /** Erases every message, contact and key on this device and starts over. */
    fun eraseAllData() = viewModelScope.launch {
        stopAudioPlayback()
        runCatching { app.online.signOut() }
        withContext(Dispatchers.IO) {
            runCatching { app.database.clearAllTables() }
            app.files.clearAll()
        }
        app.crypto.ensureIdentity()
        app.mesh.refreshIdentity()
        refreshPairingCode()
    }

    companion object {
        fun factory(app: MessengerApp): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    AppViewModel(app) as T
            }
    }
}
