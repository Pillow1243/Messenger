package com.offlinemesh.messenger.core.mesh

import com.offlinemesh.messenger.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Duty-cycle controller for the radios.
 *
 * Google Nearby decides which transport (BLE / Wi-Fi Direct) carries a link, so what we can
 * actually control is *when* we advertise and discover. Battery saver shortens discovery windows
 * while keeping existing links alive, which is what costs the most power.
 */
class RadioController(
    private val mesh: MeshManager,
    private val settings: SettingsRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            while (isActive) {
                val snapshot = settings.snapshot()
                if (!snapshot.meshEnabled || !snapshot.radiosUsable) {
                    mesh.pause()
                    delay(IDLE_POLL_MS)
                    continue
                }
                mesh.start()
                if (snapshot.batterySaver) {
                    mesh.setDiscoveryActive(true)
                    delay(SAVER_ON_MS)
                    mesh.setDiscoveryActive(false)
                    delay(SAVER_OFF_MS)
                } else {
                    mesh.setDiscoveryActive(true)
                    delay(PERFORMANCE_TICK_MS)
                }
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private companion object {
        const val IDLE_POLL_MS = 5_000L
        const val PERFORMANCE_TICK_MS = 30_000L
        const val SAVER_ON_MS = 10_000L
        const val SAVER_OFF_MS = 50_000L
    }
}
