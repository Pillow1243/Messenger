package com.offlinemesh.messenger.core.mesh

import com.offlinemesh.messenger.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

enum class BatteryMode {
    PERFORMANCE,
    BALANCED,
    SAVER
}

class HeartbeatManager(
    private val mesh: MeshManager,
    private val settings: SettingsRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                val ble = settings.isBleEnabled()
                val wifi = settings.isWifiDirectEnabled()
                if (!ble && !wifi) {
                    mesh.stop()
                    delay(5000)
                    continue
                }
                when (settings.getBatteryMode()) {
                    BatteryMode.PERFORMANCE -> {
                        mesh.start()
                        delay(30_000)
                    }
                    BatteryMode.BALANCED -> {
                        mesh.start()
                        delay(30_000)
                        mesh.stop()
                        delay(30_000)
                    }
                    BatteryMode.SAVER -> {
                        mesh.start()
                        delay(15_000)
                        mesh.stop()
                        delay(45_000)
                    }
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
        mesh.stop()
    }
}
