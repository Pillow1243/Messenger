package com.offlinemesh.messenger.presentation.screens

import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.GroupAdd
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.viewmodel.compose.viewModel
import com.offlinemesh.messenger.R
import com.offlinemesh.messenger.presentation.components.AvatarCircle
import com.offlinemesh.messenger.presentation.components.MessageBubble
import com.offlinemesh.messenger.presentation.viewmodel.ChatListViewModel
import com.offlinemesh.messenger.presentation.viewmodel.ChatViewModel
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatListScreen(
    onOpenChat: (String) -> Unit,
    onOpenDiscover: () -> Unit,
    onOpenContacts: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenGroupCreate: () -> Unit
) {
    val vm: ChatListViewModel = viewModel()
    val conversations by vm.conversations.collectAsState(initial = emptyList())
    val peers by vm.peers.collectAsState()
    val isRunning by vm.isRunning.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.chats)) },
                actions = {
                    IconButton(onClick = onOpenGroupCreate) {
                        Icon(Icons.Filled.GroupAdd, contentDescription = stringResource(R.string.new_group))
                    }
                    IconButton(onClick = onOpenDiscover) {
                        Icon(Icons.Filled.QrCodeScanner, contentDescription = stringResource(R.string.discover))
                    }
                    IconButton(onClick = onOpenContacts) {
                        Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.contacts))
                    }
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.settings))
                    }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isRunning) stringResource(R.string.mesh_status_on) else stringResource(R.string.mesh_status_off),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isRunning) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                Spacer(modifier = Modifier.padding(horizontal = 8.dp))
                Text(
                    text = "${peers.size} ${stringResource(R.string.peers_connected)}",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.weight(1f))
                Button(onClick = { if (isRunning) vm.stopMesh() else vm.startMesh() }) {
                    Text(if (isRunning) stringResource(R.string.disconnect) else stringResource(R.string.connect))
                }
            }
            if (conversations.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(stringResource(R.string.no_chats_yet), style = MaterialTheme.typography.titleMedium)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(stringResource(R.string.no_chats_subtitle), style = MaterialTheme.typography.bodyMedium)
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = onOpenDiscover) {
                            Text(stringResource(R.string.start_discovery))
                        }
                    }
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(conversations) { conv ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenChat(conv.id) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AvatarCircle(name = conv.title)
                            Spacer(modifier = Modifier.padding(horizontal = 8.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(conv.title, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    conv.lastMessagePreview.ifEmpty { stringResource(R.string.encrypted_badge) },
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1
                                )
                            }
                            if (conv.unreadCount > 0) {
                                BadgedBox(badge = { Badge { Text(conv.unreadCount.toString()) } }) {
                                    Spacer(modifier = Modifier.padding(8.dp))
                                }
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
fun ChatScreen(onBack: () -> Unit) {
    val vm: ChatViewModel = viewModel()
    val messages by vm.messages.collectAsState(initial = emptyList())
    val listState = rememberLazyListState()
    var input by remember { mutableStateOf("") }
    var isRecording by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val recorder = remember { mutableStateOf<MediaRecorder?>(null) }
    val recordingFile = remember { mutableStateOf<File?>(null) }
    val recordingStart = remember { mutableStateOf(0L) }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }
    LaunchedEffect(Unit) {
        vm.markRead()
    }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            try {
                val cursor = context.contentResolver.query(uri, null, null, null, null)
                var fileName = "file"
                cursor?.use {
                    val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx != -1 && it.moveToFirst()) {
                        fileName = it.getString(idx)
                    }
                }
                val inputStream = context.contentResolver.openInputStream(uri)
                val tempFile = File(context.cacheDir, fileName)
                inputStream?.use { input ->
                    tempFile.outputStream().use { output -> input.copyTo(output) }
                }
                vm.sendFile(tempFile, fileName)
            } catch (e: Exception) {
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(stringResource(R.string.group_chat))
                        Text(
                            stringResource(R.string.encrypted_badge),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = null)
                    }
                }
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { filePicker.launch("*/*") }) {
                    Icon(Icons.Filled.AttachFile, contentDescription = stringResource(R.string.attach_file))
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text(stringResource(R.string.type_message)) },
                    modifier = Modifier.weight(1f),
                    singleLine = true
                )
                if (input.isNotBlank()) {
                    IconButton(onClick = {
                        vm.sendText(input)
                        input = ""
                    }) {
                        Icon(Icons.Filled.Send, contentDescription = stringResource(R.string.send))
                    }
                } else {
                    IconButton(onClick = {
                        if (!isRecording) {
                            try {
                                val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    MediaRecorder(context)
                                } else {
                                    @Suppress("DEPRECATION")
                                    MediaRecorder()
                                }
                                val file = File(context.cacheDir, "voice_${System.currentTimeMillis()}.m4a")
                                rec.setAudioSource(MediaRecorder.AudioSource.MIC)
                                rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                                rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                                rec.setOutputFile(file.absolutePath)
                                rec.prepare()
                                rec.start()
                                recorder.value = rec
                                recordingFile.value = file
                                recordingStart.value = System.currentTimeMillis()
                                isRecording = true
                            } catch (e: Exception) {
                            }
                        } else {
                            try {
                                recorder.value?.stop()
                                recorder.value?.release()
                                recorder.value = null
                                val file = recordingFile.value
                                if (file != null) {
                                    vm.sendVoice(file, System.currentTimeMillis() - recordingStart.value)
                                }
                            } catch (e: Exception) {
                            }
                            isRecording = false
                        }
                    }) {
                        Icon(
                            if (isRecording) Icons.Filled.Stop else Icons.Filled.Mic,
                            contentDescription = if (isRecording) stringResource(R.string.stop_recording) else stringResource(R.string.record_voice)
                        )
                    }
                }
            }
        }
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            verticalArrangement = Arrangement.Bottom
        ) {
            items(messages) { message ->
                MessageBubble(
                    message = message,
                    isMine = false,
                    onPlayVoice = { file ->
                        try {
                            val player = MediaPlayer()
                            player.setDataSource(file.absolutePath)
                            player.prepare()
                            player.start()
                            player.setOnCompletionListener { it.release() }
                        } catch (e: Exception) {
                        }
                    }
                )
            }
        }
    }
}
