package com.bioscan.fieldterminal.data

import android.content.Context
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
    private val defaultLookback: Duration = Duration.ofDays(30) // matches Health Connect's own no-extra-permission read window

    suspend fun syncAll(): HealthConnectSyncResult {
        if (!HealthConnectManager.isAvailable(context)) return HealthConnectSyncResult.Unavailable
        if (!HealthConnectManager.hasAllPermissions(context)) return HealthConnectSyncResult.NotGranted

        return try {
            val until = Instant.now()
            val since = (readWatermark()?.minus(overlap)) ?: until.minus(defaultLookback)

            val daily = HealthConnectDailySyncRepository(context, supabase)
            val exercise = HealthConnectExerciseSyncRepository(context, supabase)
            val counts = linkedMapOf(
                "steps" to daily.syncSteps(since, until),
                "active_calories" to daily.syncActiveCalories(since, until),
                "total_calories" to daily.syncTotalCalories(since, until),
                "vo2max" to daily.syncVo2Max(since, until),
                "bmr" to daily.syncBmr(since, until),
                "body_composition" to daily.syncBodyComposition(since, until),
                "vitals" to daily.syncVitals(since, until),
                "sleep" to daily.syncSleep(since, until),
                "exercise_sessions" to exercise.syncSessions(since, until),
            )
            writeWatermark(until, counts)
            HealthConnectSyncResult.Success(counts)
        } catch (e: Exception) {
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
