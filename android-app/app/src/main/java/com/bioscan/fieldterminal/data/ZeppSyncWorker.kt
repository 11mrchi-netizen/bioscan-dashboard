package com.bioscan.fieldterminal.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkerParameters
import androidx.work.WorkManager
import java.time.Duration

// Mirrors HealthConnectSyncWorker.kt's shape exactly -- same WorkManager pattern
// this project already uses for "pull data on app open, survive backgrounding."
class ZeppSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = ZeppRepository(SupabaseClientProvider.client)
        val result = repo.sync()

        android.util.Log.d("ZeppSyncWorker", "Zepp sync: $result")

        return when (result) {
            is ZeppSyncResult.Failed -> if (runAttemptCount < 5) Result.retry() else Result.failure()
            is ZeppSyncResult.Success -> Result.success()
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "zepp_sync"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<ZeppSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, Duration.ofSeconds(30))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
