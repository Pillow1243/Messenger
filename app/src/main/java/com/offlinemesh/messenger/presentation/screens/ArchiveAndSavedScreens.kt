@file:OptIn(ExperimentalMaterial3Api::class)

package com.offlinemesh.messenger.presentation.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlinemesh.messenger.R
import com.offlinemesh.messenger.core.db.ConversationEntity
import com.offlinemesh.messenger.presentation.AppViewModel
import com.offlinemesh.messenger.presentation.components.Avatar
import com.offlinemesh.messenger.presentation.components.EmptyState
import com.offlinemesh.messenger.presentation.formatTimestamp

/**
 * Picks the destination chat for a forwarded message.
 *
 * Only conversations this device already holds are offered, so forwarding never creates a new
 * chat or a new key exchange: the message is encrypted for the target chat exactly like a message
 * typed there (pairwise envelopes for 1:1, Sender Keys for v2 groups) and follows the same
 * MESH / ONLINE / QUEUE transport rules, including offline.
 */
@Composable
fun ForwardPickerDialog(
    vm: AppViewModel,
    excludeConversationId: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val targets = conversations.filter { it.id != excludeConversationId }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_forward)) },
        text = {
            if (targets.isEmpty()) {
                Text(stringResource(R.string.forward_no_targets))
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    items(targets, key = { it.id }) { conversation ->
                        ConversationChoiceRow(
                            conversation = conversation,
                            onClick = { onPick(conversation.id) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
        }
    )
}

@Composable
private fun ConversationChoiceRow(conversation: ConversationEntity, onClick: () -> Unit) {
    val fallbackTitle = stringResource(R.string.unknown_peer)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Avatar(name = conversation.title.ifBlank { fallbackTitle })
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = conversation.title.ifBlank { fallbackTitle },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (conversation.lastMessagePreview.isNotBlank()) {
                Text(
                    text = conversation.lastMessagePreview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Archived chats: hidden from the main list, still fully functional. Selecting one opens it and
 * keeps it archived (Telegram behaviour); the row action unarchives it.
 */
@Composable
fun ArchivedChatsScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit
) {
    val archived by vm.archivedConversations.collectAsStateWithLifecycle()
    val fallbackTitle = stringResource(R.string.unknown_peer)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.archived_chats)) },
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
        if (archived.isEmpty()) {
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                EmptyState(
                    icon = Icons.Filled.Archive,
                    title = stringResource(R.string.archived_empty_title),
                    body = stringResource(R.string.archived_empty_body)
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(archived, key = { it.id }) { conversation ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenChat(conversation.id) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Avatar(name = conversation.title.ifBlank { fallbackTitle })
                        Spacer(Modifier.size(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = conversation.title.ifBlank { fallbackTitle },
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = conversation.lastMessagePreview,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Text(
                            text = formatTimestamp(conversation.lastMessageAt),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        TextButton(onClick = { vm.setConversationArchived(conversation.id, false) }) {
                            Text(stringResource(R.string.chat_unarchive))
                        }
                    }
                }
            }
        }
    }
}

/**
 * Saved messages: every message the user starred, newest first, across all chats.
 *
 * Starring is a local bookmark - the flag lives in the SQLCipher database and is never
 * transmitted, so the server cannot build a "saved items" profile of the user.
 */
@Composable
fun SavedMessagesScreen(
    vm: AppViewModel,
    onBack: () -> Unit,
    onOpenChat: (String) -> Unit
) {
    val saved by vm.starredMessages().collectAsStateWithLifecycle(initialValue = emptyList())
    val conversations by vm.conversations.collectAsStateWithLifecycle()
    val titles = conversations.associate { it.id to it.title }
    val mediaPlaceholder = stringResource(R.string.saved_media_placeholder)
    val fallbackTitle = stringResource(R.string.unknown_peer)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.saved_messages)) },
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
        if (saved.isEmpty()) {
            Column(modifier = Modifier.fillMaxSize().padding(padding)) {
                EmptyState(
                    icon = Icons.Filled.BookmarkBorder,
                    title = stringResource(R.string.saved_empty_title),
                    body = stringResource(R.string.saved_empty_body)
                )
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(saved, key = { it.id }) { message ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenChat(message.conversationId) }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = titles[message.conversationId].orEmpty()
                                    .ifBlank { fallbackTitle },
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = formatTimestamp(message.timestamp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(Modifier.size(2.dp))
                        Text(
                            text = message.body.ifBlank { message.fileName ?: mediaPlaceholder },
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.size(4.dp))
                        TextButton(onClick = { vm.setStarred(message.id, false) }) {
                            Text(stringResource(R.string.saved_remove))
                        }
                    }
                }
            }
        }
    }
}
