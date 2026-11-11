package com.offlinemesh.messenger.push

import android.content.Context
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.offlinemesh.messenger.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

enum class PushProviderStatus {
    UNCONFIGURED,
    INITIALIZED,
    REGISTERED,
    ERROR
}

typealias PushRegistrationStatus = PushProviderStatus

/**
 * Programmatic Firebase Cloud Messaging lifecycle helper.
 *
 * Supports two credential sources without requiring a committed `google-services.json`:
 *  1. Standard Android string resources (`google_app_id`, `google_api_key`, `project_id`,
 *     `gcm_defaultSenderId`) if `google-services.json` is dropped into `app/`.
 *  2. Build-time Gradle properties / environment variables (`FIREBASE_APP_ID`,
 *     `FIREBASE_API_KEY`, `FIREBASE_PROJECT_ID`, `FIREBASE_SENDER_ID`).
 *
 * When neither is present, the app runs cleanly with `UNCONFIGURED` status and relies on
 * Supabase Realtime while foregrounded — never faking push delivery.
 */
object FirebasePushInitializer {

    private val _status = MutableStateFlow(PushProviderStatus.UNCONFIGURED)
    val status: StateFlow<PushProviderStatus> = _status.asStateFlow()

    fun ensureInitialized(context: Context): Boolean {
        val appContext = context.applicationContext
        runCatching {
            if (FirebaseApp.getApps(appContext).isNotEmpty()) {
                if (_status.value == PushProviderStatus.UNCONFIGURED) {
                    _status.value = PushProviderStatus.INITIALIZED
                }
                return true
            }
            val options = resolveOptions(appContext) ?: run {
                _status.value = PushProviderStatus.UNCONFIGURED
                return false
            }
            FirebaseApp.initializeApp(appContext, options)
            FirebaseMessaging.getInstance().isAutoInitEnabled = true
            _status.value = PushProviderStatus.INITIALIZED
            return true
        }.onFailure {
            _status.value = PushProviderStatus.ERROR
        }
        return false
    }

    suspend fun fetchCurrentToken(context: Context): String? = withContext(Dispatchers.IO) {
        if (!ensureInitialized(context)) return@withContext null
        runCatching {
            Tasks.await(FirebaseMessaging.getInstance().token, 12, TimeUnit.SECONDS)
                ?.takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    suspend fun deleteLocalToken(context: Context) = withContext(Dispatchers.IO) {
        if (FirebaseApp.getApps(context.applicationContext).isEmpty()) return@withContext
        runCatching {
            Tasks.await(FirebaseMessaging.getInstance().deleteToken(), 8, TimeUnit.SECONDS)
        }
        _status.value = PushProviderStatus.INITIALIZED
    }

    fun markRegistered() {
        _status.value = PushProviderStatus.REGISTERED
    }

    private fun resolveOptions(context: Context): FirebaseOptions? {
        val appId = stringResOrBlank(context, "google_app_id").ifBlank { BuildConfig.FIREBASE_APP_ID }
        val apiKey = stringResOrBlank(context, "google_api_key").ifBlank { BuildConfig.FIREBASE_API_KEY }
        val projectId = stringResOrBlank(context, "project_id").ifBlank { BuildConfig.FIREBASE_PROJECT_ID }
        val senderId = stringResOrBlank(context, "gcm_defaultSenderId").ifBlank { BuildConfig.FIREBASE_SENDER_ID }

        if (appId.isBlank() || apiKey.isBlank() || projectId.isBlank() || senderId.isBlank()) {
            return null
        }
        return FirebaseOptions.Builder()
            .setApplicationId(appId)
            .setApiKey(apiKey)
            .setProjectId(projectId)
            .setGcmSenderId(senderId)
            .build()
    }

    private fun stringResOrBlank(context: Context, name: String): String {
        val resId = context.resources.getIdentifier(name, "string", context.packageName)
        return if (resId != 0) runCatching { context.getString(resId) }.getOrDefault("") else ""
    }
}
