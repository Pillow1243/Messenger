package com.offlinemesh.messenger

import android.app.Application
import com.offlinemesh.messenger.core.crypto.CryptoManager
import com.offlinemesh.messenger.core.crypto.SignalStore
import com.offlinemesh.messenger.core.db.DatabaseFactory
import com.offlinemesh.messenger.core.mesh.MeshManager
import com.offlinemesh.messenger.data.ChatRepository
import com.offlinemesh.messenger.data.ContactRepository
import com.offlinemesh.messenger.data.FileRepository
import com.offlinemesh.messenger.data.SettingsRepository
import com.offlinemesh.messenger.work.HeartbeatWorker
import com.offlinemesh.messenger.work.SyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MessengerApp : Application() {

    lateinit var settingsRepository: SettingsRepository
        private set
    lateinit var cryptoManager: CryptoManager
        private set
    lateinit var meshManager: MeshManager
        private set
    lateinit var chatRepository: ChatRepository
        private set
    lateinit var contactRepository: ContactRepository
        private set
    lateinit var fileRepository: FileRepository
        private set

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        settingsRepository = SettingsRepository(this)
        val database = DatabaseFactory.create(this)
        val signalStore = SignalStore(database)
        cryptoManager = CryptoManager(database, signalStore)
        fileRepository = FileRepository(this)
        meshManager = MeshManager(this, settingsRepository, cryptoManager)
        chatRepository = ChatRepository(this, database, cryptoManager, meshManager, fileRepository, settingsRepository)
        contactRepository = ContactRepository(database, cryptoManager)
        meshManager.setPacketHandler(chatRepository)
        appScope.launch {
            cryptoManager.ensureIdentityKeys()
            meshManager.initIdentity()
        }
        SyncWorker.enqueuePeriodic(this)
        HeartbeatWorker.enqueuePeriodic(this)
    }
}
