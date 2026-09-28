package com.bioscan.fieldterminal.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.postgrest
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate

// Zepp integration plan (docs/zepp-integration/). First real client-side call
// to a Supabase Edge Function (zepp-extract) rather than only postgrest --
// everything Zepp-shaped (host, apptoken, response parsing) stays server-side;
// this repository only ever sees a plain success/failure and the sync-status
// row, same boundary DAV-111 already committed to.

@Serializable
data class ZeppSyncStateRow(
    @SerialName("last_synced_track_id") val lastSyncedTrackId: String? = null,
    @SerialName("last_synced_at") val lastSyncedAt: String? = null,
    @SerialName("last_error") val lastError: String? = null,
    @SerialName("last_error_at") val lastErrorAt: String? = null,
)

sealed class ZeppSyncResult {
    data class Success(val summary: String) : ZeppSyncResult()
    data class Failed(val message: String) : ZeppSyncResult()
}

class ZeppRepository(private val supabase: SupabaseClient) {
    // Default is a small window for the automatic on-app-open sync
    // (ZeppSyncWorker) -- keeping recent workouts current, not a backfill.
    // The Settings "SYNC NOW" button calls this with a larger daysBack for an
    // ad hoc historical pull (server caps at 31 days per request either way).
    // metrics=null pulls every METRIC_DEFS entry; several (heart_rate, hrv,
    // sleep, spo2, stress, training_load, vo2max) currently 404/500 -- wrong
    // endpoint shapes, a separate bug from this integration. Restrict to
    // "sport_history" (the one confirmed-working metric) for now so a wide
    // backfill doesn't spend time on calls that can't succeed yet.
    suspend fun sync(daysBack: Long = 3, metrics: String? = "sport_history"): ZeppSyncResult {
        val to = LocalDate.now()
        val from = to.minusDays(daysBack)
        return try {
            // The generic invoke(function, body: T) overload didn't attach a
            // body at all in practice (confirmed on-device: server saw
            // missing_params, no Content-Type in the sent request) -- use the
            // request-builder overload with explicit Ktor calls instead, same
            // pattern GeminiClient.kt already uses successfully in this app.
            val metricsField = metrics?.let { ""","metrics":"$it"""" } ?: ""
            val response = supabase.functions.invoke(function = "zepp-extract") {
                contentType(ContentType.Application.Json)
                setBody("""{"from":"$from","to":"$to"$metricsField}""")
            }
            if (response.status.isSuccess()) {
                ZeppSyncResult.Success(response.bodyAsText())
            } else {
                ZeppSyncResult.Failed("HTTP ${response.status.value}: ${response.bodyAsText()}")
            }
        } catch (e: Exception) {
            ZeppSyncResult.Failed(e.message ?: "unknown error")
        }
    }

    suspend fun getSyncStatus(): ZeppSyncStateRow? =
        supabase.postgrest.from("zepp_sync_state")
            .select()
            .decodeSingleOrNull<ZeppSyncStateRow>()
}
