package com.bioscan.fieldterminal.data.routine

import android.content.Context
import android.util.Log
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
import kotlinx.coroutines.CancellationException
import java.time.Instant
import java.util.concurrent.TimeUnit

// Routine intelligence R1 + R2: the hourly heartbeat of both streams.
// Locations: records that the collector is alive, takes one balanced-power fix (a stay's evidence), re-registers the
// transitions and the stay geofence (Play services drops them now and then), uploads the outbox, recomputes visits with
// their places and the trips between them, and every 6 hours mobility_daily and the day's weather (R3).
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
        val places = PlacesRepository(SupabaseClientProvider.client)
        // Each stage on its own: a failing one used to stop every stage after it (a trips insert that failed for days
        // left mobility_daily and the OpenStreetMap suggestions unwritten too).
        val uploaded = stage("upload") { repo.uploadOutbox(ctx) }
        if (uploaded) RoutineSettingsStore.putLong(ctx, RoutineSettingsStore.KEY_LAST_UPLOAD, System.currentTimeMillis())
        if (uploaded && RoutineSettingsStore.isEnabled(ctx, RoutineStream.LOCATION)) {
            // R3: each visit gets its place, then the trips between visits are rebuilt.
            val visits = stage("visits") { repo.refreshVisits(placeIdsFor = { stays -> places.assign(ctx, stays) }) }
            val repair = RoutineSettingsStore.getLong(ctx, RoutineSettingsStore.KEY_TRIPS_REPAIRED) == 0L
            val tripsOk = visits && stage("trips") {
                if (repair) {
                    places.refreshTrips(writeFrom = Instant.now().minusSeconds(REPAIR_DAYS * 86_400))
                    places.recomputeCommuteFlags()
                } else {
                    places.refreshTrips()
                }
            }
            // Mobility, the day's weather and OpenStreetMap suggestions change slowly: every 6 hours (and once over the
            // repaired days).
            if (repair || now - RoutineSettingsStore.getLong(ctx, RoutineSettingsStore.KEY_LAST_MOBILITY_RUN) >= PHONE_INTERVAL_MS) {
                val mobilityOk = stage("mobility") { places.refreshMobility(ctx, days = if (repair && tripsOk) REPAIR_DAYS else 2) }
                stage("suggestions") { places.lookUpSuggestions() }
                if (mobilityOk) RoutineSettingsStore.putLong(ctx, RoutineSettingsStore.KEY_LAST_MOBILITY_RUN, now)
                if (repair && tripsOk && mobilityOk) RoutineSettingsStore.putLong(ctx, RoutineSettingsStore.KEY_TRIPS_REPAIRED, now)
            }
        }

        val phoneDue = now - RoutineSettingsStore.getLong(ctx, RoutineSettingsStore.KEY_LAST_PHONE_RUN) >= PHONE_INTERVAL_MS
        if (phoneDue && RoutineSettingsStore.isActive(ctx, RoutineStream.PHONE_USAGE)) {
            val result = PhoneUsageRepository(SupabaseClientProvider.client).syncRecentDays(PhoneUsageCollector(ctx))
            Log.d("RoutineCaptureWorker", "phone use: $result")
            if (result is PhoneUsageSyncResult.Success) {
                RoutineSettingsStore.putLong(ctx, RoutineSettingsStore.KEY_LAST_PHONE_RUN, now)
            } else {
                RoutineSettingsStore.putLong(ctx, RoutineSettingsStore.KEY_LAST_PHONE_ERROR, now)
            }
        }
        return Result.success()
    }

    // True when [block] finished; a failure is logged and the next stage still runs.
    private suspend fun stage(name: String, block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w("RoutineCaptureWorker", "$name: ${e.message}")
        false
    }

    companion object {
        private const val REPAIR_DAYS = 14L
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
