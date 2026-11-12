package com.offlinemesh.messenger

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.offlinemesh.messenger.presentation.AppViewModel
import com.offlinemesh.messenger.presentation.navigation.NavGraph
import com.offlinemesh.messenger.presentation.theme.OfflineMeshTheme
import com.offlinemesh.messenger.domain.LaunchIntentPolicy
import com.offlinemesh.messenger.service.MessageNotifier
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val app: MessengerApp get() = application as MessengerApp

    /** Conversation requested by a notification tap or deep link, consumed once by NavGraph. */
    private val pendingConversation = mutableStateOf<String?>(null)
    private val pendingCallId = mutableStateOf<String?>(null)
    private val pendingCallAction = mutableStateOf<String?>(null)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        requestMeshPermissions()
        extractIntentTargets(intent)

        setContent {
            val vm: AppViewModel = viewModel(factory = AppViewModel.factory(app))
            val settings by vm.settings.collectAsStateWithLifecycle()
            val themeMode = settings?.theme ?: "system"
            val darkTheme = when (themeMode) {
                "dark" -> true
                "light" -> false
                else -> isSystemInDarkTheme()
            }

            LaunchedEffect(settings?.language) {
                applyLanguage(settings?.language ?: "system")
            }

            LaunchedEffect(pendingCallId.value, pendingCallAction.value) {
                val cid = pendingCallId.value
                val action = pendingCallAction.value
                if (!cid.isNullOrBlank()) {
                    if (action == MessageNotifier.CALL_ACTION_ANSWER) {
                        vm.acceptIncomingCall(cid)
                    }
                    pendingCallId.value = null
                    pendingCallAction.value = null
                }
            }

            OfflineMeshTheme(darkTheme = darkTheme) {
                NavGraph(
                    vm = vm,
                    startConversation = pendingConversation.value,
                    onStartConsumed = { pendingConversation.value = null }
                )
            }
        }

        app.appScope.launch { runCatching { app.chats.retryPending() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractIntentTargets(intent)
    }

    private fun extractIntentTargets(intent: Intent?) {
        if (intent == null) return
        // This activity is exported, so the intent may come from any app or web page. See
        // LaunchIntentPolicy for what such an intent is allowed to do.
        LaunchIntentPolicy.safeId(intent.getStringExtra(MessageNotifier.EXTRA_CONVERSATION))?.let {
            pendingConversation.value = it
        }
        LaunchIntentPolicy.safeId(intent.getStringExtra(MessageNotifier.EXTRA_CALL_ID))?.let {
            val action = intent.getStringExtra(MessageNotifier.EXTRA_CALL_ACTION)
            val trusted = LaunchIntentPolicy.mayAnswer(
                action,
                intent.getStringExtra(MessageNotifier.EXTRA_LAUNCH_TOKEN),
                MessageNotifier.launchToken(this)
            )
            pendingCallId.value = it
            pendingCallAction.value = if (trusted) MessageNotifier.CALL_ACTION_ANSWER else MessageNotifier.CALL_ACTION_OPEN
        }
        // `offlinemesh://chat/<id>` and `offlinemesh://call/<id>` links only ever open a screen.
        intent.data?.takeIf { it.isHierarchical }?.let { uri ->
            val target = LaunchIntentPolicy.fromUri(
                uri.scheme,
                uri.host,
                uri.lastPathSegment,
                runCatching { uri.getQueryParameter("id") }.getOrNull()
            )
            target?.conversationId?.let { pendingConversation.value = it }
            target?.callId?.let {
                pendingCallId.value = it
                pendingCallAction.value = MessageNotifier.CALL_ACTION_OPEN
            }
        }
    }

    override fun onResume() {
        super.onResume()
        requestMeshPermissions()
        app.appScope.launch { runCatching { app.chats.retryPending() } }
    }

    private fun applyLanguage(language: String) {
        val desired = when (language) {
            "en" -> "en"
            "fa" -> "fa"
            else -> ""
        }
        val current = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        if (desired.isEmpty()) {
            if (current.isNotEmpty()) {
                AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
            }
        } else if (!current.startsWith(desired)) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(desired))
        }
    }

    private fun requiredPermissions(): Array<String> {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
        } else {
            @Suppress("DEPRECATION")
            permissions.add(Manifest.permission.BLUETOOTH)
            @Suppress("DEPRECATION")
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN)
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        permissions.add(Manifest.permission.RECORD_AUDIO)
        permissions.add(Manifest.permission.CAMERA)
        return permissions.toTypedArray()
    }

    private fun requestMeshPermissions() {
        val missing = requiredPermissions().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
        }
    }
}
