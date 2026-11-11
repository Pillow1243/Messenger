package com.offlinemesh.messenger.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reports whether the device currently has a validated internet path.
 *
 * This only decides *which transport to prefer*. The mesh never consults it: Nearby Connections
 * works with every radio on and no internet, so the offline path stays independent.
 */
class ConnectivityMonitor(context: Context) {

    private val manager: ConnectivityManager? =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    private val _online = MutableStateFlow(currentlyOnline())
    val online: StateFlow<Boolean> = _online.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _online.value = currentlyOnline(network)
        }

        override fun onLost(network: Network) {
            _online.value = currentlyOnline()
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            _online.value = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
    }

    private var registered = false

    fun start() {
        val cm = manager ?: return
        if (registered) return
        runCatching { cm.registerDefaultNetworkCallback(callback) }
            .onSuccess { registered = true }
            .onFailure { Log.w(TAG, "Could not register network callback", it) }
        _online.value = currentlyOnline()
    }

    fun stop() {
        val cm = manager ?: return
        if (!registered) return
        runCatching { cm.unregisterNetworkCallback(callback) }
        registered = false
    }

    private fun currentlyOnline(network: Network? = null): Boolean {
        val cm = manager ?: return false
        val active = network ?: cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(active) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private companion object {
        const val TAG = "ConnectivityMonitor"
    }
}
