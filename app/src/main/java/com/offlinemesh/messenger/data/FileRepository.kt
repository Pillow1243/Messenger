package com.offlinemesh.messenger.data

import android.content.Context
import android.net.Uri
import com.offlinemesh.messenger.core.crypto.FileVault
import com.offlinemesh.messenger.core.media.LocalMediaProbe
import com.offlinemesh.messenger.core.media.MediaMetadataHelper
import com.offlinemesh.messenger.core.mesh.FileChunker
import com.offlinemesh.messenger.core.mesh.MeshInboundPolicy
import com.offlinemesh.messenger.core.mesh.RoutingPolicy
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * Attachment storage. Files are sealed with the Keystore-backed [FileVault] before they are put
 * in the app's private directory; the plaintext staging area is the cache directory, which is
 * wiped as soon as a transfer completes.
 */
class FileRepository(private val context: Context, private val vault: FileVault = FileVault()) {

    data class StoredAttachment(val file: File, val name: String, val size: Long)

    data class StagedOnlineMedia(
        val sealedFile: File,
        val probe: LocalMediaProbe,
        val plainSha256Hex: String
    )

    private fun attachmentsDir(): File = File(context.filesDir, "attachments").apply { mkdirs() }
    private fun stagingDir(): File = File(context.cacheDir, "staging").apply { mkdirs() }
    fun shareDir(): File = File(context.cacheDir, "share").apply { mkdirs() }
    private fun encUploadDir(): File = File(context.cacheDir, "encrypted_uploads").apply { mkdirs() }
    private fun encDownloadDir(): File = File(context.cacheDir, "encrypted_downloads").apply { mkdirs() }
    private fun plainStagingDir(): File = File(context.cacheDir, "plain_staging").apply { mkdirs() }
    private fun voiceFile(id: String): File = File(attachmentsDir(), "voice_$id.enc")
    private fun attachmentFile(id: String): File = File(attachmentsDir(), "file_$id.enc")

    fun sealedVoiceFile(fileId: String): File = voiceFile(fileId)
    fun sealedAttachmentFile(fileId: String): File = attachmentFile(fileId)

    fun voiceFileFor(fileId: String): File = voiceFile(fileId)

    /** Seals a recording/file the user picked and returns the handle used by messages. */
    fun storeOutgoing(source: File, displayName: String, isVoice: Boolean, id: String = UUID.randomUUID().toString()): StoredAttachment {
        val destination = if (isVoice) voiceFile(id) else attachmentFile(id)
        vault.sealFile(source, destination)
        return StoredAttachment(destination, displayName, source.length())
    }

    /**
     * Directory that collects the chunks of one incoming transfer.
     *
     * [fileId] arrives from another device. It is checked here as well as at the call site: this
     * is the last point before the value becomes a path, and a value such as `../../files/x`
     * must never get that far.
     */
    fun stagingFileFor(fileId: String): File {
        require(MeshInboundPolicy.isSafeFileId(fileId)) { "unsafe transfer id" }
        return File(stagingDir(), fileId).apply { mkdirs() }
    }

    /**
     * Removes reassembly directories nothing has been written to for [maxAgeMs]. Chunks are
     * plaintext; a transfer whose remaining packets expired on the sender would otherwise leave
     * them in the cache indefinitely.
     */
    fun purgeStaleStaging(maxAgeMs: Long = RoutingPolicy.STORE_FORWARD_MS + 3_600_000L): Int {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        var removed = 0
        stagingDir().listFiles()?.forEach { dir ->
            val newest = (dir.listFiles()?.maxOfOrNull { it.lastModified() } ?: 0L)
                .coerceAtLeast(dir.lastModified())
            if (newest < cutoff) {
                dir.deleteRec()
                removed++
            }
        }
        return removed
    }

    fun writeChunk(fileId: String, index: Int, bytes: ByteArray) {
        FileChunker.writeChunk(stagingFileFor(fileId), index, bytes)
    }

