package com.offlinemesh.messenger.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "mesh_settings")

/** Everything the UI and the mesh service need to know about the user's choices. */
data class AppSettings(
    val deviceId: String = "",
    val displayName: String = "",
    val language: String = "system",
    val theme: String = "system",
    val meshEnabled: Boolean = true,
    val bluetoothEnabled: Boolean = true,
    val wifiEnabled: Boolean = true,
    val batterySaver: Boolean = false,
    val backgroundService: Boolean = true,
    val onboarded: Boolean = false,
    val notificationsEnabled: Boolean = true,
    val notifyDirect: Boolean = true,
    val notifyGroups: Boolean = true,
    val notifyChannels: Boolean = false,
    val notifyCalls: Boolean = true
) {
    /** Nearby Connections picks transports itself; with both connectors off the mesh is pointless. */
    val radiosUsable: Boolean get() = bluetoothEnabled || wifiEnabled
}

class SettingsRepository(
    private val context: Context,
    /**
     * Fixed device id instead of the stored one. Only for tests that run the message layer of
     * several devices inside one process, where they would otherwise share a single id.
     */
    private val deviceIdOverride: String? = null
) {

    private object Keys {
        val DEVICE_ID = stringPreferencesKey("device_id")
        val DISPLAY_NAME = stringPreferencesKey("display_name")
        val LANGUAGE = stringPreferencesKey("language")
        val THEME = stringPreferencesKey("theme")
        val MESH = booleanPreferencesKey("mesh_enabled")
        val BLUETOOTH = booleanPreferencesKey("bluetooth_enabled")
        val WIFI = booleanPreferencesKey("wifi_enabled")
        val BATTERY_SAVER = booleanPreferencesKey("battery_saver")
        val BACKGROUND = booleanPreferencesKey("background_service")
        val ONBOARDED = booleanPreferencesKey("onboarded")
        val NOTIFICATIONS_ENABLED = booleanPreferencesKey("notifications_enabled")
        val NOTIFY_DIRECT = booleanPreferencesKey("notify_direct")
        val NOTIFY_GROUPS = booleanPreferencesKey("notify_groups")
        val NOTIFY_CHANNELS = booleanPreferencesKey("notify_channels")
        val NOTIFY_CALLS = booleanPreferencesKey("notify_calls")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        AppSettings(
            deviceId = prefs[Keys.DEVICE_ID] ?: "",
            displayName = prefs[Keys.DISPLAY_NAME] ?: "",
            language = prefs[Keys.LANGUAGE] ?: "system",
            theme = prefs[Keys.THEME] ?: "system",
            meshEnabled = prefs[Keys.MESH] ?: true,
            bluetoothEnabled = prefs[Keys.BLUETOOTH] ?: true,
            wifiEnabled = prefs[Keys.WIFI] ?: true,
            batterySaver = prefs[Keys.BATTERY_SAVER] ?: false,
            backgroundService = prefs[Keys.BACKGROUND] ?: true,
            onboarded = prefs[Keys.ONBOARDED] ?: false,
            notificationsEnabled = prefs[Keys.NOTIFICATIONS_ENABLED] ?: true,
            notifyDirect = prefs[Keys.NOTIFY_DIRECT] ?: true,
            notifyGroups = prefs[Keys.NOTIFY_GROUPS] ?: true,
            notifyChannels = prefs[Keys.NOTIFY_CHANNELS] ?: false,
            notifyCalls = prefs[Keys.NOTIFY_CALLS] ?: true
        )
    }

    suspend fun snapshot(): AppSettings = settings.first()

    /** Device id is generated once and persists for the lifetime of the installation. */
    suspend fun deviceId(): String {
        deviceIdOverride?.let { return it }
        val existing = context.dataStore.data.map { it[Keys.DEVICE_ID] }.first()
        if (!existing.isNullOrBlank()) return existing
        val generated = UUID.randomUUID().toString()
        context.dataStore.edit { it[Keys.DEVICE_ID] = generated }
        return generated
    }

    suspend fun setDisplayName(name: String) = context.dataStore.edit { it[Keys.DISPLAY_NAME] = name.trim() }

    suspend fun setLanguage(language: String) = context.dataStore.edit { it[Keys.LANGUAGE] = language }

    suspend fun setTheme(theme: String) = context.dataStore.edit { it[Keys.THEME] = theme }

    suspend fun setMeshEnabled(enabled: Boolean) = context.dataStore.edit { it[Keys.MESH] = enabled }

    suspend fun setBluetoothEnabled(enabled: Boolean) = context.dataStore.edit { it[Keys.BLUETOOTH] = enabled }

    suspend fun setWifiEnabled(enabled: Boolean) = context.dataStore.edit { it[Keys.WIFI] = enabled }

    suspend fun setBatterySaver(enabled: Boolean) = context.dataStore.edit { it[Keys.BATTERY_SAVER] = enabled }

    suspend fun setBackgroundService(enabled: Boolean) =
        context.dataStore.edit { it[Keys.BACKGROUND] = enabled }

    suspend fun setOnboarded(value: Boolean) = context.dataStore.edit { it[Keys.ONBOARDED] = value }

    suspend fun setNotificationsEnabled(enabled: Boolean) =
        context.dataStore.edit { it[Keys.NOTIFICATIONS_ENABLED] = enabled }

    suspend fun setNotifyDirect(enabled: Boolean) =
        context.dataStore.edit { it[Keys.NOTIFY_DIRECT] = enabled }

    suspend fun setNotifyGroups(enabled: Boolean) =
        context.dataStore.edit { it[Keys.NOTIFY_GROUPS] = enabled }

    suspend fun setNotifyChannels(enabled: Boolean) =
        context.dataStore.edit { it[Keys.NOTIFY_CHANNELS] = enabled }

    suspend fun setNotifyCalls(enabled: Boolean) =
        context.dataStore.edit { it[Keys.NOTIFY_CALLS] = enabled }
}
