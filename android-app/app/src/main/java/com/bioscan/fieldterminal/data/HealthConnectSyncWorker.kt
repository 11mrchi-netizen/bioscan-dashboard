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
import com.bioscan.fieldterminal.healthconnect.HealthConnectSyncStatus
import java.time.Duration

// Runs HealthConnectSyncCoordinator.syncAll() as real WorkManager background
// work rather than a plain LaunchedEffect-scoped coroutine tied to
// MainActivity's composition -- see build.gradle.kts's own comment on why:
// a real on-device sync got killed ("Software caused connection abort") the
// moment the user switched apps mid-sync, and WorkManager survives both
// backgrounding and process death, retrying with backoff on failure instead
// of forcing the user to keep the app open in the foreground.
class HealthConnectSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val result = HealthConnectSyncCoordinator(applicationContext, SupabaseClientProvider.client).syncAll()
        HealthConnectSyncStatus.record(result)
        return when (result) {
            is HealthConnectSyncResult.Success -> Result.success()
            is HealthConnectSyncResult.Unavailable, is HealthConnectSyncResult.NotGranted -> Result.failure()
            // Retry with WorkManager's own backoff -- covers exactly the
            // transient network-abort case that motivated this file.
            is HealthConnectSyncResult.Failed -> if (runAttemptCount < 5) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "health_connect_sync"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<HealthConnectSyncWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, Duration.ofSeconds(30))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }
    }
}
