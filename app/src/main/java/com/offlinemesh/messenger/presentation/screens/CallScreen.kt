package com.offlinemesh.messenger.presentation.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.Cameraswitch
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicOff
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.offlinemesh.messenger.R
import com.offlinemesh.messenger.call.CallPhase
import com.offlinemesh.messenger.presentation.AppViewModel
import com.offlinemesh.messenger.presentation.components.Avatar
import com.offlinemesh.messenger.presentation.formatDuration
import kotlinx.coroutines.delay
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

@Composable
fun CallOverlay(vm: AppViewModel) {
    val state by vm.callState.collectAsStateWithLifecycle()
    val localTrack by vm.localVideoTrack.collectAsStateWithLifecycle()
    val remoteTrack by vm.remoteVideoTrack.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(state.phase, state.callId) {
        if (state.phase == CallPhase.ENDED) {
            delay(1_800L)
            vm.dismissEndedCall()
        }
    }

    if (state.phase == CallPhase.IDLE) return

    var elapsedMs by remember(state.callId, state.connectedAtMs) { mutableLongStateOf(0L) }
    LaunchedEffect(state.connectedAtMs, state.phase) {
        val connectedAt = state.connectedAtMs
        if (connectedAt != null && (state.phase == CallPhase.ACTIVE || state.phase == CallPhase.RECONNECTING)) {
            while (true) {
                elapsedMs = (System.currentTimeMillis() - connectedAt).coerceAtLeast(0L)
                delay(500L)
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val hasMic = grants[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val hasCam = !state.isVideo || grants[Manifest.permission.CAMERA] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        if (hasMic && hasCam && state.phase == CallPhase.INCOMING_RINGING) {
            vm.acceptIncomingCall(state.callId)
        }
    }

    val statusText = when (state.phase) {
        CallPhase.INCOMING_RINGING ->
            if (state.isVideo) stringResource(R.string.call_incoming_video)
            else stringResource(R.string.call_incoming_audio)
        CallPhase.OUTGOING_RINGING -> stringResource(R.string.call_ringing)
        CallPhase.CONNECTING -> stringResource(R.string.call_connecting)
        CallPhase.RECONNECTING -> stringResource(R.string.call_reconnecting)
        CallPhase.ACTIVE -> formatDuration(elapsedMs)
        CallPhase.ENDED -> stringResource(R.string.call_ended)
        CallPhase.IDLE -> ""
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1621))
    ) {
        if (state.isVideo && remoteTrack != null && state.isInProgress) {
            WebRtcVideoSurface(
                track = remoteTrack,
                eglContext = remember { vm.eglBaseContext() },
                mirror = false,
                isOverlay = false,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Avatar(
                    name = state.peerName.ifBlank { "?" },
                    size = 104
                )
                Spacer(Modifier.height(20.dp))
                Text(
                    text = state.peerName.ifBlank { state.peerUserId.take(8) },
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White.copy(alpha = 0.78f)
                )
                if (!state.turnConfigured &&
                    (state.phase == CallPhase.CONNECTING ||
                        state.phase == CallPhase.ACTIVE ||
                        state.phase == CallPhase.RECONNECTING)
                ) {
                    Spacer(Modifier.height(12.dp))
                    Surface(
                        shape = RoundedCornerShape(999.dp),
                        color = Color.White.copy(alpha = 0.12f)
                    ) {
                        Text(
                            text = stringResource(R.string.call_stun_only_notice),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White.copy(alpha = 0.85f),
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }

        // Top overlay header when remote video fills the screen
        if (state.isVideo && remoteTrack != null && state.isInProgress) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(16.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black.copy(alpha = 0.45f))
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(
                    text = state.peerName,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.85f)
                )
            }
        }

        // Picture-in-picture local camera preview
        if (state.isVideo && state.cameraEnabled && localTrack != null && state.isInProgress) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(16.dp)
                    .size(width = 112.dp, height = 156.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Color.Black)
            ) {
                WebRtcVideoSurface(
                    track = localTrack,
                    eglContext = remember { vm.eglBaseContext() },
                    mirror = state.usingFrontCamera,
                    isOverlay = true,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        // Bottom call controls
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            if (state.phase == CallPhase.INCOMING_RINGING) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CallActionButton(
                        icon = Icons.Filled.CallEnd,
                        label = stringResource(R.string.call_decline),
                        containerColor = Color(0xFFD93025),
                        contentColor = Color.White,
                        onClick = { vm.declineIncomingCall(state.callId) }
                    )
                    CallActionButton(
                        icon = if (state.isVideo) Icons.Filled.Videocam else Icons.Filled.Call,
                        label = stringResource(R.string.call_accept),
                        containerColor = Color(0xFF1E8E3E),
                        contentColor = Color.White,
                        onClick = {
                            val needed = buildList {
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                                    add(Manifest.permission.RECORD_AUDIO)
                                }
                                if (state.isVideo && ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                                    add(Manifest.permission.CAMERA)
                                }
                            }
                            if (needed.isEmpty()) {
                                vm.acceptIncomingCall(state.callId)
                            } else {
                                permissionLauncher.launch(needed.toTypedArray())
                            }
                        }
                    )
                }
            } else if (state.isInProgress) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CallActionButton(
                        icon = if (state.micMuted) Icons.Filled.MicOff else Icons.Filled.Mic,
                        label = if (state.micMuted) stringResource(R.string.call_unmute_mic) else stringResource(R.string.call_mute_mic),
                        containerColor = if (state.micMuted) Color.White else Color.White.copy(alpha = 0.16f),
                        contentColor = if (state.micMuted) Color.Black else Color.White,
                        onClick = vm::toggleMicMute
                    )
                    CallActionButton(
                        icon = Icons.AutoMirrored.Filled.VolumeUp,
                        label = stringResource(R.string.call_speaker),
                        containerColor = if (state.speakerEnabled) Color.White else Color.White.copy(alpha = 0.16f),
                        contentColor = if (state.speakerEnabled) Color.Black else Color.White,
                        onClick = vm::toggleSpeakerphone
                    )
                    if (state.isVideo) {
                        CallActionButton(
                            icon = if (state.cameraEnabled) Icons.Filled.Videocam else Icons.Filled.VideocamOff,
                            label = stringResource(R.string.call_camera_toggle),
                            containerColor = if (!state.cameraEnabled) Color.White else Color.White.copy(alpha = 0.16f),
                            contentColor = if (!state.cameraEnabled) Color.Black else Color.White,
                            onClick = vm::toggleCamera
                        )
                        CallActionButton(
                            icon = Icons.Filled.Cameraswitch,
                            label = stringResource(R.string.call_flip_camera),
                            containerColor = Color.White.copy(alpha = 0.16f),
                            contentColor = Color.White,
                            onClick = vm::switchCamera
                        )
                    }
                    CallActionButton(
                        icon = Icons.Filled.CallEnd,
                        label = stringResource(R.string.call_end),
                        containerColor = Color(0xFFD93025),
                        contentColor = Color.White,
                        onClick = vm::endCall
                    )
                }
            }
        }
    }
}

@Composable
private fun CallActionButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    containerColor: Color,
    contentColor: Color,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        FilledIconButton(
            onClick = onClick,
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = containerColor,
                contentColor = contentColor
            ),
            modifier = Modifier.size(56.dp)
        ) {
            Icon(icon, contentDescription = label, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.85f)
        )
    }
}

@Composable
private fun WebRtcVideoSurface(
    track: VideoTrack?,
    eglContext: EglBase.Context,
    mirror: Boolean,
    isOverlay: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val renderer = remember {
        SurfaceViewRenderer(context).apply {
            init(eglContext, null)
            setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FILL)
            setEnableHardwareScaler(true)
            setZOrderMediaOverlay(isOverlay)
        }
    }

    LaunchedEffect(mirror) {
        renderer.setMirror(mirror)
    }

    DisposableEffect(track) {
        track?.addSink(renderer)
        onDispose {
            runCatching { track?.removeSink(renderer) }
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            runCatching { renderer.release() }
        }
    }

    AndroidView(
        factory = { renderer },
        modifier = modifier
    )
}
