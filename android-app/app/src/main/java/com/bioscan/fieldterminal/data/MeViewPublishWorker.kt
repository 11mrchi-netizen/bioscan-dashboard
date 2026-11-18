package com.bioscan.fieldterminal.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bioscan.fieldterminal.domain.meview.bodyMeView
import java.time.LocalDate
import java.util.concurrent.TimeUnit

// Keeps the web Dashboard's Me tabs current without the phone's tabs being opened (web transition plan, phase 3): every few
// hours it works each published tab out from the same reads as the tab on screen and saves it (MeViewPublisher). A tab
// that fails is left as it was; the others still go.
class MeViewPublishWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val supabase = SupabaseClientProvider.client
        val today = LocalDate.now()
        val body = catching {
            val metrics = AnalysisRepository(supabase).loadBodyMetrics()
            val checkinRepo = BodyCheckinRepository(supabase)
            val checkins = catching { checkinRepo.loadHistory() }.getOrDefault(emptyList())
            val profile = catching { UserProfileRepository(supabase).loadProfile() }.getOrNull()
            val height = profile?.heightCm ?: catching { checkinRepo.latestHeightCm() }.getOrNull()
            val scale = catching { ScaleReadingRepository(supabase).loadCompositionSeries() }.getOrDefault(emptyList())
            MeViewPublisher.publish(supabase, bodyMeView(metrics, checkins, height, profile, scale, today))
        }
        return if (body.isSuccess) Result.success() else Result.retry()
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
