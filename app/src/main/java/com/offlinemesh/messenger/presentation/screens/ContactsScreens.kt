@file:OptIn(ExperimentalMaterial3Api::class)

package com.offlinemesh.messenger.presentation.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlinemesh.messenger.R
import com.offlinemesh.messenger.presentation.AppViewModel
import com.offlinemesh.messenger.presentation.components.Avatar
import com.offlinemesh.messenger.presentation.components.EmptyState
import com.offlinemesh.messenger.presentation.components.InfoRow
import com.offlinemesh.messenger.presentation.components.SectionCard
import com.offlinemesh.messenger.presentation.chunkKey

@Composable
fun ContactsScreen(vm: AppViewModel, onOpenContact: (String) -> Unit) {
    val contacts by vm.contacts.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.contacts_title),
                        style = MaterialTheme.typography.headlineMedium
                    )
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (contacts.isEmpty()) {
                EmptyState(
                    icon = Icons.Filled.Person,
                    title = stringResource(R.string.no_contacts),
                    body = stringResource(R.string.no_contacts_hint),
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(contacts, key = { it.deviceId }) { contact ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenContact(contact.deviceId) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Avatar(name = contact.displayName, size = 44)
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(start = 14.dp)
                            ) {
                                Text(
                                    text = contact.displayName,
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Text(
                                    text = if (contact.verified) {
                                        stringResource(R.string.verified)
                                    } else {
                                        stringResource(R.string.not_verified)
                                    },
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (contact.verified) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                            if (contact.identityChanged) {
                                Icon(
                                    Icons.Filled.Warning,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.error
                                )
                            } else if (contact.verified) {
                                Icon(
                                    Icons.Filled.CheckCircle,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                    item { Spacer(Modifier.height(96.dp)) }
                }
            }
        }
    }
}

@Composable
fun ContactDetailScreen(
    vm: AppViewModel,
    deviceId: String,
    onBack: () -> Unit,
    onMessage: (String) -> Unit
) {
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    val safetyNumbers by vm.safetyNumbers.collectAsStateWithLifecycle()
    val contact = contacts.firstOrNull { it.deviceId == deviceId }
    var confirmReset by remember { mutableStateOf(false) }

    LaunchedEffect(deviceId) { vm.loadSafetyNumber(deviceId) }
    val safety = safetyNumbers[deviceId].orEmpty()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(contact?.displayName ?: deviceId.take(8)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cancel))
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
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Avatar(name = contact?.displayName ?: "?", size = 64)
                Column(modifier = Modifier.padding(start = 16.dp)) {
                    Text(
                        text = contact?.displayName ?: deviceId.take(8),
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        text = if (contact?.verified == true) {
                            stringResource(R.string.verified)
                        } else {
                            stringResource(R.string.not_verified)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = if (contact?.verified == true) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }
            }

            if (contact?.identityChanged == true) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(modifier = Modifier.padding(16.dp)) {
                        Icon(
                            Icons.Filled.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(Modifier.size(12.dp))
                        Text(
                            text = stringResource(R.string.identity_changed_warning),
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            SectionCard(title = stringResource(R.string.safety_number)) {
                Text(
                    text = if (safety.isBlank()) "…" else safety.chunked(5).joinToString(" "),
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                )
                Text(
                    text = stringResource(R.string.safety_number_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp)
                )
                Spacer(Modifier.height(12.dp))
            }

            SectionCard(title = stringResource(R.string.contact_key)) {
                InfoRow(
                    label = stringResource(R.string.contact_key),
                    value = chunkKey(contact?.identityKeyBase64.orEmpty()),
                    mono = true
                )
                InfoRow(label = "Device", value = deviceId, mono = true)
            }

            Spacer(Modifier.height(16.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = { onMessage(deviceId) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.new_chat)) }

                OutlinedButton(
                    onClick = {
                        vm.setVerified(deviceId, !(contact?.verified ?: false))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Filled.Shield, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(stringResource(R.string.mark_verified))
                }

                TextButton(
                    onClick = { confirmReset = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.reset_identity)) }
            }
            Spacer(Modifier.height(32.dp))
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text(stringResource(R.string.reset_identity)) },
            text = { Text(stringResource(R.string.reset_identity_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    vm.resetTrust(deviceId)
                }) { Text(stringResource(R.string.reset_identity)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmReset = false }) { Text(stringResource(R.string.cancel)) }
            }
        )
    }
}
