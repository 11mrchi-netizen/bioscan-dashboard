package com.bioscan.fieldterminal.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

// Keeps the web Dashboard's Me tabs current without the phone's tabs being opened (web transition plan, phase 3): every few
// hours it works each published tab out from the same reads as the tab on screen and saves it (MeViewPublisher). A tab
// that fails is left as it was; the others still go.
class MeViewPublishWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val supabase = SupabaseClientProvider.client
        val tabs = listOf<suspend () -> com.bioscan.fieldterminal.domain.meview.MeViewDoc>(
            { MeViewSources.body(supabase) },
            { MeViewSources.bloodwork(supabase) },
            { MeViewSources.health(applicationContext, supabase) },
            { MeViewSources.performance(supabase) },
            { com.bioscan.fieldterminal.ui.screens.user.HealthspanMeSource.build() },
            { MeViewSources.routine(supabase) },
            { MeViewSources.exposures(supabase) },
        )
        val results = tabs.map { build -> catching { MeViewPublisher.publish(supabase, build()) } }
        return if (results.any { it.isSuccess }) Result.success() else Result.retry()
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "me_view_publish"

        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<MeViewPublishWorker>(6, TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build(),
            )
        }
    }
}
