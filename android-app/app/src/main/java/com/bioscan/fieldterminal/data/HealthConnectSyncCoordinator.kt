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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope

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
            // Perf pass: a failing record type no longer cancels its siblings. Each step reports its own outcome, the others
            // still finish and write (all upserts are idempotent), and the watermark only advances when every step succeeded,
            // so a failed type is re-read from the same window on the next run.
            val outcomes = linkedMapOf<String, Deferred<Result<Int>>>()
            supervisorScope {
                fun step(name: String, block: suspend () -> Int) {
                    outcomes[name] = async {
                        try {
                            Result.success(block().also { Log.d(TAG, "$name: $it") })
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            Log.e(TAG, "$name failed", e)
                            Result.failure(e)
                        }
                    }
                }
                step("steps") { daily.syncSteps(since, until) }
                step("active_calories") { daily.syncActiveCalories(since, until) }
                step("total_calories") { daily.syncTotalCalories(since, until) }
                step("vo2max") { daily.syncVo2Max(since, until) }
                step("bmr") { daily.syncBmr(since, until) }
                step("body_composition") { daily.syncBodyComposition(since, until) }
                step("vitals") { daily.syncVitals(since, until) }
                step("sleep") { daily.syncSleep(since, until) }
                step("exercise_sessions") { exercise.syncSessions(since, until) }
            }
            val results = outcomes.mapValues { it.value.await() }
            val failedSteps = results.filterValues { it.isFailure }
            if (failedSteps.isNotEmpty()) {
                val first = failedSteps.values.first().exceptionOrNull()
                Log.e(TAG, "syncAll incomplete, failed: ${failedSteps.keys}")
                return HealthConnectSyncResult.Failed("Health Connect sync incomplete (${failedSteps.keys.joinToString()}): ${first?.message ?: "error"}")
            }
            val counts = results.mapValues { it.value.getOrThrow() }

            writeWatermark(until, counts)
            Log.d(TAG, "syncAll succeeded: $counts")
            HealthConnectSyncResult.Success(counts)
        } catch (e: CancellationException) {
            throw e
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
