package com.offlinemesh.messenger

import android.Manifest
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
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.offlinemesh.messenger.presentation.AppViewModel
import com.offlinemesh.messenger.presentation.navigation.NavGraph
import com.offlinemesh.messenger.presentation.theme.OfflineMeshTheme
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private val app: MessengerApp get() = application as MessengerApp

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        requestMeshPermissions()

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

            OfflineMeshTheme(darkTheme = darkTheme) {
                NavGraph(vm = vm)
            }
        }

        // A fresh launch is a good moment to push anything still queued.
        app.appScope.launch { runCatching { app.chats.retryPending() } }
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
