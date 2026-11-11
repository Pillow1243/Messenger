package com.offlinemesh.messenger

import android.app.Application
import android.util.Log
import com.offlinemesh.messenger.core.crypto.CryptoManager
import com.offlinemesh.messenger.core.crypto.SignalStore
import com.offlinemesh.messenger.core.db.AppDatabase
import com.offlinemesh.messenger.core.db.DatabaseFactory
import com.offlinemesh.messenger.core.mesh.MeshManager
import com.offlinemesh.messenger.core.mesh.RadioController
import com.offlinemesh.messenger.data.ChatRepository
import com.offlinemesh.messenger.data.ContactRepository
import com.offlinemesh.messenger.data.FileRepository
import com.offlinemesh.messenger.data.SettingsRepository
import com.offlinemesh.messenger.service.MeshService
import com.offlinemesh.messenger.work.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MessengerApp : Application() {

    lateinit var settings: SettingsRepository
        private set
    lateinit var database: AppDatabase
        private set
    lateinit var crypto: CryptoManager
        private set
    lateinit var mesh: MeshManager
        private set
    lateinit var files: FileRepository
        private set
    lateinit var contacts: ContactRepository
        private set
    lateinit var chats: ChatRepository
        private set
    lateinit var radios: RadioController
        private set

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        settings = SettingsRepository(this)
        database = DatabaseFactory.get(this)
        crypto = CryptoManager(database, SignalStore(database))
        files = FileRepository(this)
        contacts = ContactRepository(database, crypto, settings)
        mesh = MeshManager(this, settings, crypto)
        chats = ChatRepository(database, crypto, mesh, files, settings, contacts)
        radios = RadioController(mesh, settings)
        mesh.setHandler(chats)

        appScope.launch {
            runCatching {
                crypto.ensureIdentity()
                mesh.refreshIdentity()
                val snapshot = settings.settings.first()
                if (snapshot.onboarded) {
                    if (snapshot.meshEnabled && snapshot.radiosUsable) startMesh() else stopMesh()
                }
                chats.retryPending()
            }.onFailure { Log.e(TAG, "Startup failed", it) }
        }
        SyncWorker.schedule(this)
    }

    /** Starts advertising/discovery and (optionally) the foreground service. */
    fun startMesh() {
        radios.start()
        appScope.launch { mesh.start() }
        if (settings.settings.first().backgroundService) {
            runCatching { MeshService.start(this) }
                .onFailure { Log.w(TAG, "Could not start foreground service", it) }
        }
    }

    fun stopMesh() {
        radios.stop()
        mesh.stop()
        runCatching { MeshService.stop(this) }
    }

    fun applyMeshPreference() {
        appScope.launch {
            val snapshot = settings.settings.first()
            if (snapshot.meshEnabled && snapshot.radiosUsable) {
                mesh.refreshIdentity()
                radios.start()
                mesh.start()
                if (snapshot.backgroundService) runCatching { MeshService.start(this@MessengerApp) }
            } else {
                stopMesh()
            }
        }
    }

    private companion object {
        const val TAG = "MessengerApp"
    }
}
