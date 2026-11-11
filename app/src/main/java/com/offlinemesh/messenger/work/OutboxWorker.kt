package com.offlinemesh.messenger.work

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.offlinemesh.messenger.MessengerApp
import java.util.concurrent.TimeUnit

/**
 * Flushes the online outbox as soon as a network is available, even if the process was killed
 * between the user pressing send and the upload finishing. The worker is intentionally tiny: all
 * scheduling/backoff state lives in the `online_outbox` table, so a re-run is idempotent.
 */
class OutboxWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as MessengerApp
        if (!app.online.isSignedIn) return Result.success()
        return try {
            app.online.drainOutbox()
            Result.success()
        } catch (e: Exception) {
            Log.w(TAG, "Outbox drain failed", e)
            if (runAttemptCount >= MAX_ATTEMPTS) Result.success() else Result.retry()
        }
    }

    companion object {
        private const val TAG = "OutboxWorker"
        private const val WORK_NAME = "online-outbox"
        private const val MAX_ATTEMPTS = 8

        fun enqueueNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<OutboxWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request
            )
        }
    }
}
