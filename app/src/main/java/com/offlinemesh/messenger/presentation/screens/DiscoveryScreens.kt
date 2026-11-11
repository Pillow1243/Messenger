package com.offlinemesh.messenger.presentation.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.journeyapps.scan.ScanContract
import com.journeyapps.scan.ScanOptions
import com.offlinemesh.messenger.R
import com.offlinemesh.messenger.presentation.components.AvatarCircle
import com.offlinemesh.messenger.presentation.components.QrImage
import com.offlinemesh.messenger.presentation.viewmodel.ContactsViewModel
import com.offlinemesh.messenger.presentation.viewmodel.DiscoveryViewModel
import com.offlinemesh.messenger.presentation.viewmodel.GroupCreateViewModel
import com.offlinemesh.messenger.presentation.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiscoveryScreen(
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit,
    onOpenQrShow: () -> Unit
) {
    val vm: DiscoveryViewModel = viewModel()
    val peers by vm.peers.collectAsState()
    val isRunning by vm.isRunning.collectAsState()
    val contactsVm: ContactsViewModel = viewModel()
    var scanError by remember { mutableStateOf(false) }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents != null) {
            contactsVm.addFromQr(contents) { ok ->
                scanError = !ok
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.discover)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = onOpenQrShow) {
                        Icon(Icons.Filled.QrCode, contentDescription = stringResource(R.string.show_my_qr))
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (isRunning) stringResource(R.string.mesh_status_on) else stringResource(R.string.mesh_status_off),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.weight(1f))
                Button(onClick = { if (isRunning) vm.stopMesh() else vm.startMesh() }) {
                    Text(if (isRunning) stringResource(R.string.disconnect) else stringResource(R.string.connect))
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Row {
                Button(onClick = {
                    scanner.launch(
                        ScanOptions().apply {
                            setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                            setPrompt("")
                            setBeepEnabled(false)
                            setOrientationLocked(false)
                        }
                    )
                }) {
                    Icon(Icons.Filled.QrCodeScanner, contentDescription = null)
                    Spacer(modifier = Modifier.padding(horizontal = 4.dp))
                    Text(stringResource(R.string.scan_qr))
                }
                Spacer(modifier = Modifier.padding(horizontal = 8.dp))
                Button(onClick = onOpenQrShow) {
                    Text(stringResource(R.string.show_my_qr))
                }
            }
            if (scanError) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(stringResource(R.string.invalid_qr), color = MaterialTheme.colorScheme.error)
            }
            Spacer(modifier = Modifier.height(16.dp))
            Text(stringResource(R.string.nearby_devices), style = MaterialTheme.typography.titleSmall)
            Spacer(modifier = Modifier.height(8.dp))
            if (peers.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.no_devices_found))
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(peers) { peer ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { vm.openChat(peer.deviceId, peer.displayName, onOpenChat) }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AvatarCircle(name = peer.displayName)
                            Spacer(modifier = Modifier.padding(horizontal = 8.dp))
                            Column {
                                Text(peer.displayName, style = MaterialTheme.typography.titleMedium)
                                Text(stringResource(R.string.online), style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContactsScreen(
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit
) {
    val vm: ContactsViewModel = viewModel()
    val contacts by vm.contacts.collectAsState(initial = emptyList())
    var scanError by remember { mutableStateOf(false) }

    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents != null) {
            vm.addFromQr(contents) { ok -> scanError = !ok }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.contacts)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = {
                        scanner.launch(
                            ScanOptions().apply {
                                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                setPrompt("")
                                setBeepEnabled(false)
                                setOrientationLocked(false)
                            }
                        )
                    }) {
                        Icon(Icons.Filled.QrCodeScanner, contentDescription = stringResource(R.string.scan_qr))
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (scanError) {
                Text(
                    stringResource(R.string.invalid_qr),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(16.dp)
                )
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(contacts) { contact ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { vm.openChat(contact, onOpenChat) }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AvatarCircle(name = contact.displayName)
                        Spacer(modifier = Modifier.padding(horizontal = 8.dp))
                        Column {
                            Text(contact.displayName, style = MaterialTheme.typography.titleMedium)
                            Text(contact.deviceId.take(12), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupCreateScreen(
    onBack: () -> Unit,
    onCreated: (String) -> Unit
) {
    val vm: GroupCreateViewModel = viewModel()
    val contacts by vm.contacts.collectAsState(initial = emptyList())
    val name by vm.groupName.collectAsState()
    val selected by vm.selected.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.new_group)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
            OutlinedTextField(
                value = name,
                onValueChange = { vm.groupName.value = it },
                label = { Text(stringResource(R.string.group_name_hint)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(modifier = Modifier.height(16.dp))
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(contacts) { contact ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { vm.toggle(contact.deviceId) }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = selected.contains(contact.deviceId),
                            onCheckedChange = { vm.toggle(contact.deviceId) }
                        )
                        AvatarCircle(name = contact.displayName)
                        Spacer(modifier = Modifier.padding(horizontal = 8.dp))
                        Text(contact.displayName)
                    }
                }
            }
            Button(
                onClick = { vm.create(onCreated) },
                enabled = name.isNotBlank() && selected.isNotEmpty(),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.create))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrShowScreen(onBack: () -> Unit) {
    val vm: SettingsViewModel = viewModel()
    val payload = remember { vm.buildMyQr() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.my_qr_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(stringResource(R.string.my_qr_subtitle))
            Spacer(modifier = Modifier.height(24.dp))
            if (payload.isNotEmpty()) {
                QrImage(text = payload)
            }
        }
    }
}
