package com.offlinemesh.messenger.presentation.viewmodel

import android.app.Application
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.offlinemesh.messenger.MessengerApp
import com.offlinemesh.messenger.core.db.ContactEntity
import com.offlinemesh.messenger.core.db.ConversationEntity
import com.offlinemesh.messenger.core.db.MessageEntity
import com.offlinemesh.messenger.core.mesh.PeerInfo
import com.offlinemesh.messenger.data.ReleaseInfo
import com.offlinemesh.messenger.data.UpdateChecker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

class OnboardingViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MessengerApp
    val displayName = MutableStateFlow("")
    val language = MutableStateFlow("en")

    fun save(onDone: () -> Unit) {
        viewModelScope.launch {
            app.settingsRepository.setDisplayName(displayName.value.trim())
            app.settingsRepository.setLanguage(language.value)
            app.settingsRepository.setOnboarded(true)
            app.meshManager.refreshIdentity()
            onDone()
        }
    }
}

class ChatListViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MessengerApp
    val conversations: Flow<List<ConversationEntity>> = app.chatRepository.observeConversations()
    val peers: StateFlow<List<PeerInfo>> = app.meshManager.peersFlow
    val isRunning: StateFlow<Boolean> = app.meshManager.isRunning

    fun startMesh() = app.meshManager.start()
    fun stopMesh() = app.meshManager.stop()

    fun openChat(peerDeviceId: String, displayName: String, onOpen: (String) -> Unit) {
        viewModelScope.launch {
            val id = app.chatRepository.ensureDirectConversation(peerDeviceId, displayName)
            onOpen(id)
        }
    }
}

class ChatViewModel(application: Application, savedStateHandle: SavedStateHandle) : AndroidViewModel(application) {
    private val app = application as MessengerApp
    val conversationId: String = savedStateHandle.get<String>("conversationId") ?: ""
    val messages: Flow<List<MessageEntity>> = app.chatRepository.observeMessages(conversationId)

    fun sendText(body: String) {
        viewModelScope.launch { app.chatRepository.sendText(conversationId, body) }
    }

    fun sendVoice(file: File, durationMs: Long) {
        viewModelScope.launch { app.chatRepository.sendVoice(conversationId, file, durationMs) }
    }

    fun sendFile(file: File, fileName: String) {
        viewModelScope.launch { app.chatRepository.sendFile(conversationId, file, fileName) }
    }

    fun markRead() {
        viewModelScope.launch { app.chatRepository.markRead(conversationId) }
    }
}

class DiscoveryViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MessengerApp
    val peers: StateFlow<List<PeerInfo>> = app.meshManager.peersFlow
    val isRunning: StateFlow<Boolean> = app.meshManager.isRunning

    fun startMesh() = app.meshManager.start()
    fun stopMesh() = app.meshManager.stop()

    fun openChat(peerDeviceId: String, displayName: String, onOpen: (String) -> Unit) {
        viewModelScope.launch {
            val id = app.chatRepository.ensureDirectConversation(peerDeviceId, displayName)
            onOpen(id)
        }
    }
}

class ContactsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MessengerApp
    val contacts: Flow<List<ContactEntity>> = app.contactRepository.observeContacts()

    fun addFromQr(text: String, onResult: (Boolean) -> Unit) {
        viewModelScope.launch {
            val payload = app.contactRepository.parseQrPayload(text)
            if (payload == null) {
                onResult(false)
                return@launch
            }
            app.contactRepository.addContact(payload.deviceId, payload.name, payload.identityKey)
            onResult(true)
        }
    }

    fun openChat(contact: ContactEntity, onOpen: (String) -> Unit) {
        viewModelScope.launch {
            val id = app.chatRepository.ensureDirectConversation(contact.deviceId, contact.displayName)
            onOpen(id)
        }
    }
}

class SettingsViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MessengerApp
    val displayName: Flow<String> = app.settingsRepository.displayName
    val language: Flow<String> = app.settingsRepository.language
    val theme: Flow<String> = app.settingsRepository.theme
    val bleEnabled: Flow<Boolean> = app.settingsRepository.bleEnabled
    val wifiEnabled: Flow<Boolean> = app.settingsRepository.wifiEnabled
    val batteryMode: Flow<String> = app.settingsRepository.batteryMode

    private val _updateState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val updateState: StateFlow<UpdateUiState> = _updateState.asStateFlow()

    private val _fingerprint = MutableStateFlow("")
    val fingerprint: StateFlow<String> = _fingerprint.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                _fingerprint.value = app.cryptoManager.myFingerprint()
            } catch (e: Exception) {
                _fingerprint.value = ""
            }
        }
    }

    fun setDisplayName(name: String) {
        viewModelScope.launch {
            app.settingsRepository.setDisplayName(name)
            app.meshManager.refreshIdentity()
        }
    }

    fun setLanguage(lang: String) {
        viewModelScope.launch { app.settingsRepository.setLanguage(lang) }
    }

    fun setTheme(theme: String) {
        viewModelScope.launch { app.settingsRepository.setTheme(theme) }
    }

    fun setBle(enabled: Boolean) {
        viewModelScope.launch { app.settingsRepository.setBleEnabled(enabled) }
    }

    fun setWifi(enabled: Boolean) {
        viewModelScope.launch { app.settingsRepository.setWifiEnabled(enabled) }
    }

    fun setBatteryMode(mode: String) {
        viewModelScope.launch { app.settingsRepository.setBatteryMode(mode) }
    }

    fun checkUpdates() {
        _updateState.value = UpdateUiState.Checking
        viewModelScope.launch {
            val checker = UpdateChecker()
            val release = checker.checkLatestRelease()
            if (release == null) {
                _updateState.value = UpdateUiState.Offline
            } else if (checker.isNewer("1.0.0", release.tagName)) {
                _updateState.value = UpdateUiState.Available(release)
            } else {
                _updateState.value = UpdateUiState.UpToDate
            }
        }
    }

    fun myQrPayload(): String {
        return viewModelScope.launch {
        }.let {
            ""
        }
    }

    fun buildMyQr(): String {
        return try {
            val deviceId = kotlinx.coroutines.runBlocking { app.settingsRepository.getDeviceId() }
            val name = kotlinx.coroutines.runBlocking { app.settingsRepository.getDisplayName() }
            app.contactRepository.buildQrPayload(deviceId, name)
        } catch (e: Exception) {
            ""
        }
    }
}

sealed interface UpdateUiState {
    object Idle : UpdateUiState
    object Checking : UpdateUiState
    object UpToDate : UpdateUiState
    object Offline : UpdateUiState
    data class Available(val release: ReleaseInfo) : UpdateUiState
}

class GroupCreateViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as MessengerApp
    val contacts: Flow<List<ContactEntity>> = app.contactRepository.observeContacts()
    val groupName = MutableStateFlow("")
    val selected = MutableStateFlow<Set<String>>(emptySet())

    fun toggle(deviceId: String) {
        val current = selected.value.toMutableSet()
        if (current.contains(deviceId)) current.remove(deviceId) else current.add(deviceId)
        selected.value = current
    }

    fun create(onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val groupId = java.util.UUID.randomUUID().toString()
            val members = selected.value.toList()
            val id = app.chatRepository.ensureGroupConversation(groupId, groupName.value.trim(), members)
            onCreated(id)
        }
    }
}