    /** Assembles chunks and seals the result; returns the sealed file or null if incomplete. */
    fun completeIncoming(
        fileId: String,
        fileName: String,
        totalChunks: Int,
        isVoice: Boolean
    ): StoredAttachment? {
        val staging = stagingFileFor(fileId)
        val assembled = File(staging, "assembled.bin")
        val complete = FileChunker.assemble(staging, totalChunks, assembled) ?: return null
        val destination = if (isVoice) voiceFile(fileId) else attachmentFile(fileId)
        // If sealing fails (for example the disk is full) the chunks stay, so that a retransmitted
        // chunk can complete the transfer later; purgeStaleStaging() removes what never completes.
        vault.sealFile(complete, destination)
        val size = complete.length()
        FileChunker.cleanup(staging)
        return StoredAttachment(destination, fileName, size)
    }

    /** Decrypts a sealed file into the cache so an external viewer can open it. */
    fun exportForOpening(sealed: File, displayName: String): File? {
        if (!sealed.isFile) return null
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").trimStart('.').take(80)
            .ifBlank { "attachment" }
        val target = File(shareDir(), safeName)
        return try {
            vault.open(sealed, target)
            target
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Decrypts a sealed attachment into a private temporary file so that it can be cut into mesh
     * chunks. Unlike [exportForOpening] the copy is named after the message, not the attachment
     * (two files called `image.jpg` no longer overwrite each other mid-transfer), and it lives
     * outside the directory that is shared with other apps. The caller deletes it.
     */
    fun exportForMesh(sealed: File, messageId: String): File? {
        if (!sealed.isFile) return null
        val target = File(plainStagingDir(), "mesh_${UUID.randomUUID()}_${messageId.take(8).filter { it.isLetterOrDigit() }}.bin")
        return try {
            vault.open(sealed, target)
            target
        } catch (e: Exception) {
            target.delete()
            null
        }
    }

    fun delete(sealed: File?) {
        sealed?.delete()
    }

    /** Opens a streaming [InputStream] over a [FileVault]-sealed attachment. */
    fun openSealedStream(sealed: File): InputStream = vault.openRead(sealed)

    /**
     * Stages an arbitrary attachment (up to [MediaMetadataHelper.MAX_ONLINE_ATTACHMENT_BYTES])
     * from a content [Uri] without loading the full payload into heap memory:
     *  1. Streams URI -> temporary cache file while computing plaintext SHA-256.
     *  2. Probes dimensions/duration/thumbnail locally via [MediaMetadataHelper].
     *  3. Seals plaintext into [FileVault] and wipes temporary plaintext immediately.
     */
    fun stageOnlineAttachment(uri: Uri, id: String, isVoiceNote: Boolean = false): StagedOnlineMedia? {
        val (displayName, declaredSize) = MediaMetadataHelper.resolveDisplayNameAndSize(context, uri)
        if (declaredSize > MediaMetadataHelper.MAX_ONLINE_ATTACHMENT_BYTES) {
            throw IllegalArgumentException("file_too_large")
        }
        if (declaredSize > 0 && !MediaMetadataHelper.hasEnoughDiskSpace(context, declaredSize)) {
            throw IllegalStateException("insufficient_storage")
        }
        val mimeType = MediaMetadataHelper.resolveMimeType(context, uri, displayName)
        val safeExt = displayName.substringAfterLast('.', "bin").take(8).filter { it.isLetterOrDigit() }.ifEmpty { "bin" }
        val tempPlain = File(plainStagingDir(), "stage_${id}.$safeExt")
        val targetSealed = if (isVoiceNote) voiceFile(id) else attachmentFile(id)
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var totalBytes = 0L
            val input = context.contentResolver.openInputStream(uri) ?: return null
            input.use { ins ->
                FileOutputStream(tempPlain).use { fos ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val read = ins.read(buf)
                        if (read <= 0) break
                        totalBytes += read
                        if (totalBytes > MediaMetadataHelper.MAX_ONLINE_ATTACHMENT_BYTES) {
                            throw IllegalArgumentException("file_too_large")
                        }
                        digest.update(buf, 0, read)
                        fos.write(buf, 0, read)
                    }
                    fos.fd.sync()
                }
            }
            if (!MediaMetadataHelper.hasEnoughDiskSpace(context, totalBytes)) {
                throw IllegalStateException("insufficient_storage")
            }
            val probe = MediaMetadataHelper.probeFile(tempPlain, displayName, mimeType, isVoiceNote)
            vault.sealFile(tempPlain, targetSealed)
            val shaHex = digest.digest().joinToString("") { "%02x".format(it) }
            return StagedOnlineMedia(
                sealedFile = targetSealed,
                probe = probe,
                plainSha256Hex = shaHex
            )
        } finally {
            runCatching { tempPlain.delete() }
        }
    }

