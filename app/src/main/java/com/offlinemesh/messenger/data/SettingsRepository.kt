package com.offlinemesh.messenger.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.offlinemesh.messenger.core.mesh.BatteryMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    companion object {
        private val KEY_DEVICE_ID = stringPreferencesKey("device_id")
        private val KEY_DISPLAY_NAME = stringPreferencesKey("display_name")
        private val KEY_LANGUAGE = stringPreferencesKey("language")
        private val KEY_THEME = stringPreferencesKey("theme")
        private val KEY_BLE = booleanPreferencesKey("ble_enabled")
        private val KEY_WIFI = booleanPreferencesKey("wifi_direct_enabled")
        private val KEY_BATTERY_MODE = stringPreferencesKey("battery_mode")
        private val KEY_ONBOARDED = booleanPreferencesKey("onboarded")
    }

    val displayName: Flow<String> = context.dataStore.data.map { it[KEY_DISPLAY_NAME] ?: "" }
    val language: Flow<String> = context.dataStore.data.map { it[KEY_LANGUAGE] ?: "en" }
    val theme: Flow<String> = context.dataStore.data.map { it[KEY_THEME] ?: "system" }
    val bleEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_BLE] ?: true }
    val wifiEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_WIFI] ?: true }
    val batteryMode: Flow<String> = context.dataStore.data.map { it[KEY_BATTERY_MODE] ?: "balanced" }
    val onboarded: Flow<Boolean> = context.dataStore.data.map { it[KEY_ONBOARDED] ?: false }

    suspend fun getDeviceId(): String {
        val existing = context.dataStore.data.map { it[KEY_DEVICE_ID] }.first()
        if (existing != null) return existing
        val id = UUID.randomUUID().toString()
        context.dataStore.edit { it[KEY_DEVICE_ID] = id }
        return id
    }

    suspend fun getDisplayName(): String =
        context.dataStore.data.map { it[KEY_DISPLAY_NAME] ?: "" }.first()

    suspend fun setDisplayName(name: String) {
        context.dataStore.edit { it[KEY_DISPLAY_NAME] = name }
    }

    suspend fun setLanguage(lang: String) {
        context.dataStore.edit { it[KEY_LANGUAGE] = lang }
    }

    suspend fun setTheme(theme: String) {
        context.dataStore.edit { it[KEY_THEME] = theme }
    }

    suspend fun setBleEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_BLE] = enabled }
    }

    suspend fun setWifiEnabled(enabled: Boolean) {
        context.dataStore.edit { it[KEY_WIFI] = enabled }
    }

    suspend fun setBatteryMode(mode: String) {
        context.dataStore.edit { it[KEY_BATTERY_MODE] = mode }
    }

    suspend fun setOnboarded(value: Boolean) {
        context.dataStore.edit { it[KEY_ONBOARDED] = value }
    }

    suspend fun isBleEnabled(): Boolean =
        context.dataStore.data.map { it[KEY_BLE] ?: true }.first()

    suspend fun isWifiDirectEnabled(): Boolean =
        context.dataStore.data.map { it[KEY_WIFI] ?: true }.first()

    suspend fun getBatteryMode(): BatteryMode {
        val raw = context.dataStore.data.map { it[KEY_BATTERY_MODE] ?: "balanced" }.first()
        return when (raw) {
            "performance" -> BatteryMode.PERFORMANCE
            "saver" -> BatteryMode.SAVER
            else -> BatteryMode.BALANCED
        }
    }
}
