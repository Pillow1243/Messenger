package com.offlinemesh.messenger.presentation.screens

import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.offlinemesh.messenger.BuildConfig
import com.offlinemesh.messenger.R
import com.offlinemesh.messenger.presentation.viewmodel.SettingsViewModel
import com.offlinemesh.messenger.presentation.viewmodel.UpdateUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val vm: SettingsViewModel = viewModel()
    val context = LocalContext.current
    val displayName by vm.displayName.collectAsState(initial = "")
    val language by vm.language.collectAsState(initial = "en")
    val theme by vm.theme.collectAsState(initial = "system")
    val ble by vm.bleEnabled.collectAsState(initial = true)
    val wifi by vm.wifiEnabled.collectAsState(initial = true)
    val batteryMode by vm.batteryMode.collectAsState(initial = "balanced")
    val updateState by vm.updateState.collectAsState()
    val fingerprint by vm.fingerprint.collectAsState()
    var nameDraft by remember(displayName) { mutableStateOf(displayName) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = null)
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
                .padding(16.dp)
        ) {
            SectionTitle(stringResource(R.string.profile))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = nameDraft,
                        onValueChange = { nameDraft = it },
                        label = { Text(stringResource(R.string.display_name_label)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(onClick = { vm.setDisplayName(nameDraft) }) {
                        Text(stringResource(R.string.save))
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            SectionTitle(stringResource(R.string.mesh_radios))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    ToggleRow(
                        title = stringResource(R.string.ble_toggle),
                        subtitle = stringResource(R.string.ble_subtitle),
                        checked = ble,
                        onCheckedChange = vm::setBle
                    )
                    ToggleRow(
                        title = stringResource(R.string.wifi_direct_toggle),
                        subtitle = stringResource(R.string.wifi_direct_subtitle),
                        checked = wifi,
                        onCheckedChange = vm::setWifi
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.battery_mode), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.battery_subtitle), style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Row {
                        ModeButton(
                            label = stringResource(R.string.battery_performance),
                            selected = batteryMode == "performance",
                            onClick = { vm.setBatteryMode("performance") }
                        )
                        Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                        ModeButton(
                            label = stringResource(R.string.battery_balanced),
                            selected = batteryMode == "balanced",
                            onClick = { vm.setBatteryMode("balanced") }
                        )
                        Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                        ModeButton(
                            label = stringResource(R.string.battery_saver),
                            selected = batteryMode == "saver",
                            onClick = { vm.setBatteryMode("saver") }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            SectionTitle(stringResource(R.string.appearance))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.theme), style = MaterialTheme.typography.titleSmall)
                    Row {
                        ModeButton(
                            label = stringResource(R.string.theme_system),
                            selected = theme == "system",
                            onClick = { vm.setTheme("system") }
                        )
                        Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                        ModeButton(
                            label = stringResource(R.string.theme_light),
                            selected = theme == "light",
                            onClick = { vm.setTheme("light") }
                        )
                        Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                        ModeButton(
                            label = stringResource(R.string.theme_dark),
                            selected = theme == "dark",
                            onClick = { vm.setTheme("dark") }
                        )
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(stringResource(R.string.language), style = MaterialTheme.typography.titleSmall)
                    Row {
                        ModeButton(
                            label = stringResource(R.string.language_english),
                            selected = language == "en",
                            onClick = {
                                vm.setLanguage("en")
                                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("en"))
                            }
                        )
                        Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                        ModeButton(
                            label = stringResource(R.string.language_persian),
                            selected = language == "fa",
                            onClick = {
                                vm.setLanguage("fa")
                                AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("fa"))
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            SectionTitle(stringResource(R.string.security))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.encryption_status), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.encryption_on), style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.database_status), style = MaterialTheme.typography.titleSmall)
                    Text(stringResource(R.string.database_encrypted), style = MaterialTheme.typography.bodySmall)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.fingerprint), style = MaterialTheme.typography.titleSmall)
                    Text(fingerprint, style = MaterialTheme.typography.bodySmall)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            SectionTitle(stringResource(R.string.updates))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Button(onClick = { vm.checkUpdates() }) {
                        Text(stringResource(R.string.check_updates))
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    when (val state = updateState) {
                        is UpdateUiState.Idle -> {
                        }
                        is UpdateUiState.Checking -> Text(stringResource(R.string.checking))
                        is UpdateUiState.UpToDate -> Text(stringResource(R.string.up_to_date))
                        is UpdateUiState.Offline -> Text(stringResource(R.string.update_offline))
                        is UpdateUiState.Available -> {
                            Text("${stringResource(R.string.update_available)}: ${state.release.tagName}")
                            Spacer(modifier = Modifier.height(8.dp))
                            state.release.apkUrl?.let { url ->
                                Button(onClick = {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                }) {
                                    Text(stringResource(R.string.download_update))
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            SectionTitle(stringResource(R.string.about))
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("${stringResource(R.string.version)} ${BuildConfig.VERSION_NAME}")
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(stringResource(R.string.about_text), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium)
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ModeButton(label: String, selected: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick) {
        Text(
            text = label,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
    }
}
