@file:OptIn(ExperimentalMaterial3Api::class)

package com.offlinemesh.messenger.presentation.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.offlinemesh.messenger.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Renders the third-party notices that ship inside the APK.
 *
 * The asset is generated from `THIRD_PARTY_NOTICES.md` by `tools/build_licence_asset.py` and a CI
 * check fails when the two drift, so what a user reads here is exactly the inventory of what the
 * build links. Attribution for the bundled SQLCipher (BSD), libsignal (AGPL-3.0) and the Apache-2.0
 * AndroidX/Compose libraries has to be reachable from inside the app, not only from the repository,
 * which is what this screen provides.
 */
@Composable
fun LicencesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val notices by produceState<String?>(initialValue = null) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open(NOTICES_ASSET).bufferedReader().use { it.readText() }
            }.getOrNull()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.licences_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            val body = notices
            if (body == null) {
                Text(
                    text = stringResource(R.string.licences_loading),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                // Markdown is shown verbatim rather than rendered: this is a legal notice, and a
                // reader must see exactly the text that ships in the build.
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

private const val NOTICES_ASSET = "licenses/THIRD_PARTY_NOTICES.md"
