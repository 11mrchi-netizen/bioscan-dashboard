package com.bioscan.fieldterminal.data

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bioscan.fieldterminal.domain.afterWindow
import com.bioscan.fieldterminal.domain.startHistoryPull
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.LocalDate

// Pulls the whole Zepp history in the background (domain/ZeppHistoryPull.kt): window after window back from today,
// each one zepp-extract call, the progress stored after every window. A worker may run for about ten minutes, so each
// run stops after RUN_BUDGET_MS and queues the next run behind itself; leaving the screen or closing the app does not
// stop it, and a failed window pauses the pull with its reason, to be carried on from Settings.
class ZeppHistoryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val store = ZeppHistoryStore(applicationContext)
        var state = store.load() ?: startHistoryPull(LocalDate.now())
        if (state.done) return Result.success()
        store.save(state.copy(error = null))
        val repo = ZeppRepository(SupabaseClientProvider.client)
        val deadline = System.currentTimeMillis() + RUN_BUDGET_MS
        while (!state.done && System.currentTimeMillis() < deadline) {
            val (from, to) = state.nextWindow
            val found = try {
                repo.pullHistoryWindow(from, to)
            } catch (e: CancellationException) {
                store.stopped()
                throw e
            } catch (e: Exception) {
                val reason = e.message ?: e.javaClass.simpleName
                // A dropped connection is worth a couple of retries; an expired Zepp token is not.
                if (runAttemptCount < 2 && !reason.contains("token")) {
                    store.beat()
                    return Result.retry()
                }
                store.save(state.copy(error = reason))
                store.stopped()
                return Result.failure()
            }
            state = state.afterWindow(found.workouts, found.bandDays)
            store.save(state)
            delay(PAUSE_BETWEEN_WINDOWS_MS)
        }
        if (state.done) store.stopped() else enqueue(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
        return Result.success()
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "zepp_history_pull"
        private const val RUN_BUDGET_MS = 6 * 60_000L
        private const val PAUSE_BETWEEN_WINDOWS_MS = 1_500L

        /** Starts the pull, or carries on a paused one; [fromScratch] forgets the progress and starts at today. */
        fun start(context: Context, fromScratch: Boolean = false) {
            val store = ZeppHistoryStore(context)
            if (fromScratch) store.clear()
            val state = store.load()
            store.save(state?.copy(error = null) ?: startHistoryPull(LocalDate.now()))
            enqueue(context, ExistingWorkPolicy.KEEP)
        }

        fun stop(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
            ZeppHistoryStore(context).stopped()
        }

        private fun enqueue(context: Context, policy: ExistingWorkPolicy) {
            val request = OneTimeWorkRequestBuilder<ZeppHistoryWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, Duration.ofSeconds(60))
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_WORK_NAME, policy, request)
        }
    }
}
