package com.bioscan.fieldterminal.data

import android.content.Context
import android.util.Log
import com.bioscan.fieldterminal.healthconnect.HealthConnectManager
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.time.Duration
import java.time.Instant

sealed interface HealthConnectSyncResult {
    data object Unavailable : HealthConnectSyncResult
    data object NotGranted : HealthConnectSyncResult
    data class Success(val counts: Map<String, Int>) : HealthConnectSyncResult
    data class Failed(val message: String) : HealthConnectSyncResult
}

// Phase G2. Orchestrates every HealthConnectDailySyncRepository.sync*() call
// and tracks progress via the existing `sync_log` table (sync_source =
// 'health_connect') rather than a new table -- that table already exists
// for exactly this purpose (the old Wellness Project sync logged its runs
// there the same way). A 2-day overlap on the watermark absorbs data that
// lands in Health Connect late (watch -> phone -> Health Connect can lag
// real time by hours); every write this repository makes is an
// onConflict="user_id,date" upsert, so re-processing a couple of already-
// synced days on the next run is naturally idempotent, not a bug.
class HealthConnectSyncCoordinator(private val context: Context, private val supabase: SupabaseClient) {
    private val overlap: Duration = Duration.ofDays(2)
    companion object {
        private const val TAG = "HealthConnectSync"
    }
    // Only applies to a user's very first sync (no watermark yet) -- every
    // later run resumes from the watermark instead. Wide on purpose: with
    // PERMISSION_READ_HEALTH_DATA_HISTORY granted, Health Connect will
    // actually return however much real history exists in this window, not
    // just a rolling 30 days. Analysis wants as much real history as
    // possible; nothing displays this raw, so there's no UI cost to it being
    // wide (Log tab already caps each source at FETCH_LIMIT_PER_SOURCE).
    private val defaultLookback: Duration = Duration.ofDays(3650)

    suspend fun syncAll(): HealthConnectSyncResult {
        if (!HealthConnectManager.isAvailable(context)) return HealthConnectSyncResult.Unavailable
        if (!HealthConnectManager.hasAllPermissions(context)) return HealthConnectSyncResult.NotGranted

        return try {
            val until = Instant.now()
            val since = (readWatermark()?.minus(overlap)) ?: until.minus(defaultLookback)

            Log.d(TAG, "syncAll starting, since=$since until=$until")
            val daily = HealthConnectDailySyncRepository(context, supabase)
            val exercise = HealthConnectExerciseSyncRepository(context, supabase)

            val counts = linkedMapOf<String, Int>()
            // Step-by-step, not one linkedMapOf(... = a(), ... = b()) call --
            // that gave zero visibility into which of the 9 real steps a
            // multi-year sync was actually on. This was the difference
            // between "still working" and "silently hung" being guessable
            // from logcat instead of pure guesswork.
            counts["steps"] = daily.syncSteps(since, until).also { Log.d(TAG, "steps: $it") }
            counts["active_calories"] = daily.syncActiveCalories(since, until).also { Log.d(TAG, "active_calories: $it") }
            counts["total_calories"] = daily.syncTotalCalories(since, until).also { Log.d(TAG, "total_calories: $it") }
            counts["vo2max"] = daily.syncVo2Max(since, until).also { Log.d(TAG, "vo2max: $it") }
            counts["bmr"] = daily.syncBmr(since, until).also { Log.d(TAG, "bmr: $it") }
            counts["body_composition"] = daily.syncBodyComposition(since, until).also { Log.d(TAG, "body_composition: $it") }
            counts["vitals"] = daily.syncVitals(since, until).also { Log.d(TAG, "vitals: $it") }
            counts["sleep"] = daily.syncSleep(since, until).also { Log.d(TAG, "sleep: $it") }
            counts["exercise_sessions"] = exercise.syncSessions(since, until).also { Log.d(TAG, "exercise_sessions: $it") }

            writeWatermark(until, counts)
            Log.d(TAG, "syncAll succeeded: $counts")
            HealthConnectSyncResult.Success(counts)
        } catch (e: Exception) {
            Log.e(TAG, "syncAll failed", e)
            HealthConnectSyncResult.Failed(e.message ?: "Health Connect sync failed.")
        }
    }

    private suspend fun readWatermark(): Instant? =
        supabase.postgrest.from("sync_log")
            .select(columns = Columns.list("synced_at")) {
                filter { eq("sync_source", "health_connect") }
                order("synced_at", Order.DESCENDING)
                limit(1)
            }
            .decodeList<SyncLogRow>()
            .firstOrNull()
            ?.let { Instant.parse(it.syncedAt) }

    private suspend fun writeWatermark(at: Instant, counts: Map<String, Int>) {
        val summary: JsonObject = buildJsonObject {
            counts.forEach { (key, value) -> put(key, JsonPrimitive(value)) }
        }
        supabase.postgrest.from("sync_log").insert(
            NewSyncLogRow(syncedAt = at.toString(), syncSource = "health_connect", changesSummary = summary),
        )
    }
}

@Serializable
private data class SyncLogRow(@SerialName("synced_at") val syncedAt: String)

@Serializable
private data class NewSyncLogRow(
    @SerialName("synced_at") val syncedAt: String,
    @SerialName("sync_source") val syncSource: String,
    @SerialName("changes_summary") val changesSummary: JsonObject,
)
