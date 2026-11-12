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
        // Each step runs even if an earlier one failed, and a failed step is reported: the pass
        // used to swallow every error except the first step's and then report success.
        var failed = false
        suspend fun step(name: String, block: suspend () -> Unit) {
            try {
                block()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                failed = true
                Log.w(TAG, "Sync step '$name' failed", e)
            }
        }
        step("temp files") { app.files.cleanupStaleTempFiles() }
        step("retry") { app.chats.retryPending() }
        if (app.online.isSignedIn && app.online.networkAvailable) {
            step("pre-keys") { app.online.rotatePreKeysIfDue(force = false) }
            step("push token") { app.online.syncPushToken() }
            step("outbox") { app.online.drainOutbox() }
            step("backfill") { app.online.backfillAll() }
        }
        // WorkManager re-runs a periodic request after its backoff on `retry`, and at the next
        // period either way. Giving up after a few attempts avoids a tight loop on a permanent error.
        return if (failed && runAttemptCount < 3) Result.retry() else Result.success()
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
