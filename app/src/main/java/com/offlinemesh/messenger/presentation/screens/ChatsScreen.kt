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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChatBubbleOutline
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Badge
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlinemesh.messenger.R
import com.offlinemesh.messenger.core.db.ConversationEntity
import com.offlinemesh.messenger.core.db.ContactEntity
import com.offlinemesh.messenger.presentation.AppViewModel
import com.offlinemesh.messenger.presentation.components.Avatar
import com.offlinemesh.messenger.presentation.components.EmptyState
import com.offlinemesh.messenger.domain.OnlineState
import com.offlinemesh.messenger.presentation.formatTimestamp

@Composable
fun ChatsScreen(
    vm: AppViewModel,
    onOpenChat: (String) -> Unit,
    onOpenNearby: () -> Unit,
    onOpenSearch: () -> Unit = {},
    onOpenChannels: () -> Unit = {},
    onOpenAccount: () -> Unit = {}
) {
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val contacts by vm.contacts.collectAsStateWithLifecycle()
    val onlineState by vm.onlineState.collectAsStateWithLifecycle()
    val network by vm.networkAvailable.collectAsStateWithLifecycle()
    val meshRunning by vm.meshRunning.collectAsStateWithLifecycle()
    var showGroupDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = stringResource(R.string.chats_title),
                        style = MaterialTheme.typography.headlineMedium
                    )
                },
                actions = {
                    IconButton(onClick = onOpenSearch) {
                        Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.nav_search))
                    }
                    IconButton(onClick = onOpenChannels) {
                        Icon(Icons.Filled.Campaign, contentDescription = stringResource(R.string.nav_channels))
                    }
                    IconButton(onClick = onOpenNearby) {
                        Icon(Icons.Filled.ChatBubbleOutline, contentDescription = stringResource(R.string.nav_discover))
                    }
                    IconButton(onClick = onOpenAccount) {
                        Icon(Icons.Filled.Person, contentDescription = stringResource(R.string.nav_account))
                    }
                }
            )
        },
        floatingActionButton = {
            if (contacts.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { showGroupDialog = true },
                    icon = { Icon(Icons.Filled.Groups, contentDescription = null) },
                    text = { Text(stringResource(R.string.new_group)) }
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                item {
                    ModeBanner(
                        onlineState = onlineState,
                        network = network,
                        meshRunning = meshRunning,
                        onOpenAccount = onOpenAccount
                    )
                }
                if (conversations.isEmpty()) {
                    item {
                        EmptyState(
                            icon = Icons.Filled.ChatBubbleOutline,
                            title = stringResource(R.string.no_chats_title),
                            body = stringResource(R.string.no_chats_hint)
                        )
                    }
                } else {
                    items(conversations, key = { it.id }) { conversation ->
                        ConversationRow(conversation = conversation, onClick = { onOpenChat(conversation.id) })
                    }
                    item { Spacer(Modifier.height(96.dp)) }
                }
            }
        }
    }

    if (showGroupDialog) {
        NewGroupDialog(
            contacts = contacts,
            onDismiss = { showGroupDialog = false },
            onCreate = { name, members ->
                showGroupDialog = false
                vm.createGroup(name, members) { id -> onOpenChat(id) }
            }
        )
    }
}

@Composable
private fun ConversationRow(conversation: ConversationEntity, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(name = conversation.title, size = 48)
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = 14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = conversation.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = formatTimestamp(conversation.lastMessageAt),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = conversation.lastMessagePreview.ifBlank { stringResource(R.string.empty_chat) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (conversation.unreadCount > 0) {
                    Spacer(Modifier.size(8.dp))
                    Badge { Text(conversation.unreadCount.toString()) }
                }
            }
        }
    }
}

@Composable
private fun NewGroupDialog(
    contacts: List<ContactEntity>,
    onDismiss: () -> Unit,
    onCreate: (String, List<String>) -> Unit
) {
    var name by remember { mutableStateOf("") }
    val selected = remember { mutableStateOf(setOf<String>()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.new_group)) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.group_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    stringResource(R.string.group_members),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                LazyColumn(modifier = Modifier.height(240.dp)) {
                    items(contacts, key = { it.deviceId }) { contact ->
                        val isSelected = selected.value.contains(contact.deviceId)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selected.value = if (isSelected) {
                                        selected.value - contact.deviceId
                                    } else {
                                        selected.value + contact.deviceId
                                    }
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            androidx.compose.material3.Checkbox(
                                checked = isSelected,
                                onCheckedChange = { checked ->
                                    selected.value = if (checked) {
                                        selected.value + contact.deviceId
                                    } else {
                                        selected.value - contact.deviceId
                                    }
                                }
                            )
                            Avatar(name = contact.displayName, size = 32)
                            Text(contact.displayName, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(name.trim().ifBlank { "Group" }, selected.value.toList()) },
                enabled = selected.value.isNotEmpty()
            ) { Text(stringResource(R.string.create)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        }
    )
}

/**
 * Honest transport indicator: it shows what is actually usable right now instead of a decorative
 * dot. Tapping it opens the account screen, where the mesh/online split is explained.
 */
@Composable
private fun ModeBanner(
    onlineState: OnlineState,
    network: Boolean,
    meshRunning: Boolean,
    onOpenAccount: () -> Unit
) {
    val chipLabel = when {
        onlineState is OnlineState.Connected && meshRunning -> stringResource(R.string.mode_both)
        onlineState is OnlineState.Connected -> stringResource(R.string.mode_online)
        onlineState is OnlineState.Connecting -> stringResource(R.string.mode_connecting)
        else -> stringResource(R.string.mode_offline)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AssistChip(
            onClick = onOpenAccount,
            label = { Text(chipLabel) },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Filled.Wifi,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
            }
        )
        if (!network) {
            Text(
                text = stringResource(R.string.account_network_down),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
        }
    }
}
