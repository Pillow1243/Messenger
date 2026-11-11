@file:OptIn(ExperimentalMaterial3Api::class)

package com.offlinemesh.messenger.presentation.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlinemesh.messenger.R
import com.offlinemesh.messenger.core.db.MessageEntity
import com.offlinemesh.messenger.presentation.AppViewModel
import com.offlinemesh.messenger.presentation.components.EmptyState
import com.offlinemesh.messenger.presentation.formatDuration
import com.offlinemesh.messenger.presentation.formatSize
import com.offlinemesh.messenger.presentation.formatTimestamp
import java.io.File

@Composable
fun ChatScreen(vm: AppViewModel, conversationId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val conversation by remember(conversationId) { vm.conversation(conversationId) }
        .collectAsStateWithLifecycle(initialValue = null)
    val messages by remember(conversationId) { vm.messages(conversationId) }
        .collectAsStateWithLifecycle(initialValue = emptyList<MessageEntity>())

    var draft by remember { mutableStateOf("") }
    var actionTarget by remember { mutableStateOf<MessageEntity?>(null) }
    var editing by remember { mutableStateOf<MessageEntity?>(null) }
    var replyTarget by remember { mutableStateOf<MessageEntity?>(null) }
    var recording by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordStart by remember { mutableStateOf(0L) }
    var recordFile by remember { mutableStateOf<File?>(null) }
    val errorKey by vm.error.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()

    val typing by vm.typing.collectAsStateWithLifecycle()
    val typingPeers = typing[conversationId].orEmpty()

    LaunchedEffect(conversationId) { vm.markRead(conversationId) }
    LaunchedEffect(conversationId, conversation?.draft) {
        val stored = conversation?.draft
        if (!stored.isNullOrEmpty() && draft.isEmpty()) draft = stored
    }
    LaunchedEffect(draft) {
        if (draft.isNotEmpty()) {
            vm.notifyTyping(conversationId)
            kotlinx.coroutines.delay(600)
            vm.setDraft(conversationId, draft)
        }
    }
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    fun stopRecording(send: Boolean) {
        val active = recorder ?: return
        recorder = null
        recording = false
        try {
            active.stop()
        } catch (e: Exception) {
            // Too-short recordings throw on stop; the file is dropped below.
        }
        try {
            active.release()
        } catch (e: Exception) {
            // ignore
        }
        val file = recordFile
        val duration = System.currentTimeMillis() - recordStart
        recordFile = null
        if (file != null && file.exists()) {
            if (send && duration > 400) {
                vm.sendVoice(conversationId, file, duration)
            } else {
                file.delete()
            }
        }
    }

    fun startRecording() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val file = File(context.cacheDir, "rec_${System.currentTimeMillis()}.m4a")
        try {
            val created = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }
            created.setAudioSource(MediaRecorder.AudioSource.MIC)
            created.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            created.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            created.setAudioEncodingBitRate(64_000)
            created.setAudioSamplingRate(44_100)
            created.setOutputFile(file.absolutePath)
            created.prepare()
            created.start()
            recorder = created
            recordFile = file
            recordStart = System.currentTimeMillis()
            recording = true
        } catch (e: Exception) {
            file.delete()
            recording = false
            recorder = null
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            try {
                recorder?.release()
            } catch (e: Exception) {
                // ignore
            }
            recorder = null
        }
    }

    val pickFile = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val name = queryDisplayName(context, uri) ?: "attachment"
            val temp = File(context.cacheDir, "out_${System.currentTimeMillis()}")
            val copied = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    temp.outputStream().use { output -> input.copyTo(output) }
                }
                temp.exists() && temp.length() > 0
            }.getOrDefault(false)
            if (copied) vm.sendFile(conversationId, temp, name) else temp.delete()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = conversation?.title ?: "",
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.Lock,
                                contentDescription = null,
                                modifier = Modifier.size(12.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.size(4.dp))
                            Text(
                                text = stringResource(R.string.encrypted_banner),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cancel))
                    }
                }
            )
        },
        bottomBar = {
            Column(modifier = Modifier.background(MaterialTheme.colorScheme.surface)) {
            replyTarget?.let { target ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "↳ " + target.body.take(60),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { replyTarget = null }) {
                        Text(stringResource(R.string.action_cancel))
                    }
                }
            }
            if (typingPeers.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.chat_typing),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 20.dp, top = 4.dp)
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(horizontal = 8.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { pickFile.launch("*/*") }) {
                    Icon(Icons.Filled.AttachFile, contentDescription = stringResource(R.string.attach_file))
                }
                IconButton(
                    onClick = { if (recording) stopRecording(true) else startRecording() }
                ) {
                    Icon(
                        Icons.Filled.Mic,
                        contentDescription = stringResource(R.string.record_voice),
                        tint = if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    )
                }
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    placeholder = { Text(stringResource(R.string.message_hint)) },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp),
                    maxLines = 5,
                    shape = RoundedCornerShape(24.dp)
                )
                IconButton(
                    onClick = {
                        val text = draft.trim()
                        if (text.isNotEmpty()) {
                            draft = ""
                            vm.sendText(conversationId, text, replyTarget?.id)
                            replyTarget = null
                        }
                    },
                    enabled = draft.isNotBlank()
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = stringResource(R.string.send))
                }
            }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            errorKey?.let { key ->
                val text = when (key) {
                    "file_too_large" -> stringResource(R.string.file_too_large)
                    "attachment_pick_failed" -> stringResource(R.string.attachment_pick_failed)
                    else -> stringResource(R.string.error_unknown)
                }
                Column(modifier = Modifier.align(Alignment.TopCenter).padding(8.dp)) {
                    Text(text, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = { vm.clearError() }) { Text(stringResource(R.string.cancel)) }
                }
            }
            if (messages.isEmpty()) {
                EmptyState(
                    icon = Icons.Filled.Lock,
                    title = stringResource(R.string.empty_chat),
                    body = stringResource(R.string.encrypted_banner),
                    modifier = Modifier.align(Alignment.Center)
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(messages, key = { it.id }) { message ->
                        MessageBubble(
                            message = message,
                            showSender = conversation?.isGroup == true,
                            onLongPress = { actionTarget = message },
                            replyPreview = message.replyToId?.let { id ->
                                messages.firstOrNull { it.id == id }?.body
                            },
                            onOpenAttachment = {
                                val path = message.filePath
                                if (path != null) {
                                    val name = message.fileName ?: "attachment"
                                    vm.openAttachment(path, name) { uri -> openUri(context, uri, name) }
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    val target = actionTarget
    if (target != null) {
        AlertDialog(
            onDismissRequest = { actionTarget = null },
            title = { Text(stringResource(R.string.chat_react)) },
            text = {
                Column {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("👍", "❤️", "😂", "😮", "🙏").forEach { emoji ->
                            TextButton(onClick = {
                                actionTarget = null
                                vm.toggleReaction(target.id, emoji)
                            }) { Text(emoji, style = MaterialTheme.typography.headlineSmall) }
                        }
                    }
                    if (target.outgoing) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_edit)) },
                            onClick = { editing = target; actionTarget = null },
                            enabled = target.deletedAt == null && target.remoteId != null
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_delete_for_all)) },
                            onClick = {
                                actionTarget = null
                                vm.deleteForEveryone(target.id)
                            },
                            enabled = target.remoteId != null
                        )
                    }
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_reply)) },
                        onClick = {
                            actionTarget = null
                            replyTarget = target
                        }
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.chat_star)) },
                        onClick = {
                            actionTarget = null
                            vm.setStarred(target.id, !target.starred)
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { actionTarget = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    val editTarget = editing
    if (editTarget != null) {
        var editedText by remember(editTarget.id) { mutableStateOf(editTarget.body) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(stringResource(R.string.chat_edit)) },
            text = {
                OutlinedTextField(
                    value = editedText,
                    onValueChange = { editedText = it },
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    enabled = editedText.isNotBlank(),
                    onClick = {
                        vm.editMessage(editTarget.id, editedText)
                        editing = null
                    }
                ) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = {
                TextButton(onClick = { editing = null }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun MessageBubble(
    message: MessageEntity,
    showSender: Boolean,
    replyPreview: String?,
    onLongPress: () -> Unit,
    onOpenAttachment: () -> Unit
) {
    val outgoing = message.outgoing
    val bubbleColor = if (outgoing) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val alignment = if (outgoing) Alignment.CenterEnd else Alignment.CenterStart

    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = alignment) {
        Column(
            modifier = Modifier
                .widthIn(max = 300.dp)
                .background(bubbleColor, RoundedCornerShape(18.dp))
                .combinedClickable(onClick = {}, onLongClick = onLongPress)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            if (replyPreview != null) {
                Text(
                    text = "↳ " + replyPreview.take(80),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
            }
            if (showSender && !outgoing) {
                Text(
                    text = message.senderDeviceId.take(8),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            when (message.type) {
                "FILE" -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable(onClick = onOpenAttachment)
                ) {
                    Icon(Icons.Filled.Description, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Column {
                        Text(
                            text = message.fileName ?: stringResource(R.string.file_received),
                            style = MaterialTheme.typography.bodyLarge
                        )
                        message.fileSize?.let {
                            Text(
                                text = formatSize(it),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                "VOICE" -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable(onClick = onOpenAttachment)
                ) {
                    Icon(Icons.Filled.PlayArrow, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text(
                        text = stringResource(R.string.voice_note) + " · " +
                            formatDuration(message.durationMs ?: 0L),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }

                else -> Text(text = message.body, style = MaterialTheme.typography.bodyLarge)
            }
            if (message.reactions.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    text = message.reactions.split(',').joinToString("  ") { part ->
                        val bits = part.split(':')
                        if (bits.size == 2) bits[0] + " " + bits[1] else part
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            Spacer(Modifier.height(4.dp))
            val statusLabel = when (message.status) {
                "QUEUED" -> stringResource(R.string.status_queued)
                "SENT" -> stringResource(R.string.status_sent)
                "DELIVERED" -> stringResource(R.string.status_delivered)
                "FAILED" -> stringResource(R.string.status_failed)
                else -> ""
            }
            Text(
                text = buildString {
                    append(formatTimestamp(message.timestamp))
                    if (message.editedAt != null) append(" · " + stringResource(R.string.chat_edit))
                    if (outgoing && statusLabel.isNotEmpty()) {
                        append(" · ")
                        append(statusLabel)
                    }
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun queryDisplayName(context: Context, uri: Uri): String? {
    return try {
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            } else {
                null
            }
        }
    } catch (e: Exception) {
        null
    }
}

private fun openUri(context: Context, uri: Uri, name: String) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, context.contentResolver.getType(uri) ?: mimeOf(name))
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(intent) }
}

private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "pdf" -> "application/pdf"
    "m4a" -> "audio/mp4"
    "mp3" -> "audio/mpeg"
    "txt" -> "text/plain"
    else -> "*/*"
}
