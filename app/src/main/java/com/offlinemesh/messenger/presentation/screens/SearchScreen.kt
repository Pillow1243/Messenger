@file:OptIn(ExperimentalMaterial3Api::class)

package com.offlinemesh.messenger.presentation.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlinemesh.messenger.R
import com.offlinemesh.messenger.presentation.AppViewModel
import com.offlinemesh.messenger.presentation.components.Avatar
import com.offlinemesh.messenger.presentation.components.EmptyState
import com.offlinemesh.messenger.presentation.formatTimestamp
import kotlinx.coroutines.delay

/**
 * Unified search: decrypted local content (messages, chats, contacts) and, when signed in,
 * public accounts and channels. Everything matches on the device except the public directory
 * lookup, which the server exposes through a rate-limited RPC.
 */
@Composable
fun SearchScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    val local by vm.localResults.collectAsStateWithLifecycle()
    val accounts by vm.accountResults.collectAsStateWithLifecycle()
    val channels by vm.channelResults.collectAsStateWithLifecycle()
    val searching by vm.searching.collectAsStateWithLifecycle()

    LaunchedEffect(query) {
        delay(250)
        if (query.isBlank()) vm.clearSearch() else vm.search(query)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text(stringResource(R.string.search_hint)) },
                        singleLine = true,
                        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth()
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cancel)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            if (searching) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            if (query.isBlank()) {
                EmptyState(
                    icon = Icons.Filled.Search,
                    title = stringResource(R.string.search_title),
                    body = stringResource(R.string.search_body)
                )
                return@Column
            }
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (local.conversations.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.search_chats)) }
                    items(local.conversations, key = { "c" + it.id }) { conversation ->
                        ResultRow(
                            title = conversation.title,
                            subtitle = conversation.lastMessagePreview,
                            onClick = { onOpenChat(conversation.id) }
                        )
                    }
                }
                if (local.messages.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.search_messages)) }
                    items(local.messages, key = { "m" + it.id }) { message ->
                        ResultRow(
                            title = message.body.take(80),
                            subtitle = formatTimestamp(message.timestamp),
                            onClick = { onOpenChat(message.conversationId) }
                        )
                    }
                }
                if (local.contacts.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.search_contacts)) }
                    items(local.contacts, key = { "k" + it.deviceId }) { contact ->
                        ResultRow(
                            title = contact.displayName,
                            subtitle = contact.deviceId,
                            onClick = { vm.openConversationWith(contact.deviceId, onOpenChat) }
                        )
                    }
                }
                if (accounts.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.search_accounts)) }
                    items(accounts, key = { "a" + it.userId }) { profile ->
                        ResultRow(
                            title = profile.displayName.ifBlank { profile.username },
                            subtitle = "@" + profile.username,
                            onClick = { vm.openAccountChat(profile.userId, onOpenChat) },
                            iconName = profile.displayName.ifBlank { profile.username }
                        )
                    }
                }
                if (channels.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.search_channels)) }
                    items(channels, key = { "ch" + it.id }) { channel ->
                        ResultRow(
                            title = channel.title,
                            subtitle = channel.username?.let { "@$it" } ?: (channel.description ?: ""),
                            onClick = { vm.joinChannel(channel.id, onOpenChat) },
                            isChannel = true
                        )
                    }
                }
                item { Spacer(Modifier.height(32.dp)) }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Column {
        HorizontalDivider()
        Text(
            text = text.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp)
        )
    }
}

@Composable
private fun ResultRow(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    iconName: String? = null,
    isChannel: Boolean = false
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (isChannel) {
            Icon(Icons.Filled.Campaign, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        } else {
            Avatar(name = iconName ?: title, size = 38)
        }
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle.isNotBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