    /** Probes and hashes an already-sealed voice/local file for online E2EE upload. */
    fun probeAndHashSealedFile(
        sealedFile: File,
        displayName: String,
        mimeType: String,
        isVoiceNote: Boolean,
        knownDurationMs: Long? = null
    ): Pair<LocalMediaProbe, String> {
        val tempPlain = File(plainStagingDir(), "probe_${UUID.randomUUID()}.bin")
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var total = 0L
            vault.openRead(sealedFile).use { ins ->
                FileOutputStream(tempPlain).use { fos ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = ins.read(buf)
                        if (n <= 0) break
                        total += n
                        digest.update(buf, 0, n)
                        fos.write(buf, 0, n)
                    }
                }
            }
            val probed = MediaMetadataHelper.probeFile(tempPlain, displayName, mimeType, isVoiceNote)
            val finalProbe = if (knownDurationMs != null && probed.durationMs == null) {
                probed.copy(durationMs = knownDurationMs, sizeBytes = total)
            } else {
                probed.copy(sizeBytes = total)
            }
            val shaHex = digest.digest().joinToString("") { "%02x".format(it) }
            return finalProbe to shaHex
        } finally {
            runCatching { tempPlain.delete() }
        }
    }

    /** Seals a verified decrypted temporary plaintext file into [FileVault] storage. */
    fun sealDownloadedFile(plainTempFile: File, id: String, isVoice: Boolean): File {
        val dest = if (isVoice) voiceFile(id) else attachmentFile(id)
        vault.sealFile(plainTempFile, dest)
        runCatching { plainTempFile.delete() }
        return dest
    }

    fun encryptedUploadTempFile(id: String): File = File(encUploadDir(), "up_$id.enc")
    fun encryptedDownloadPartFile(id: String): File = File(encDownloadDir(), "dl_$id.enc.part")
    fun decryptedTempFile(id: String): File = File(plainStagingDir(), "dec_$id.plain")

    /** Purges abandoned temporary files and shared exports older than [maxAgeMs]. */
    fun cleanupStaleTempFiles(maxAgeMs: Long = 12L * 3600_000L) {
        val cutoff = System.currentTimeMillis() - maxAgeMs
        listOf(shareDir(), encUploadDir(), plainStagingDir()).forEach { dir ->
            dir.listFiles()?.forEach { f ->
                if (f.isFile && f.lastModified() < cutoff) runCatching { f.delete() }
            }
        }
        val partCutoff = System.currentTimeMillis() - 48L * 3600_000L
        encDownloadDir().listFiles()?.forEach { f ->
            if (f.isFile && f.lastModified() < partCutoff) runCatching { f.delete() }
        }
        runCatching { purgeStaleStaging() }
    }

    fun clearAll() {
        attachmentsDir().listFiles()?.forEach { it.deleteRec() }
        stagingDir().listFiles()?.forEach { it.deleteRec() }
        shareDir().listFiles()?.forEach { it.deleteRec() }
        encUploadDir().listFiles()?.forEach { it.deleteRec() }
        encDownloadDir().listFiles()?.forEach { it.deleteRec() }
        plainStagingDir().listFiles()?.forEach { it.deleteRec() }
    }

    private fun File.deleteRec() {
        if (isDirectory) listFiles()?.forEach { it.deleteRec() }
        runCatching { delete() }
    }
}
