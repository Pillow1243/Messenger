package com.offlinemesh.messenger.work

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.offlinemesh.messenger.MessengerApp
import java.util.concurrent.TimeUnit

/**
 * Periodic housekeeping for both transports:
 *  - Purges stale temp files and expired store-and-forward packets.
 *  - Retries queued mesh/online messages and flushes the online outbox.
 *  - Rotates signed/Kyber pre-keys if due and refreshes push token registration.
 */
class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as MessengerApp
        return try {
            runCatching { app.files.cleanupStaleTempFiles() }
            app.chats.retryPending()
            if (app.online.isSignedIn && app.online.networkAvailable) {
                runCatching { app.online.rotatePreKeysIfDue(force = false) }
                runCatching { app.online.syncPushToken() }
                runCatching { app.online.drainOutbox() }
                runCatching { app.online.backfillAll() }
            }
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Sync pass failed", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "SyncWorker"
        private const val WORK_NAME = "mesh-sync"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
