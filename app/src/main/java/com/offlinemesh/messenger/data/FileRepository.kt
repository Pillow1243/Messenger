package com.offlinemesh.messenger.data

import android.content.Context
import com.offlinemesh.messenger.core.crypto.FileVault
import com.offlinemesh.messenger.core.mesh.FileChunker
import java.io.File
import java.util.UUID

/**
 * Attachment storage. Files are sealed with the Keystore-backed [FileVault] before they are put
 * in the app's private directory; the plaintext staging area is the cache directory, which is
 * wiped as soon as a transfer completes.
 */
class FileRepository(private val context: Context, private val vault: FileVault = FileVault()) {

    data class StoredAttachment(val file: File, val name: String, val size: Long)

    private fun attachmentsDir(): File = File(context.filesDir, "attachments").apply { mkdirs() }
    private fun stagingDir(): File = File(context.cacheDir, "staging").apply { mkdirs() }
    fun shareDir(): File = File(context.cacheDir, "share").apply { mkdirs() }
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

    fun stagingFileFor(fileId: String): File = File(stagingDir(), fileId).apply { mkdirs() }

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
        vault.sealFile(complete, destination)
        val size = complete.length()
        FileChunker.cleanup(staging)
        return StoredAttachment(destination, fileName, size)
    }

    /** Decrypts a sealed file into the cache so an external viewer can open it. */
    fun exportForOpening(sealed: File, displayName: String): File? {
        if (!sealed.isFile) return null
        val safeName = displayName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
            .ifBlank { "attachment" }
        val target = File(shareDir(), safeName)
        return try {
            vault.open(sealed, target)
            target
        } catch (e: Exception) {
            null
        }
    }

    fun delete(sealed: File?) {
        sealed?.delete()
    }

    fun clearAll() {
        attachmentsDir().listFiles()?.forEach { it.delete() }
        stagingDir().listFiles()?.forEach { it.delete() }
        shareDir().listFiles()?.forEach { it.delete() }
    }
}
