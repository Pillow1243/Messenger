@file:OptIn(ExperimentalMaterial3Api::class)

package com.offlinemesh.messenger.presentation.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlinemesh.messenger.BuildConfig
import com.offlinemesh.messenger.R
import com.offlinemesh.messenger.presentation.AppViewModel
import com.offlinemesh.messenger.presentation.chunkKey
import com.offlinemesh.messenger.presentation.components.ActionRow
import com.offlinemesh.messenger.presentation.components.InfoRow
import com.offlinemesh.messenger.presentation.components.SectionCard
import com.offlinemesh.messenger.presentation.components.ToggleRow

@Composable
fun SettingsScreen(vm: AppViewModel, onOpenAccount: () -> Unit = {}) {
    val settings by vm.settings.collectAsStateWithLifecycle()
    val pairingCode by vm.pairingCode.collectAsStateWithLifecycle()
    var editName by remember { mutableStateOf(false) }
    var confirmErase by remember { mutableStateOf(false) }

    val current = settings

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.settings_title),
                        style = MaterialTheme.typography.headlineMedium
                    )
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(bottom = 96.dp)
        ) {
            SectionCard(title = stringResource(R.string.section_profile)) {
                ActionRow(
                    icon = Icons.Filled.Person,
                    title = current?.displayName?.ifBlank { stringResource(R.string.display_name) }
                        ?: stringResource(R.string.display_name),
                    subtitle = stringResource(R.string.display_name),
                    onClick = { editName = true }
                )
                InfoRow(
                    label = stringResource(R.string.my_identity),
                    value = chunkKey(current?.deviceId.orEmpty(), groupSize = 8, maxGroups = 3),
                    mono = true
                )
            }

            SectionCard(title = stringResource(R.string.account_title)) {
                ActionRow(
                    icon = Icons.Filled.Cloud,
                    title = stringResource(R.string.account_title),
                    subtitle = stringResource(R.string.account_status),
                    onClick = onOpenAccount
                )
            }

            SectionCard(title = stringResource(R.string.section_connectors)) {
                ToggleRow(
                    icon = Icons.Filled.Settings,
                    title = stringResource(R.string.connectors_mesh),
                    subtitle = stringResource(R.string.connectors_mesh_hint),
                    checked = current?.meshEnabled ?: false,
                    onCheckedChange = { vm.setMeshEnabled(it) }
                )
                ToggleRow(
                    icon = Icons.Filled.Bluetooth,
                    title = stringResource(R.string.connectors_bluetooth),
                    subtitle = stringResource(R.string.connectors_bluetooth_hint),
                    checked = current?.bluetoothEnabled ?: false,
                    onCheckedChange = { vm.setBluetoothEnabled(it) }
                )
                ToggleRow(
                    icon = Icons.Filled.Wifi,
                    title = stringResource(R.string.connectors_wifi),
                    subtitle = stringResource(R.string.connectors_wifi_hint),
                    checked = current?.wifiEnabled ?: false,
                    onCheckedChange = { vm.setWifiEnabled(it) }
                )
                ToggleRow(
                    icon = Icons.Filled.BatteryStd,
                    title = stringResource(R.string.connectors_battery),
                    subtitle = stringResource(R.string.connectors_battery_hint),
                    checked = current?.batterySaver ?: false,
                    onCheckedChange = { vm.setBatterySaver(it) }
                )
                Text(
                    text = stringResource(R.string.connectors_radios_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
            }

            SectionCard(title = stringResource(R.string.section_appearance)) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = current?.theme == "system",
                        onClick = { vm.setTheme("system") },
                        label = { Text(stringResource(R.string.theme_system)) }
                    )
                    FilterChip(
                        selected = current?.theme == "light",
                        onClick = { vm.setTheme("light") },
                        label = { Text(stringResource(R.string.theme_light)) }
                    )
                    FilterChip(
                        selected = current?.theme == "dark",
                        onClick = { vm.setTheme("dark") },
                        label = { Text(stringResource(R.string.theme_dark)) }
                    )
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = current?.language == "en",
                        onClick = { vm.setLanguage("en") },
                        label = { Text(stringResource(R.string.language_english)) }
                    )
                    FilterChip(
                        selected = current?.language == "fa",
                        onClick = { vm.setLanguage("fa") },
                        label = { Text(stringResource(R.string.language_persian)) }
                    )
                }
            }

            SectionCard(title = stringResource(R.string.section_security)) {
                Row(modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                    androidx.compose.material3.Icon(
                        Icons.Filled.Lock,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.height(20.dp)
                    )
                    Spacer(Modifier.height(4.dp))
                    Column(modifier = Modifier.padding(start = 12.dp)) {
                        Text(
                            text = stringResource(R.string.encryption_title),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = stringResource(R.string.encryption_desc),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                InfoRow(
                    label = stringResource(R.string.database_title),
                    value = stringResource(R.string.database_desc)
                )
                Text(
                    text = stringResource(R.string.offline_notice),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                )
            }

            SectionCard(title = stringResource(R.string.section_about)) {
                InfoRow(
                    label = stringResource(R.string.version_title),
                    value = stringResource(
                        R.string.version_value,
                        BuildConfig.VERSION_NAME,
                        BuildConfig.BUILD_CHANNEL
                    )
                )
                Text(
                    text = stringResource(R.string.about_text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                )
                ActionRow(
                    icon = Icons.Filled.Delete,
                    title = stringResource(R.string.clear_all_data),
                    danger = true,
                    onClick = { confirmErase = true }
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (editName) {
        var name by remember { mutableStateOf(current?.displayName.orEmpty()) }
        AlertDialog(
            onDismissRequest = { editName = false },
            title = { Text(stringResource(R.string.display_name)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 40) name = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.display_name)) }
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.setDisplayName(name.trim())
                    editName = false
                }) { Text(stringResource(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { editName = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    if (confirmErase) {
        AlertDialog(
            onDismissRequest = { confirmErase = false },
            title = { Text(stringResource(R.string.clear_all_data)) },
            text = { Text(stringResource(R.string.clear_all_data_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmErase = false
                    vm.eraseAllData()
                }) { Text(stringResource(R.string.clear_all_data)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmErase = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }

    // Keeps the pairing code warm so the QR dialog opens instantly.
    if (pairingCode.isEmpty()) {
        androidx.compose.runtime.LaunchedEffect(Unit) { vm.refreshPairingCode() }
    }
}
