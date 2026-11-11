package com.offlinemesh.messenger.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import androidx.exifinterface.media.ExifInterface
import com.offlinemesh.messenger.data.MessageType
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.util.Base64

data class LocalMediaProbe(
    val displayName: String,
    val mimeType: String,
    val messageType: String,
    val sizeBytes: Long,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val thumbnailBase64: String? = null
)

/**
 * Extracts local attachment metadata & small encrypted-envelope preview thumbnails without ever
 * loading full media files into memory.
 */
object MediaMetadataHelper {

    /** Maximum attachment size for online E2EE storage transfers (128 MiB). */
    const val MAX_ONLINE_ATTACHMENT_BYTES = 128L * 1024 * 1024L
    private const val MAX_THUMB_EDGE_PX = 160
    private const val THUMB_JPEG_QUALITY = 65

    fun classifyMessageType(mimeType: String?, fileName: String?, isVoiceNote: Boolean = false): String {
        if (isVoiceNote) return MessageType.VOICE
        val mime = mimeType?.lowercase().orEmpty()
        val ext = fileName?.substringAfterLast('.', "")?.lowercase().orEmpty()
        return when {
            mime.startsWith("image/") || ext in setOf("jpg", "jpeg", "png", "webp", "gif", "heic") ->
                MessageType.IMAGE
            mime.startsWith("video/") || ext in setOf("mp4", "mkv", "mov", "webm", "3gp") ->
                MessageType.VIDEO
            mime.startsWith("audio/") || ext in setOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac") ->
                MessageType.AUDIO
            else -> MessageType.FILE
        }
    }

    fun resolveMimeType(context: Context, uri: Uri?, fileName: String): String {
        val fromResolver = uri?.let { runCatching { context.contentResolver.getType(it) }.getOrNull() }
        if (!fromResolver.isNullOrBlank()) return fromResolver
        val ext = fileName.substringAfterLast('.', "").lowercase()
        if (ext.isNotEmpty()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
        }
        return "application/octet-stream"
    }

    fun resolveDisplayNameAndSize(context: Context, uri: Uri): Pair<String, Long> {
        var name = "attachment"
        var size = -1L
        runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIdx >= 0 && !cursor.isNull(nameIdx)) {
                            name = cursor.getString(nameIdx).ifBlank { "attachment" }
                        }
                        val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                            size = cursor.getLong(sizeIdx)
                        }
                    }
                }
        }
        return name to size
    }

    fun hasEnoughDiskSpace(context: Context, requiredPlainBytes: Long): Boolean {
        return runCatching {
            val stat = StatFs(context.filesDir.absolutePath)
            // Require 2.2x file size + 16 MiB headroom for temporary encrypted/decrypted staging.
            val needed = (requiredPlainBytes * 22L / 10L) + 16L * 1024 * 1024L
            stat.availableBytes > needed
        }.getOrDefault(true)
    }

    fun probeFile(
        file: File,
        displayName: String,
        mimeType: String,
        isVoiceNote: Boolean = false
    ): LocalMediaProbe {
        val msgType = classifyMessageType(mimeType, displayName, isVoiceNote)
        val size = file.length()
        return when (msgType) {
            MessageType.IMAGE -> probeImage(file, displayName, mimeType, size)
            MessageType.VIDEO -> probeVideo(file, displayName, mimeType, size)
            MessageType.AUDIO, MessageType.VOICE -> probeAudio(file, displayName, mimeType, msgType, size)
            else -> LocalMediaProbe(
                displayName = displayName,
                mimeType = mimeType,
                messageType = msgType,
                sizeBytes = size
            )
        }
    }

    private fun probeImage(file: File, name: String, mime: String, size: Long): LocalMediaProbe {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        val rawW = bounds.outWidth.takeIf { it > 0 }
        val rawH = bounds.outHeight.takeIf { it > 0 }
        if (rawW == null || rawH == null) {
            return LocalMediaProbe(name, mime, MessageType.IMAGE, size)
        }

        val rotation = runCatching {
            FileInputStream(file).use { fis ->
                when (ExifInterface(fis).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            }
        }.getOrDefault(0)

        val finalW = if (rotation == 90 || rotation == 270) rawH else rawW
        val finalH = if (rotation == 90 || rotation == 270) rawW else rawH

        val thumbB64 = runCatching {
            var sample = 1
            while (rawW / sample > MAX_THUMB_EDGE_PX * 2 || rawH / sample > MAX_THUMB_EDGE_PX * 2) {
                sample *= 2
            }
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val decoded = BitmapFactory.decodeFile(file.absolutePath, opts) ?: return@runCatching null
            val scaled = scaleDownAndRotate(decoded, rotation, MAX_THUMB_EDGE_PX)
            if (scaled !== decoded) decoded.recycle()
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, THUMB_JPEG_QUALITY, out)
            scaled.recycle()
            Base64.getEncoder().encodeToString(out.toByteArray())
        }.getOrNull()

        return LocalMediaProbe(
            displayName = name,
            mimeType = mime,
            messageType = MessageType.IMAGE,
            sizeBytes = size,
            width = finalW,
            height = finalH,
            thumbnailBase64 = thumbB64
        )
    }

    private fun probeVideo(file: File, name: String, mime: String, size: Long): LocalMediaProbe {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val frame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            val thumbB64 = frame?.let { bmp ->
                val scaled = scaleDownAndRotate(bmp, 0, MAX_THUMB_EDGE_PX)
                if (scaled !== bmp) bmp.recycle()
                val out = ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, THUMB_JPEG_QUALITY, out)
                scaled.recycle()
                Base64.getEncoder().encodeToString(out.toByteArray())
            }
            LocalMediaProbe(
                displayName = name,
                mimeType = mime,
                messageType = MessageType.VIDEO,
                sizeBytes = size,
                width = width,
                height = height,
                durationMs = duration,
                thumbnailBase64 = thumbB64
            )
        } catch (_: Exception) {
            LocalMediaProbe(name, mime, MessageType.VIDEO, size)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun probeAudio(
        file: File,
        name: String,
        mime: String,
        msgType: String,
        size: Long
    ): LocalMediaProbe {
        val retriever = MediaMetadataRetriever()
        val duration = try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
        return LocalMediaProbe(
            displayName = name,
            mimeType = mime,
            messageType = msgType,
            sizeBytes = size,
            durationMs = duration
        )
    }

    private fun scaleDownAndRotate(src: Bitmap, rotationDegrees: Int, maxEdge: Int): Bitmap {
        val maxCurrent = maxOf(src.width, src.height).coerceAtLeast(1)
        val scale = if (maxCurrent > maxEdge) maxEdge.toFloat() / maxCurrent.toFloat() else 1f
        if (scale == 1f && rotationDegrees == 0) return src
        val matrix = Matrix().apply {
            if (scale < 1f) postScale(scale, scale)
            if (rotationDegrees != 0) postRotate(rotationDegrees.toFloat())
        }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, matrix, true)
    }
}
