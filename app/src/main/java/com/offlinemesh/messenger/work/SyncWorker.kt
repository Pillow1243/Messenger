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
 * Periodic housekeeping: expires stale store-and-forward packets and retries anything that is
 * still waiting for a peer to come into range. This is a purely local operation - the app has no
 * network stack at all.
 */
class SyncWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as MessengerApp
        return try {
            app.chats.retryPending()
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
