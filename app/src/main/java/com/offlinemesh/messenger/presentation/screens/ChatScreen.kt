@file:OptIn(ExperimentalMaterial3Api::class)

package com.offlinemesh.messenger.presentation.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.util.Base64
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Reply
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlinemesh.messenger.R
import com.offlinemesh.messenger.core.db.ConversationEntity
import com.offlinemesh.messenger.core.db.MessageEntity
import com.offlinemesh.messenger.core.db.PeerProfileEntity
import com.offlinemesh.messenger.data.ConversationType
import com.offlinemesh.messenger.data.MessageStatus
import com.offlinemesh.messenger.data.MessageType
import com.offlinemesh.messenger.presentation.AppViewModel
import com.offlinemesh.messenger.presentation.components.EmptyState
import com.offlinemesh.messenger.presentation.formatDuration
import com.offlinemesh.messenger.presentation.formatSize
import com.offlinemesh.messenger.presentation.formatTimestamp
import com.offlinemesh.messenger.work.MediaTransferWorker
import kotlinx.coroutines.delay
import java.io.File

@Composable
fun ChatScreen(vm: AppViewModel, conversationId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val conversation by remember(conversationId) { vm.conversation(conversationId) }
        .collectAsStateWithLifecycle(initialValue = null)
    val messages by remember(conversationId) { vm.messages(conversationId) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val playingMessageId by vm.playingMessageId.collectAsStateWithLifecycle()

    var draft by remember { mutableStateOf("") }
    var actionTarget by remember { mutableStateOf<MessageEntity?>(null) }
    var editing by remember { mutableStateOf<MessageEntity?>(null) }
    var replyTarget by remember { mutableStateOf<MessageEntity?>(null) }
    var recording by remember { mutableStateOf(false) }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordStart by remember { mutableStateOf(0L) }
    var recordFile by remember { mutableStateOf<File?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }
    var showGroupInfo by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var pendingCallVideo by remember { mutableStateOf<Boolean?>(null) }
    var forwardTarget by remember { mutableStateOf<MessageEntity?>(null) }

    val errorKey by vm.error.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    val typing by vm.typing.collectAsStateWithLifecycle()
    val typingPeers = typing[conversationId].orEmpty()

    LaunchedEffect(conversationId, messages.size) {
        vm.markRead(conversationId)
    }
    LaunchedEffect(conversationId, conversation?.draft) {
        val stored = conversation?.draft
        if (!stored.isNullOrEmpty() && draft.isEmpty()) draft = stored
    }
    LaunchedEffect(draft) {
        if (draft.isNotEmpty()) {
            vm.notifyTyping(conversationId)
            delay(600)
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
        runCatching { active.stop() }
        runCatching { active.release() }
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

    val micPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startRecording()
    }

    val callPermLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val wantVideo = pendingCallVideo ?: return@rememberLauncherForActivityResult
        pendingCallVideo = null
        val hasMic = grants[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val hasCam = !wantVideo || grants[Manifest.permission.CAMERA] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (hasMic && hasCam) {
            vm.startCall(conversationId, isVideo = wantVideo)
        }
    }

    fun requestOrStartCall(isVideo: Boolean) {
        val missing = buildList {
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.RECORD_AUDIO)
            }
            if (isVideo && ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                add(Manifest.permission.CAMERA)
            }
        }
        if (missing.isEmpty()) {
            vm.startCall(conversationId, isVideo = isVideo)
        } else {
            pendingCallVideo = isVideo
            callPermLauncher.launch(missing.toTypedArray())
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { recorder?.release() }
            recorder = null
            vm.stopAudioPlayback()
        }
    }

    val pickFiles = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            val caption = draft.trim()
            if (caption.isNotEmpty()) {
                draft = ""
                vm.setDraft(conversationId, "")
            }
            vm.sendAttachmentsFromUris(conversationId, uris, caption)
        }
    }

    val canCallDirect = conversation?.type == ConversationType.DIRECT &&
        (!conversation?.onlineId.isNullOrBlank() || !conversation?.peerUserId.isNullOrBlank())

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
                            val subtitle = if (conversation?.type == ConversationType.GROUP && conversation?.onlineId != null) {
                                if ((conversation?.encryptionVersion ?: 1) >= 2) {
                                    stringResource(R.string.group_sender_keys_v2, conversation?.senderKeyEpoch ?: 1)
                                } else {
                                    stringResource(R.string.group_pairwise_v1)
                                }
                            } else {
                                stringResource(R.string.encrypted_banner)
                            }
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cancel)
                        )
                    }
                },
                actions = {
                    if (canCallDirect && vm.isOnlineSignedIn) {
                        IconButton(onClick = { requestOrStartCall(isVideo = false) }) {
                            Icon(
                                Icons.Filled.Call,
                                contentDescription = stringResource(R.string.call_voice)
                            )
                        }
                        IconButton(onClick = { requestOrStartCall(isVideo = true) }) {
                            Icon(
                                Icons.Filled.Videocam,
                                contentDescription = stringResource(R.string.call_video)
                            )
                        }
                    }
                    if (conversation?.type == ConversationType.GROUP) {
                        IconButton(onClick = { showGroupInfo = true }) {
                            Icon(
                                Icons.Filled.Shield,
                                contentDescription = stringResource(R.string.group_info)
                            )
                        }
                    }
                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = null)
                        }
                        DropdownMenu(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (conversation?.muted == true) stringResource(R.string.chat_unmute)
                                        else stringResource(R.string.chat_mute)
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    vm.toggleConversationMute(conversationId)
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (conversation?.pinned == true) stringResource(R.string.chat_unpin)
                                        else stringResource(R.string.chat_pin)
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    vm.toggleConversationPin(conversationId)
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (conversation?.archived == true) stringResource(R.string.chat_unarchive)
                                        else stringResource(R.string.chat_archive)
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    vm.setConversationArchived(conversationId, conversation?.archived != true)
                                    onBack()
                                }
                            )
                            if (conversation?.type == ConversationType.GROUP) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.group_info)) },
                                    onClick = {
                                        menuExpanded = false
                                        showGroupInfo = true
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        stringResource(R.string.delete_conversation),
                                        color = MaterialTheme.colorScheme.error
                                    )
                                },
                                onClick = {
                                    menuExpanded = false
                                    confirmDelete = true
                                }
                            )
                        }
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
                        Icon(
                            Icons.AutoMirrored.Filled.Reply,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(
                            text = target.body.ifBlank { target.fileName.orEmpty() }.take(60),
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
                    IconButton(onClick = { pickFiles.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Filled.AttachFile, contentDescription = stringResource(R.string.attach_file))
                    }
                    IconButton(
                        onClick = {
                            if (recording) {
                                stopRecording(true)
                            } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                startRecording()
                            } else {
                                micPermLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
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
                                vm.setDraft(conversationId, "")
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
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(messages, key = { it.id }) { message ->
                        MessageBubble(
                            message = message,
                            showSender = conversation?.isGroup == true,
                            isPlayingAudio = playingMessageId == message.id,
                            replyPreview = message.replyToId?.let { id ->
                                messages.firstOrNull { it.id == id }?.let { it.body.ifBlank { it.fileName } }
                            },
                            onLongPress = { actionTarget = message },
                            onOpenAttachment = {
                                if (message.type == MessageType.VOICE || message.type == MessageType.AUDIO) {
                                    vm.toggleAudioPlayback(message)
                                } else if (!message.filePath.isNullOrBlank()) {
                                    val name = message.fileName ?: "attachment"
                                    vm.openAttachment(message.filePath, name) { uri ->
                                        openUri(context, uri, message.fileMime ?: mimeOf(name))
                                    }
                                } else if (!message.remoteObjectPath.isNullOrBlank()) {
                                    vm.downloadAttachment(message.id)
                                }
                            },
                            onDownload = { vm.downloadAttachment(message.id) },
                            onRetry = { vm.retryMediaTransfer(message.id) },
                            onCancelTransfer = { vm.cancelMediaTransfer(message.id) }
                        )
                    }
                }
            }

            errorKey?.let { key ->
                val text = when (key) {
                    "file_too_large" -> stringResource(R.string.file_too_large)
                    "insufficient_storage" -> stringResource(R.string.insufficient_storage)
                    "attachment_pick_failed" -> stringResource(R.string.attachment_pick_failed)
                    "leave_failed" -> stringResource(R.string.leave_failed)
                    "edit_failed" -> stringResource(R.string.edit_failed)
                    "delete_failed" -> stringResource(R.string.delete_failed)
                    "reaction_unavailable" -> stringResource(R.string.reaction_unavailable)
                    "forward_unavailable" -> stringResource(R.string.forward_unavailable)
                    else -> stringResource(R.string.error_unknown)
                }
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(12.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.errorContainer
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        TextButton(onClick = { vm.clearError() }) {
                            Text(stringResource(R.string.cancel))
                        }
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
                    if (target.outgoing && target.type == MessageType.TEXT) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_edit)) },
                            onClick = { editing = target; actionTarget = null },
                            enabled = target.deletedAt == null
                        )
                    }
                    if (target.outgoing) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.chat_delete_for_all)) },
                            onClick = {
                                actionTarget = null
                                vm.deleteForEveryone(target.id)
                            },
                            enabled = target.deletedAt == null
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
                        text = { Text(stringResource(R.string.chat_forward)) },
                        onClick = {
                            actionTarget = null
                            forwardTarget = target
                        },
                        enabled = target.deletedAt == null
                    )
                    DropdownMenuItem(
                        text = {
                            Text(
                                stringResource(R.string.chat_delete_for_me),
                                color = MaterialTheme.colorScheme.error
                            )
                        },
                        onClick = {
                            actionTarget = null
                            vm.deleteForMe(target.id)
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

    val pendingForward = forwardTarget
    if (pendingForward != null) {
        ForwardPickerDialog(
            vm = vm,
            excludeConversationId = conversationId,
            onDismiss = { forwardTarget = null },
            onPick = { targetConversationId ->
                vm.forwardMessage(pendingForward.id, targetConversationId)
                forwardTarget = null
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

    if (showGroupInfo && conversation != null) {
        GroupInfoDialog(
            vm = vm,
            conversation = conversation!!,
            onDismiss = { showGroupInfo = false }
        )
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.delete_conversation)) },
            text = { Text(stringResource(R.string.delete_conversation_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    vm.deleteConversation(conversationId) { onBack() }
                }) {
                    Text(
                        stringResource(R.string.action_delete),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }
}

@Composable
private fun GroupInfoDialog(
    vm: AppViewModel,
    conversation: ConversationEntity,
    onDismiss: () -> Unit
) {
    var members by remember(conversation.memberUserIds) {
        mutableStateOf<List<PeerProfileEntity>>(emptyList())
    }
    var addQuery by remember { mutableStateOf("") }
    val searchResults by vm.accountResults.collectAsStateWithLifecycle()

    LaunchedEffect(conversation.memberUserIds, conversation.senderKeyEpoch) {
        members = vm.groupMemberProfiles(conversation)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.group_info)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = if (conversation.encryptionVersion >= 2) {
                        stringResource(R.string.group_sender_keys_v2, conversation.senderKeyEpoch)
                    } else {
                        stringResource(R.string.group_pairwise_v1)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )

                if (conversation.onlineId != null && conversation.encryptionVersion < 2 && vm.isOnlineSignedIn) {
                    Button(
                        onClick = { vm.upgradeGroupToSenderKeys(conversation.id) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(stringResource(R.string.group_upgrade_v2))
                    }
                }

                if (members.isNotEmpty()) {
                    Text(
                        stringResource(R.string.group_members),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    members.forEach { member ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = member.displayName.ifBlank { member.userId.take(8) },
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (conversation.onlineId != null &&
                                member.userId != vm.currentUserId &&
                                (conversation.myRole == "owner" || conversation.myRole == "admin")
                            ) {
                                TextButton(
                                    onClick = { vm.removeGroupMember(conversation.id, member.userId) }
                                ) {
                                    Text(
                                        stringResource(R.string.group_remove_member),
                                        color = MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }
                }

                if (conversation.onlineId != null &&
                    (conversation.myRole == "owner" || conversation.myRole == "admin")
                ) {
                    OutlinedTextField(
                        value = addQuery,
                        onValueChange = {
                            addQuery = it
                            vm.search(it)
                        },
                        label = { Text(stringResource(R.string.group_add_member_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    searchResults.take(3).forEach { candidate ->
                        if (members.none { it.userId == candidate.userId }) {
                            OutlinedButton(
                                onClick = {
                                    vm.addGroupMember(conversation.id, candidate.userId) {
                                        addQuery = ""
                                        vm.clearSearch()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("+ ${candidate.displayName}")
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    )
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun MessageBubble(
    message: MessageEntity,
    showSender: Boolean,
    isPlayingAudio: Boolean,
    replyPreview: String?,
    onLongPress: () -> Unit,
    onOpenAttachment: () -> Unit,
    onDownload: () -> Unit,
    onRetry: () -> Unit,
    onCancelTransfer: () -> Unit
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
                .widthIn(max = 310.dp)
                .background(bubbleColor, RoundedCornerShape(18.dp))
                .combinedClickable(onClick = {}, onLongClick = onLongPress)
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            if (replyPreview != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.AutoMirrored.Filled.Reply,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.size(4.dp))
                    Text(
                        text = replyPreview.take(80),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.height(4.dp))
            }
            if (showSender && !outgoing) {
                Text(
                    text = message.senderDeviceId.take(8),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (message.deletedAt != null) {
                Text(
                    text = stringResource(R.string.chat_delete_for_all),
                    style = MaterialTheme.typography.bodyMedium,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else if (message.type in setOf(
                    MessageType.IMAGE,
                    MessageType.VIDEO,
                    MessageType.AUDIO,
                    MessageType.VOICE,
                    MessageType.FILE
                )
            ) {
                MediaAttachmentContent(
                    message = message,
                    isPlayingAudio = isPlayingAudio,
                    onOpen = onOpenAttachment,
                    onDownload = onDownload,
                    onRetry = onRetry,
                    onCancel = onCancelTransfer
                )
            } else {
                Text(text = message.body, style = MaterialTheme.typography.bodyLarge)
            }

            if (message.reactions.isNotBlank() && message.deletedAt == null) {
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
            val statusLabel = when {
                outgoing && message.readAt != null -> stringResource(R.string.status_read)
                outgoing && message.status == MessageStatus.DELIVERED -> stringResource(R.string.status_delivered)
                outgoing && message.status == MessageStatus.SENT -> stringResource(R.string.status_sent)
                outgoing && message.status == MessageStatus.QUEUED -> stringResource(R.string.status_queued)
                outgoing && message.status == MessageStatus.FAILED -> stringResource(R.string.status_failed)
                else -> ""
            }
            Text(
                text = buildString {
                    append(formatTimestamp(message.timestamp))
                    if (message.editedAt != null) append(" · " + stringResource(R.string.chat_edit))
                    if (statusLabel.isNotEmpty()) {
                        append(" · ")
                        append(statusLabel)
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun MediaAttachmentContent(
    message: MessageEntity,
    isPlayingAudio: Boolean,
    onOpen: () -> Unit,
    onDownload: () -> Unit,
    onRetry: () -> Unit,
    onCancel: () -> Unit
) {
    val thumbBitmap = remember(message.thumbnailBase64) {
        message.thumbnailBase64?.takeIf { it.isNotBlank() }?.let { b64 ->
            runCatching {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
            }.getOrNull()
        }
    }

    val state = message.transferState
    val isUploading = state == MediaTransferWorker.STATE_UPLOADING ||
        (message.outgoing && state == MediaTransferWorker.STATE_QUEUED && message.remoteObjectPath == null && message.transport == "ONLINE")
    val isDownloading = state == MediaTransferWorker.STATE_DOWNLOADING ||
        (!message.outgoing && state == MediaTransferWorker.STATE_QUEUED)
    val isFailed = state == MediaTransferWorker.STATE_FAILED || message.status == MessageStatus.FAILED
    val needsDownload = !message.outgoing &&
        message.filePath.isNullOrBlank() &&
        !message.remoteObjectPath.isNullOrBlank() &&
        !isDownloading

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if ((message.type == MessageType.IMAGE || message.type == MessageType.VIDEO) && thumbBitmap != null) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(176.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onOpen),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    bitmap = thumbBitmap,
                    contentDescription = message.fileName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                if (message.type == MessageType.VIDEO) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(Color.Black.copy(alpha = 0.55f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Filled.PlayArrow,
                            contentDescription = stringResource(R.string.media_video),
                            tint = Color.White
                        )
                    }
                }
            }
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpen)
        ) {
            val leadIcon = when (message.type) {
                MessageType.VOICE, MessageType.AUDIO ->
                    if (isPlayingAudio) Icons.Filled.Pause else Icons.Filled.PlayArrow
                MessageType.IMAGE -> Icons.Filled.Image
                MessageType.VIDEO -> Icons.Filled.Videocam
                else -> Icons.Filled.Description
            }
            Icon(
                imageVector = leadIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.size(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                val title = when (message.type) {
                    MessageType.VOICE -> stringResource(R.string.voice_note)
                    MessageType.AUDIO -> message.fileName ?: stringResource(R.string.media_audio)
                    MessageType.IMAGE -> message.fileName ?: stringResource(R.string.media_photo)
                    MessageType.VIDEO -> message.fileName ?: stringResource(R.string.media_video)
                    else -> message.fileName ?: stringResource(R.string.file_received)
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val metaParts = buildList {
                    message.durationMs?.takeIf { it > 0L }?.let { add(formatDuration(it)) }
                    message.fileSize?.takeIf { it > 0L }?.let { add(formatSize(it)) }
                    if ((message.mediaWidth ?: 0) > 0 && (message.mediaHeight ?: 0) > 0) {
                        add("${message.mediaWidth}×${message.mediaHeight}")
                    }
                }
                if (metaParts.isNotEmpty()) {
                    Text(
                        text = metaParts.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            when {
                isUploading || isDownloading -> {
                    IconButton(onClick = onCancel) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.media_cancel)
                        )
                    }
                }
                isFailed || state == MediaTransferWorker.STATE_CANCELLED || state == MediaTransferWorker.STATE_PAUSED -> {
                    IconButton(onClick = onRetry) {
                        Icon(
                            Icons.Filled.Refresh,
                            contentDescription = stringResource(R.string.media_retry)
                        )
                    }
                }
                needsDownload -> {
                    IconButton(onClick = onDownload) {
                        Icon(
                            Icons.Filled.Download,
                            contentDescription = stringResource(R.string.media_download)
                        )
                    }
                }
            }
        }

        if (isUploading || isDownloading) {
            val total = (message.cipherSize ?: message.fileSize ?: 0L).coerceAtLeast(1L)
            val fraction = (message.transferBytes.toFloat() / total.toFloat()).coerceIn(0f, 1f)
            if (message.transferBytes > 0L) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(
                text = if (isUploading) stringResource(R.string.media_uploading)
                else stringResource(R.string.media_downloading),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else if (isFailed) {
            val errText = if (message.error?.contains("integrity", ignoreCase = true) == true) {
                stringResource(R.string.integrity_failed)
            } else if (message.error?.contains("storage", ignoreCase = true) == true) {
                stringResource(R.string.insufficient_storage)
            } else if (message.error == "mesh_queue_full") {
                stringResource(R.string.mesh_queue_full)
            } else {
                stringResource(R.string.status_failed)
            }
            Text(
                text = errText,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        if (message.body.isNotBlank() &&
            message.body != message.fileName &&
            message.body != "voice"
        ) {
            Text(text = message.body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun openUri(context: Context, uri: Uri, fallbackMime: String) {
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, context.contentResolver.getType(uri) ?: fallbackMime)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { context.startActivity(intent) }
}

private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "png" -> "image/png"
    "webp" -> "image/webp"
    "mp4", "mov" -> "video/mp4"
    "webm" -> "video/webm"
    "pdf" -> "application/pdf"
    "m4a" -> "audio/mp4"
    "mp3" -> "audio/mpeg"
    "ogg", "opus" -> "audio/ogg"
    "txt" -> "text/plain"
    else -> "*/*"
}
