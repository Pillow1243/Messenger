package com.offlinemesh.messenger.presentation

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.offlinemesh.messenger.MessengerApp
import com.offlinemesh.messenger.core.db.ContactEntity
import com.offlinemesh.messenger.core.db.ConversationEntity
import com.offlinemesh.messenger.core.db.MessageEntity
import com.offlinemesh.messenger.core.mesh.PeerInfo
import com.offlinemesh.messenger.data.AppSettings
import com.offlinemesh.messenger.data.ChatRepository
import com.offlinemesh.messenger.data.OnlineError
import com.offlinemesh.messenger.data.PairingCodec
import com.offlinemesh.messenger.data.RemoteChannel
import com.offlinemesh.messenger.data.RemoteDevice
import com.offlinemesh.messenger.data.RemoteProfile
import com.offlinemesh.messenger.domain.OnlineState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File

/**
 * Single view-model for the whole app: the data volume is tiny (local SQLCipher DB + a handful of
 * peers), so a shared state holder keeps the Compose layer simple and consistent.
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

    init {
        refreshPairingCode()
    }

    fun messages(conversationId: String): Flow<List<MessageEntity>> =
        app.chats.observeMessages(conversationId)

    fun conversation(conversationId: String): Flow<ConversationEntity?> =
        kotlinx.coroutines.flow.flow {
            emit(app.chats.conversation(conversationId))
        }

    fun clearError() {
        _error.value = null
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

    // --------------------------------------------------------------------- settings

    fun setDisplayName(name: String) = viewModelScope.launch {
        app.settings.setDisplayName(name)
        app.mesh.refreshIdentity()
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

    fun applyMeshPreference() = app.applyMeshPreference()

    // ------------------------------------------------------------------------ chats

    fun sendText(conversationId: String, text: String) = viewModelScope.launch {
        runCatching { app.chats.sendText(conversationId, text) }
            .onFailure { _error.value = "error_unknown" }
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
            .onFailure { _error.value = "attachment_pick_failed" }
    }

    fun markRead(conversationId: String) = viewModelScope.launch { app.chats.markRead(conversationId) }

    fun deleteConversation(conversationId: String) = viewModelScope.launch {
        app.chats.deleteConversation(conversationId)
    }

    fun createGroup(name: String, memberIds: List<String>, onCreated: (String) -> Unit) =
        viewModelScope.launch {
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
                    androidx.core.content.FileProvider.getUriForFile(
                        app,
                        "${app.packageName}.fileprovider",
                        file
                    )
                )
            }
        }

    // --------------------------------------------------------------------- contacts

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

    fun setVerified(deviceId: String, verified: Boolean) = viewModelScope.launch {
        app.contacts.setVerified(deviceId, verified)
    }

    fun resetTrust(deviceId: String) = viewModelScope.launch {
        app.contacts.resetTrust(deviceId)
        loadSafetyNumber(deviceId)
    }

    fun contact(deviceId: String): ContactEntity? = contacts.value.firstOrNull { it.deviceId == deviceId }

    // ------------------------------------------------------------------ online mode

    val onlineState: StateFlow<OnlineState> = app.online.state
    val networkAvailable: StateFlow<Boolean> = app.online.networkFlow()
    val onlineError: StateFlow<String?> = app.online.lastError
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
        } else {
            _error.value = result.exceptionOrNull()?.message ?: "sign_in_failed"
        }
        _busy.value = false
        onDone(result.isSuccess)
    }

    fun signUp(email: String, password: String, displayName: String, onDone: (Boolean) -> Unit) =
        viewModelScope.launch {
            _busy.value = true
            val result = app.online.signUp(email, password, displayName)
            if (result.isSuccess) runCatching { refreshDevices() }
            else _error.value = result.exceptionOrNull()?.message ?: "sign_up_failed"
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

    /** On-device search over decrypted data plus server search over public accounts/channels. */
    fun search(query: String) = viewModelScope.launch {
        _searching.value = true
        _localResults.value = app.chats.search(query)
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
        runCatching { app.chats.toggleReaction(messageId, emoji) }
            .onFailure { _error.value = "error_unknown" }
    }

    fun editMessage(messageId: String, body: String) = viewModelScope.launch {
        if (!app.chats.editMessage(messageId, body)) _error.value = "error_unknown"
    }

    fun deleteForEveryone(messageId: String) = viewModelScope.launch {
        if (!app.chats.deleteForEveryone(messageId)) _error.value = "error_unknown"
    }

    fun setStarred(messageId: String, starred: Boolean) = viewModelScope.launch {
        app.chats.setStarred(messageId, starred)
    }

    fun starredMessages(): Flow<List<MessageEntity>> = app.database.messageDao().observeStarred()

    // ------------------------------------------------------------------------ misc

    fun retryNow() = viewModelScope.launch { app.chats.retryPending() }

    /** Erases every message, contact and key on this device and starts over. */
    fun eraseAllData() = viewModelScope.launch {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
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
