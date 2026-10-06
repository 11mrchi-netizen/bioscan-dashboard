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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

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
    // Only applies to the very first sync (no watermark yet) — every later
    // run resumes from the watermark instead. Capped at 90 days: the original
    // 10-year default caused multi-minute first-sync stalls on opening
    // (paginating 3650 days × 9 record types sequentially). 90 days covers
    // the full rolling analysis window every dashboard view uses; older data
    // can be backfilled via Settings → "Sync full history" if ever added.
    private val defaultLookback: Duration = Duration.ofDays(90)

    suspend fun syncAll(): HealthConnectSyncResult {
        if (!HealthConnectManager.isAvailable(context)) return HealthConnectSyncResult.Unavailable
        if (!HealthConnectManager.hasAllPermissions(context)) return HealthConnectSyncResult.NotGranted

        return try {
            val until = Instant.now()
            val since = (readWatermark()?.minus(overlap)) ?: until.minus(defaultLookback)

            Log.d(TAG, "syncAll starting, since=$since until=$until")
            val daily = HealthConnectDailySyncRepository(context, supabase)
            val exercise = HealthConnectExerciseSyncRepository(context, supabase)

            // All 9 steps run concurrently -- each writes to a different
            // table/column using onConflict upserts, so there is zero
            // contention between them. Total time = max(slowest type) instead
            // of sum(all types), which matters most on the 90-day first sync.
            val counts = coroutineScope {
                val dSteps        = async { daily.syncSteps(since, until).also            { Log.d(TAG, "steps: $it") } }
                val dActiveCal    = async { daily.syncActiveCalories(since, until).also   { Log.d(TAG, "active_calories: $it") } }
                val dTotalCal     = async { daily.syncTotalCalories(since, until).also    { Log.d(TAG, "total_calories: $it") } }
                val dVo2max       = async { daily.syncVo2Max(since, until).also           { Log.d(TAG, "vo2max: $it") } }
                val dBmr          = async { daily.syncBmr(since, until).also             { Log.d(TAG, "bmr: $it") } }
                val dBodyComp     = async { daily.syncBodyComposition(since, until).also  { Log.d(TAG, "body_composition: $it") } }
                val dVitals       = async { daily.syncVitals(since, until).also           { Log.d(TAG, "vitals: $it") } }
                val dSleep        = async { daily.syncSleep(since, until).also            { Log.d(TAG, "sleep: $it") } }
                val dExercise     = async { exercise.syncSessions(since, until).also      { Log.d(TAG, "exercise_sessions: $it") } }
                linkedMapOf(
                    "steps"              to dSteps.await(),
                    "active_calories"    to dActiveCal.await(),
                    "total_calories"     to dTotalCal.await(),
                    "vo2max"             to dVo2max.await(),
                    "bmr"                to dBmr.await(),
                    "body_composition"   to dBodyComp.await(),
                    "vitals"             to dVitals.await(),
                    "sleep"              to dSleep.await(),
                    "exercise_sessions"  to dExercise.await(),
                )
            }

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
