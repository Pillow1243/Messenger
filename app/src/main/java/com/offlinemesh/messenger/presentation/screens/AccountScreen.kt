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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlinemesh.messenger.R
import com.offlinemesh.messenger.data.OnlineError
import com.offlinemesh.messenger.data.RemoteDevice
import com.offlinemesh.messenger.domain.OnlineState
import com.offlinemesh.messenger.presentation.AppViewModel
import com.offlinemesh.messenger.presentation.components.ActionRow
import com.offlinemesh.messenger.presentation.components.SectionCard
import com.offlinemesh.messenger.presentation.formatTimestamp
import com.offlinemesh.messenger.push.PushRegistrationStatus

/**
 * Account & multi-device security screen for the online transport.
 * Offline mesh messaging works independently with or without an account.
 */
@Composable
fun AccountScreen(vm: AppViewModel, onBack: () -> Unit) {
    val state by vm.onlineState.collectAsStateWithLifecycle()
    val network by vm.networkAvailable.collectAsStateWithLifecycle()
    val devices by vm.devices.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val pushStatus by vm.pushStatus.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val error by vm.error.collectAsStateWithLifecycle()
    val onlineError by vm.onlineError.collectAsStateWithLifecycle()

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var displayName by remember { mutableStateOf("") }
    var registering by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var pendingRevoke by remember { mutableStateOf<RemoteDevice?>(null) }

    val connected = state is OnlineState.Connected
    LaunchedEffect(state) {
        if (state is OnlineState.Connected) vm.refreshDevices()
    }

    val localizedError = when (onlineError ?: error) {
        OnlineError.DEVICE_LIMIT_REACHED -> stringResource(R.string.device_limit_error)
        OnlineError.DEVICE_REVOKED -> stringResource(R.string.device_revoked_error)
        null -> null
        else -> onlineError ?: error
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.account_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cancel)
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
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SectionCard(title = stringResource(R.string.account_status)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        when (state) {
                            is OnlineState.Connected -> stringResource(R.string.account_connected)
                            is OnlineState.Connecting -> stringResource(R.string.account_connecting)
                            is OnlineState.Offline -> stringResource(R.string.account_offline)
                            is OnlineState.Error -> stringResource(R.string.account_error)
                            OnlineState.SignedOut -> stringResource(R.string.account_signed_out)
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = if (network) {
                            stringResource(R.string.account_network_up)
                        } else {
                            stringResource(R.string.account_network_down)
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = stringResource(R.string.account_mesh_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.push_status_label) + ": " + when (pushStatus) {
                            PushRegistrationStatus.REGISTERED -> stringResource(R.string.push_status_registered)
                            PushRegistrationStatus.UNCONFIGURED -> stringResource(R.string.push_status_unconfigured)
                            PushRegistrationStatus.INITIALIZED -> stringResource(R.string.push_status_initialized)
                            PushRegistrationStatus.ERROR -> stringResource(R.string.push_status_error)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    (state as? OnlineState.Connected)?.let { s ->
                        Spacer(Modifier.height(8.dp))
                        Text(s.email ?: s.userId, style = MaterialTheme.typography.bodySmall)
                    }
                    if (!localizedError.isNullOrBlank()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            localizedError,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            if (!connected) {
                SectionCard(
                    title = if (registering) {
                        stringResource(R.string.account_create)
                    } else {
                        stringResource(R.string.account_sign_in)
                    }
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        OutlinedTextField(
                            value = email,
                            onValueChange = {
                                email = it
                                vm.clearError()
                            },
                            label = { Text(stringResource(R.string.account_email)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Email,
                                imeAction = ImeAction.Next
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = password,
                            onValueChange = {
                                password = it
                                vm.clearError()
                            },
                            label = { Text(stringResource(R.string.account_password)) },
                            singleLine = true,
                            visualTransformation = PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Password,
                                imeAction = ImeAction.Done
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                        if (registering) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = displayName,
                                onValueChange = { displayName = it },
                                label = { Text(stringResource(R.string.account_display_name)) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                enabled = email.isNotBlank() && password.length >= 8 && !busy,
                                onClick = {
                                    if (registering) {
                                        vm.signUp(email, password, displayName) { ok ->
                                            if (ok) registering = false
                                        }
                                    } else {
                                        vm.signIn(email, password) { }
                                    }
                                }
                            ) {
                                if (busy) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.height(18.dp),
                                        strokeWidth = 2.dp
                                    )
                                } else {
                                    Text(
                                        stringResource(
                                            if (registering) R.string.account_create else R.string.account_sign_in
                                        )
                                    )
                                }
                            }
                            OutlinedButton(
                                onClick = {
                                    registering = !registering
                                    vm.clearError()
                                },
                                enabled = !busy
                            ) {
                                Text(
                                    stringResource(
                                        if (registering) R.string.account_have_account
                                        else R.string.account_no_account
                                    )
                                )
                            }
                        }
                    }
                }
            } else {
                val activeCount = devices.count { it.revokedAtMs == null }
                SectionCard(
                    title = "${stringResource(R.string.account_devices)} (${stringResource(R.string.device_active_count, activeCount)})"
                ) {
                    if (devices.isEmpty()) {
                        Text(
                            stringResource(R.string.account_no_devices),
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        val myDevId = settings?.deviceId.orEmpty()
                        val myRowId = vm.currentDeviceRowId
                        devices.forEach { device ->
                            val isCurrent = device.rowId == myRowId || device.deviceId == myDevId
                            val isRevoked = device.revokedAtMs != null
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Devices,
                                    contentDescription = null,
                                    tint = if (isRevoked) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary
                                )
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(horizontal = 12.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = device.name.ifBlank { device.deviceId.take(12) },
                                            style = MaterialTheme.typography.titleMedium
                                        )
                                        if (isCurrent) {
                                            Surface(
                                                shape = RoundedCornerShape(999.dp),
                                                color = MaterialTheme.colorScheme.primaryContainer
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.device_this_device),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                        if (isRevoked) {
                                            Surface(
                                                shape = RoundedCornerShape(999.dp),
                                                color = MaterialTheme.colorScheme.errorContainer
                                            ) {
                                                Text(
                                                    text = stringResource(R.string.device_revoked_badge),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                                )
                                            }
                                        }
                                    }
                                    val lastSeen = if (device.lastSeenAtMs > 0L) formatTimestamp(device.lastSeenAtMs) else ""
                                    Text(
                                        text = listOfNotNull(
                                            device.platform.uppercase(),
                                            device.deviceId.take(12),
                                            lastSeen.takeIf { it.isNotBlank() }
                                        ).joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (!isRevoked) {
                                    TextButton(onClick = { pendingRevoke = device }) {
                                        Text(
                                            text = stringResource(R.string.device_revoke_action),
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                SectionCard(title = stringResource(R.string.account_session)) {
                    ActionRow(
                        icon = Icons.Filled.Person,
                        title = stringResource(R.string.account_refresh_devices),
                        onClick = { vm.refreshDevices() }
                    )
                    ActionRow(
                        icon = Icons.Filled.Key,
                        title = stringResource(R.string.rotate_prekeys),
                        onClick = { vm.rotatePreKeysNow() }
                    )
                    ActionRow(
                        icon = Icons.Filled.Logout,
                        title = stringResource(R.string.account_sign_out),
                        onClick = { vm.signOut() }
                    )
                    ActionRow(
                        icon = Icons.Filled.Wifi,
                        title = stringResource(R.string.account_delete),
                        danger = true,
                        onClick = { confirmDelete = true }
                    )
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    pendingRevoke?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingRevoke = null },
            title = { Text(stringResource(R.string.device_revoke_action)) },
            text = { Text(stringResource(R.string.device_revoke_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    val rowId = target.rowId
                    pendingRevoke = null
                    vm.revokeDevice(rowId)
                }) {
                    Text(
                        stringResource(R.string.device_revoke_action),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingRevoke = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.account_delete)) },
            text = { Text(stringResource(R.string.account_delete_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteAccount { }
                }) { Text(stringResource(R.string.action_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}
