package com.offlinemesh.messenger.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.offlinemesh.messenger.MessengerApp
import com.offlinemesh.messenger.core.crypto.MediaCipher
import com.offlinemesh.messenger.core.db.MessageDao
import com.offlinemesh.messenger.core.db.SyncStateEntity
import com.offlinemesh.messenger.core.media.MediaMetadataHelper
import com.offlinemesh.messenger.data.MessageStatus
import com.offlinemesh.messenger.data.MessageType
import kotlinx.coroutines.CancellationException
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.AEADBadTagException

/**
 * Durable background WorkManager worker for E2EE online attachment uploads and downloads.
 *
 *  - Upload path: streams plaintext from [com.offlinemesh.messenger.core.crypto.FileVault] ->
 *    [MediaCipher.encryptStreamToFile] (AES-256-GCM) -> resumable TUS/POST upload to private
 *    `mm-media` bucket -> inserts `mm_attachments` row -> delivers E2EE Signal/SenderKey envelope.
 *  - Download path: creates expiring signed URL -> resumable HTTP `Range` download ->
 *    [MediaCipher.decryptFile] (verifies cipher SHA-256 + GCM tag + plaintext SHA-256) ->
 *    seals verified plaintext into [com.offlinemesh.messenger.core.crypto.FileVault].
 */
class MediaTransferWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? MessengerApp ?: return Result.failure()
        val messageId = inputData.getString(KEY_MESSAGE_ID) ?: return Result.failure()
        val mode = inputData.getString(KEY_MODE) ?: return Result.failure()

        return when (mode) {
            MODE_UPLOAD -> runUpload(app, messageId)
            MODE_DOWNLOAD -> runDownload(app, messageId)
            else -> Result.failure()
        }
    }

    private suspend fun runUpload(app: MessengerApp, messageId: String): Result {
        val dao = app.database.messageDao()
        val syncDao = app.database.syncStateDao()
        var msg = dao.getById(messageId) ?: return Result.failure()
        if (msg.transferState == STATE_CANCELLED) return Result.failure()

        val localPath = msg.filePath ?: return failTransfer(dao, messageId, "missing_local_file")
        val sealedFile = File(localPath)
        if (!sealedFile.isFile) return failTransfer(dao, messageId, "missing_local_file")

        if (!app.online.isSignedIn) {
            dao.updateTransferState(messageId, STATE_QUEUED, msg.transferBytes, null)
            return Result.retry()
        }

        val encTemp = app.files.encryptedUploadTempFile(messageId)
        val tusStateKey = "tus_url:$messageId"

        try {
            dao.updateTransferState(messageId, STATE_UPLOADING, msg.transferBytes, null)

            val onlineConvId = app.online.ensureOnlineConversationIdFor(msg.conversationId)
                ?: run {
                    dao.updateTransferState(messageId, STATE_PAUSED, msg.transferBytes, "waiting_for_peer_online")
                    return if (runAttemptCount < 5) Result.retry() else Result.failure()
                }

            val objectPath = msg.remoteObjectPath?.takeIf { it.isNotBlank() }
                ?: "$onlineConvId/${UUID.randomUUID()}.enc"

            val needEncrypt = msg.mediaKeyBase64.isNullOrBlank() ||
                msg.mediaIvBase64.isNullOrBlank() ||
                !encTemp.isFile ||
                (msg.cipherSize != null && encTemp.length() != msg.cipherSize)

            if (needEncrypt) {
                syncDao.remove(tusStateKey)
                val enc = app.files.openSealedStream(sealedFile).use { plainStream ->
                    MediaCipher.encryptStreamToFile(
                        plainInput = plainStream,
                        cipherDestFile = encTemp
                    ) { written ->
                        if (isStopped) throw CancellationException("Transfer cancelled")
                        kotlinx.coroutines.runBlocking {
                            dao.updateTransferState(messageId, STATE_UPLOADING, written / 4L, null)
                        }
                    }
                }
                dao.updateMediaUploadMetadata(
                    id = messageId,
                    attachmentId = msg.attachmentRemoteId,
                    objectPath = objectPath,
                    cipherSha256 = enc.cipherSha256Hex,
                    cipherSize = enc.cipherSizeBytes,
                    keyB64 = enc.keyBase64,
                    ivB64 = enc.ivBase64,
                    transferState = STATE_UPLOADING,
                    transferBytes = 0L
                )
                msg = dao.getById(messageId) ?: return Result.failure()
            } else if (msg.remoteObjectPath != objectPath) {
                msg = msg.copy(remoteObjectPath = objectPath)
                dao.upsert(msg)
            }

            if (isStopped || dao.getById(messageId)?.transferState == STATE_CANCELLED) {
                throw CancellationException("Transfer cancelled")
            }

            val savedTusUrl = syncDao.get(tusStateKey)
            var lastBytesPersisted = 0L
            val attachmentId = app.online.uploadEncryptedAttachment(
                messageId = messageId,
                onlineConversationId = onlineConvId,
                objectPath = objectPath,
                encryptedFile = encTemp,
                cipherSha256 = msg.cipherSha256.orEmpty(),
                resumeTusUrl = savedTusUrl,
                onTusUrlCreated = { url ->
                    kotlinx.coroutines.runBlocking {
                        syncDao.put(SyncStateEntity(tusStateKey, url))
                    }
                },
                onProgress = { uploaded, total ->
                    if (isStopped) throw CancellationException("Transfer cancelled")
                    if (uploaded - lastBytesPersisted >= 64 * 1024L || uploaded >= total) {
                        lastBytesPersisted = uploaded
                        kotlinx.coroutines.runBlocking {
                            dao.updateTransferState(messageId, STATE_UPLOADING, uploaded, null)
                        }
                    }
                }
            )

            syncDao.remove(tusStateKey)
            runCatching { encTemp.delete() }

            val totalSize = msg.fileSize ?: msg.cipherSize ?: lastBytesPersisted
            val finalized = (dao.getById(messageId) ?: msg).copy(
                attachmentRemoteId = attachmentId,
                remoteObjectPath = objectPath,
                transferState = STATE_COMPLETED,
                transferBytes = totalSize,
                error = null
            )
            dao.upsert(finalized)

            val conv = app.database.conversationDao().getById(finalized.conversationId)
            if (conv != null) {
                val sendRes = app.online.deliver(finalized, conv)
                if (sendRes.ok) {
                    dao.updateStatus(finalized.id, MessageStatus.SENT)
                } else {
                    app.online.enqueue(finalized, conv)
                }
            }
            return Result.success()
        } catch (ce: CancellationException) {
            val current = dao.getById(messageId)
            if (current?.transferState == STATE_CANCELLED) {
                syncDao.remove(tusStateKey)
                runCatching { encTemp.delete() }
                return Result.failure()
            }
            dao.updateTransferState(messageId, STATE_PAUSED, current?.transferBytes ?: 0L, null)
            return Result.failure()
        } catch (e: Exception) {
            val latest = dao.getById(messageId)
            if (latest?.transferState == STATE_CANCELLED) {
                syncDao.remove(tusStateKey)
                runCatching { encTemp.delete() }
                return Result.failure()
            }
            return if (runAttemptCount < 4) {
                dao.updateTransferState(
                    messageId,
                    STATE_PAUSED,
                    latest?.transferBytes ?: 0L,
                    e.message?.take(120)
                )
                Result.retry()
            } else {
                failTransfer(dao, messageId, e.message?.take(120) ?: "upload_failed")
            }
        }
    }

    private suspend fun runDownload(app: MessengerApp, messageId: String): Result {
        val dao = app.database.messageDao()
        val msg = dao.getById(messageId) ?: return Result.failure()
        if (msg.transferState == STATE_CANCELLED) return Result.failure()
        if (!msg.filePath.isNullOrBlank() && File(msg.filePath).isFile) {
            dao.updateTransferState(messageId, STATE_COMPLETED, msg.fileSize ?: 0L, null)
            return Result.success()
        }

        val objectPath = msg.remoteObjectPath?.takeIf { it.isNotBlank() }
            ?: return failTransfer(dao, messageId, "missing_remote_object")
        val keyB64 = msg.mediaKeyBase64?.takeIf { it.isNotBlank() }
            ?: return failTransfer(dao, messageId, "missing_media_key")
        val ivB64 = msg.mediaIvBase64?.takeIf { it.isNotBlank() }
            ?: return failTransfer(dao, messageId, "missing_media_iv")

        if (!MediaMetadataHelper.hasEnoughDiskSpace(applicationContext, msg.fileSize ?: 1024L)) {
            return failTransfer(dao, messageId, "insufficient_storage")
        }
        if (!app.online.isSignedIn) {
            dao.updateTransferState(messageId, STATE_PAUSED, msg.transferBytes, "auth_required")
            return Result.failure()
        }

        val partFile = app.files.encryptedDownloadPartFile(messageId)
        val decTemp = app.files.decryptedTempFile(messageId)

        try {
            dao.updateTransferState(messageId, STATE_DOWNLOADING, partFile.takeIf { it.isFile }?.length() ?: 0L, null)
            val signedUrl = app.online.createSignedAttachmentUrl(objectPath)
            var lastBytes = 0L
            app.online.downloadEncryptedAttachment(
                messageId = messageId,
                signedUrl = signedUrl,
                destPartFile = partFile,
                expectedTotalBytes = msg.cipherSize
            ) { downloaded, total ->
                if (isStopped) throw CancellationException("Download cancelled")
                if (downloaded - lastBytes >= 64 * 1024L || (total > 0 && downloaded >= total)) {
                    lastBytes = downloaded
                    kotlinx.coroutines.runBlocking {
                        dao.updateTransferState(messageId, STATE_DOWNLOADING, downloaded, null)
                    }
                }
            }

            if (isStopped || dao.getById(messageId)?.transferState == STATE_CANCELLED) {
                throw CancellationException("Download cancelled")
            }

            MediaCipher.decryptFile(
                cipherFile = partFile,
                plainDestFile = decTemp,
                keyBase64 = keyB64,
                ivBase64 = ivB64,
                expectedCipherSha256Hex = msg.cipherSha256,
                expectedPlainSha256Hex = msg.fileSha256.takeIf { it.isNotBlank() }
            )

            val plainSize = decTemp.length()
            val sealed = app.files.sealDownloadedFile(
                plainTempFile = decTemp,
                id = messageId,
                isVoice = msg.type == MessageType.VOICE
            )
            runCatching { partFile.delete() }
            dao.markMediaDownloaded(messageId, sealed.absolutePath, plainSize)
            return Result.success()
        } catch (tamper: AEADBadTagException) {
            runCatching { partFile.delete() }
            runCatching { decTemp.delete() }
            return failTransfer(dao, messageId, "integrity_verification_failed")
        } catch (tamper: SecurityException) {
            runCatching { partFile.delete() }
            runCatching { decTemp.delete() }
            return failTransfer(dao, messageId, "integrity_verification_failed")
        } catch (ce: CancellationException) {
            runCatching { decTemp.delete() }
            val latest = dao.getById(messageId)
            if (latest?.transferState == STATE_CANCELLED) {
                runCatching { partFile.delete() }
                return Result.failure()
            }
            dao.updateTransferState(messageId, STATE_PAUSED, partFile.takeIf { it.isFile }?.length() ?: 0L, null)
            return Result.failure()
        } catch (e: Exception) {
            runCatching { decTemp.delete() }
            val latest = dao.getById(messageId)
            if (latest?.transferState == STATE_CANCELLED) {
                runCatching { partFile.delete() }
                return Result.failure()
            }
            return if (runAttemptCount < 4) {
                dao.updateTransferState(
                    messageId,
                    STATE_PAUSED,
                    partFile.takeIf { it.isFile }?.length() ?: 0L,
                    e.message?.take(120)
                )
                Result.retry()
            } else {
                failTransfer(dao, messageId, e.message?.take(120) ?: "download_failed")
            }
        }
    }

    private suspend fun failTransfer(dao: MessageDao, messageId: String, reason: String): Result {
        val current = dao.getById(messageId)
        dao.updateTransferState(messageId, STATE_FAILED, current?.transferBytes ?: 0L, reason)
        return Result.failure()
    }

    companion object {
        private const val KEY_MESSAGE_ID = "message_id"
        private const val KEY_MODE = "mode"
        const val MODE_UPLOAD = "upload"
        const val MODE_DOWNLOAD = "download"

        const val STATE_QUEUED = "QUEUED"
        const val STATE_UPLOADING = "UPLOADING"
        const val STATE_DOWNLOADING = "DOWNLOADING"
        const val STATE_PAUSED = "PAUSED"
        const val STATE_COMPLETED = "COMPLETED"
        const val STATE_FAILED = "FAILED"
        const val STATE_CANCELLED = "CANCELLED"

        fun uniqueWorkName(mode: String, messageId: String): String = "mm_media_${mode}_$messageId"

        fun enqueueUpload(context: Context, messageId: String) {
            enqueue(context, MODE_UPLOAD, messageId)
        }

        fun enqueueDownload(context: Context, messageId: String) {
            enqueue(context, MODE_DOWNLOAD, messageId)
        }

        fun cancel(context: Context, messageId: String) {
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork(uniqueWorkName(MODE_UPLOAD, messageId))
            wm.cancelUniqueWork(uniqueWorkName(MODE_DOWNLOAD, messageId))
        }

        private fun enqueue(context: Context, mode: String, messageId: String) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val req = OneTimeWorkRequestBuilder<MediaTransferWorker>()
                .setConstraints(constraints)
                .setInputData(workDataOf(KEY_MODE to mode, KEY_MESSAGE_ID to messageId))
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .addTag("mm_media_transfer")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                uniqueWorkName(mode, messageId),
                ExistingWorkPolicy.REPLACE,
                req
            )
        }
    }
}
