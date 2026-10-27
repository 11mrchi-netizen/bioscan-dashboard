package com.bioscan.fieldterminal.data.routine

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import java.util.concurrent.TimeUnit

// Routine intelligence R1 + R2: the hourly heartbeat of both streams.
// Locations: records that the collector is alive, takes one balanced-power fix (a stay's evidence), re-registers the
// transitions and the stay geofence (Play services drops them now and then), uploads the outbox and recomputes visits.
// Phone use: every 6 hours, recomputes the last completed days from Android's usage events.
// An upload that fails leaves the outbox as it is for the next run, so the worker always reports success and keeps its
// schedule rather than backing off for hours.
class RoutineCaptureWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val now = System.currentTimeMillis()

        if (LocationCollector.canRun(ctx)) {
            RoutineOutbox.addHeartbeat(ctx, OutboxHeartbeat("location", now))
            // While a trip is being recorded the service already takes fixes.
            if (!TripRecordingService.running) {
                LocationCollector.currentFix(ctx)?.let { loc ->
                    RoutineOutbox.addFix(
                        ctx,
                        OutboxFix(loc.time, loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy else null, "heartbeat"),
                    )
                    if (RoutineSettingsStore.stayCentre(ctx) == null) LocationCollector.armStayGeofence(ctx, loc.latitude, loc.longitude)
                }
            }
            LocationCollector.registerAll(ctx)
            RoutineSettingsStore.putLong(ctx, RoutineSettingsStore.KEY_LAST_HEARTBEAT, now)
        }

        val repo = LocationRepository(SupabaseClientProvider.client)
        runCatching {
            repo.uploadOutbox(ctx)
            RoutineSettingsStore.putLong(ctx, RoutineSettingsStore.KEY_LAST_UPLOAD, System.currentTimeMillis())
            if (RoutineSettingsStore.isEnabled(ctx, RoutineStream.LOCATION)) repo.refreshVisits()
        }.onFailure { android.util.Log.w("RoutineCaptureWorker", "location sync: ${it.message}") }

        val phoneDue = now - RoutineSettingsStore.getLong(ctx, RoutineSettingsStore.KEY_LAST_PHONE_RUN) >= PHONE_INTERVAL_MS
        if (phoneDue && RoutineSettingsStore.isActive(ctx, RoutineStream.PHONE_USAGE)) {
            val result = PhoneUsageRepository(SupabaseClientProvider.client).syncRecentDays(PhoneUsageCollector(ctx))
            android.util.Log.d("RoutineCaptureWorker", "phone use: $result")
            if (result is PhoneUsageSyncResult.Success) {
                RoutineSettingsStore.putLong(ctx, RoutineSettingsStore.KEY_LAST_PHONE_RUN, now)
            } else {
                RoutineSettingsStore.putLong(ctx, RoutineSettingsStore.KEY_LAST_PHONE_ERROR, now)
            }
        }
        return Result.success()
    }

    companion object {
        private const val PERIODIC_NAME = "routine_capture"
        private const val SOON_NAME = "routine_capture_soon"
        private const val PHONE_INTERVAL_MS = 6 * 60 * 60_000L

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RoutineCaptureWorker>(1, TimeUnit.HOURS).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        // After a trip ends, or when a stream is turned on: upload and recompute without waiting for the hour.
        fun runSoon(context: Context) {
            val request = OneTimeWorkRequestBuilder<RoutineCaptureWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(SOON_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        // Turning phone use on runs it at once instead of in up to 6 hours.
        fun forcePhoneRun(context: Context) {
            RoutineSettingsStore.putLong(context, RoutineSettingsStore.KEY_LAST_PHONE_RUN, 0L)
            runSoon(context)
        }
    }
}
