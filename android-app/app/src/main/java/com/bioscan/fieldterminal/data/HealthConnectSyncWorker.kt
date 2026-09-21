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

// Runs HealthConnectSyncCoordinator.syncAll() and HealthConnectWriteBackRepository.writeBackDaily()
// as real WorkManager background work rather than a plain LaunchedEffect-scoped coroutine.
class HealthConnectSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val syncCoordinator = HealthConnectSyncCoordinator(applicationContext, SupabaseClientProvider.client)
        val readResult = syncCoordinator.syncAll()
        HealthConnectSyncStatus.record(readResult)

        // DAV-154: Run daily Health Connect write-back alongside read sync
        val writeBackRepo = HealthConnectWriteBackRepository(applicationContext, SupabaseClientProvider.client)
        val writeBackResult = writeBackRepo.writeBackDaily()

        android.util.Log.d("HealthConnectSyncWorker", "Read sync: $readResult, Write-back: $writeBackResult")

        return when {
            readResult is HealthConnectSyncResult.Failed || writeBackResult is HealthConnectWriteBackResult.Failed -> {
                if (runAttemptCount < 5) Result.retry() else Result.failure()
            }
            readResult is HealthConnectSyncResult.Unavailable || readResult is HealthConnectSyncResult.NotGranted -> {
                Result.failure()
            }
            else -> Result.success()
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
