package com.bioscan.fieldterminal.data

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bioscan.fieldterminal.domain.fuel.currentPrepWeek
import com.bioscan.fieldterminal.domain.fuel.upcomingPrepWeek
import java.time.LocalDate
import java.util.concurrent.TimeUnit

// The web's MEAL PREP reads the prep week the phone works out (fuel_week_plans). It used to be written only when Fuel ›
// Plan › Prep was opened for that very week, so the web could wait forever (owner, Nov 2026: "I can't move out of the
// first step"). This keeps the current and the coming week published every few hours, with the same inputs as the page.
class WeekPlanPublishWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val supabase = SupabaseClientProvider.client
        val overview = catching { NutritionRepository(supabase).loadOverview() }.getOrNull()
        val today = LocalDate.now()
        val weeks = listOf(currentPrepWeek(today), upcomingPrepWeek(today)).distinct()
        val ok = catching { FuelPlanRepository(supabase).publishPrepWeeks(applicationContext, overview, weeks) }.isSuccess
        return if (ok) Result.success() else Result.retry()
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "week_plan_publish"

        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<WeekPlanPublishWorker>(6, TimeUnit.HOURS)
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .build(),
            )
        }
    }
}
