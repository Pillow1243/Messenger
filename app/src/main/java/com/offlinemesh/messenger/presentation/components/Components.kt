package com.offlinemesh.messenger.presentation.components

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.offlinemesh.messenger.R
import com.offlinemesh.messenger.core.db.MessageEntity
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MessageBubble(
    message: MessageEntity,
    isMine: Boolean,
    onPlayVoice: (File) -> Unit
) {
    val bubbleColor = if (isMine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
    val textColor = if (isMine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        if (isMine) Spacer(modifier = Modifier.weight(1f))
        Column(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(bubbleColor)
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            when (message.type) {
                "VOICE" -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = {
                            message.filePath?.let { onPlayVoice(File(it)) }
                        }) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = stringResource(R.string.voice_note))
                        }
                        Icon(Icons.Filled.Mic, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = stringResource(R.string.voice_note),
                            color = textColor,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                "FILE" -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Description, contentDescription = null, tint = textColor)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = message.fileName ?: message.body,
                            color = textColor,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
                else -> {
                    Text(
                        text = message.body,
                        color = textColor,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = formatTime(message.timestamp),
                    color = textColor.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall
                )
                if (isMine) {
                    Spacer(modifier = Modifier.width(6.dp))
                    StatusIcon(message.status)
                }
            }
        }
        if (!isMine) Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun StatusIcon(status: String) {
    val (icon, desc) = when (status) {
        "QUEUED" -> Icons.Filled.Schedule to stringResource(R.string.queued)
        "SENT" -> Icons.Filled.Check to stringResource(R.string.sent)
        "DELIVERED" -> Icons.Filled.CheckCircle to stringResource(R.string.delivered)
        "READ" -> Icons.Filled.CheckCircle to stringResource(R.string.read)
        "FAILED" -> Icons.Filled.Error to stringResource(R.string.failed)
        else -> Icons.Filled.Check to stringResource(R.string.sent)
    }
    Icon(
        imageVector = icon,
        contentDescription = desc,
        modifier = Modifier.size(14.dp)
    )
}

fun formatTime(timestamp: Long): String {
    val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

fun generateQrBitmap(text: String, size: Int = 512): Bitmap {
    val matrix = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    for (x in 0 until size) {
        for (y in 0 until size) {
            bitmap.setPixel(x, y, if (matrix.get(x, y)) Color.BLACK else Color.WHITE)
        }
    }
    return bitmap
}

@Composable
fun QrImage(text: String, modifier: Modifier = Modifier) {
    val bitmap = generateQrBitmap(text)
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = null,
        modifier = modifier
            .size(280.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(androidx.compose.ui.graphics.Color.White)
            .padding(12.dp)
    )
}

@Composable
fun AvatarCircle(name: String, modifier: Modifier = Modifier) {
    val initial = name.firstOrNull()?.uppercase() ?: "?"
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = initial,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onPrimaryContainer
        )
    }
}
