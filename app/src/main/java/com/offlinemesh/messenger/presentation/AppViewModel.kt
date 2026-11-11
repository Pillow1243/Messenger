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
import com.offlinemesh.messenger.data.PairingCodec
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
