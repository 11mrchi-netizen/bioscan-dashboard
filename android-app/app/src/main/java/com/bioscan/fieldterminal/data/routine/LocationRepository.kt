package com.bioscan.fieldterminal.data.routine

import android.content.Context
import com.bioscan.fieldterminal.domain.routine.Fix
import com.bioscan.fieldterminal.domain.routine.Stay
import com.bioscan.fieldterminal.domain.routine.detectStays
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

// Routine intelligence R2: syncs the outbox to public.location_fixes, activity_transitions and collector_heartbeats, and
// recomputes public.place_visits from the last 72 hours of fixes (supabase/migrations/20261104_routine_capture.sql).
@Serializable
private data class FixRow(
    val ts: String,
    val lat: Double,
    val lon: Double,
    @SerialName("accuracy_m") val accuracyM: Float? = null,
    val kind: String,
    val activity: String? = null,
)

@Serializable
private data class TransitionRow(val ts: String, val activity: String, val transition: String)

@Serializable
private data class HeartbeatRow(val stream: String, val ts: String)

@Serializable
private data class FixReadRow(val ts: String, val lat: Double, val lon: Double, @SerialName("accuracy_m") val accuracyM: Double? = null)

@Serializable
private data class HeartbeatReadRow(val ts: String)

@Serializable
data class VisitRow(
    @SerialName("start_at") val startAt: String,
    @SerialName("end_at") val endAt: String,
    val lat: Double,
    val lon: Double,
    @SerialName("n_fixes") val nFixes: Int,
    val confidence: Double,
    val open: Boolean,
)

class LocationRepository(private val supabase: SupabaseClient) {

    suspend fun uploadOutbox(context: Context) {
        RoutineOutbox.drain(context, "fixes", RoutineOutbox::decodeFix) { batch ->
            batch.chunked(CHUNK).forEach { chunk ->
                supabase.postgrest.from("location_fixes").upsert(
                    chunk.map { FixRow(Instant.ofEpochMilli(it.ts).toString(), it.lat, it.lon, it.acc, it.kind, it.activity) },
                ) {
                    onConflict = "user_id,ts"
                    ignoreDuplicates = true
                }
            }
        }
        RoutineOutbox.drain(context, "transitions", RoutineOutbox::decodeTransition) { batch ->
            batch.chunked(CHUNK).forEach { chunk ->
                supabase.postgrest.from("activity_transitions").upsert(
                    chunk.map { TransitionRow(Instant.ofEpochMilli(it.ts).toString(), it.activity, it.transition) },
                ) {
                    onConflict = "user_id,ts,activity,transition"
                    ignoreDuplicates = true
                }
            }
        }
        RoutineOutbox.drain(context, "heartbeats", RoutineOutbox::decodeHeartbeat) { batch ->
            batch.chunked(CHUNK).forEach { chunk ->
                supabase.postgrest.from("collector_heartbeats").upsert(
                    chunk.map { HeartbeatRow(it.stream, Instant.ofEpochMilli(it.ts).toString()) },
                ) {
                    onConflict = "user_id,stream,ts"
                    ignoreDuplicates = true
                }
            }
        }
    }

    // Stays over the last 72 hours; the visits that start in the last 48 are replaced (earlier ones could be cut off by
    // the window's edge, so they keep what an earlier run found).
    suspend fun refreshVisits(now: Instant = Instant.now()): List<Stay> {
        val windowStart = now.minusSeconds(72 * 3600)
        val writeFrom = now.minusSeconds(48 * 3600)
        // Paged: a few days of walking at one fix per 30 s passes the API's 1,000-row cap.
        val rows = mutableListOf<FixReadRow>()
        var start = 0L
        while (true) {
            val page = supabase.postgrest.from("location_fixes")
                .select(columns = Columns.list("ts,lat,lon,accuracy_m")) {
                    filter { gte("ts", windowStart.toString()) }
                    order("ts", Order.ASCENDING)
                    range(start, start + PAGE - 1)
                }
                .decodeList<FixReadRow>()
            rows += page
            if (page.size < PAGE) break
            start += PAGE
        }
        val fixes = rows.mapNotNull { r -> parseTimestamp(r.ts)?.let { Fix(it.toEpochMilli(), r.lat, r.lon, r.accuracyM) } }
        val heartbeats = supabase.postgrest.from("collector_heartbeats")
            .select(columns = Columns.list("ts")) {
                filter {
                    eq("stream", "location")
                    gte("ts", windowStart.toString())
                }
            }
            .decodeList<HeartbeatReadRow>()
            .mapNotNull { parseTimestamp(it.ts)?.toEpochMilli() }
        val stays = detectStays(fixes, heartbeats).filter { it.startTs >= writeFrom.toEpochMilli() }
        supabase.postgrest.from("place_visits").delete { filter { gte("start_at", writeFrom.toString()) } }
        if (stays.isNotEmpty()) {
            supabase.postgrest.from("place_visits").insert(
                stays.map {
                    VisitRow(
                        Instant.ofEpochMilli(it.startTs).toString(), Instant.ofEpochMilli(it.endTs).toString(),
                        it.lat, it.lon, it.nFixes, it.confidence, it.open,
                    )
                },
            )
        }
        return stays
    }

    suspend fun recentVisits(since: Instant): List<VisitRow> =
        supabase.postgrest.from("place_visits")
            .select(columns = Columns.list("start_at,end_at,lat,lon,n_fixes,confidence,open")) {
                filter { gte("start_at", since.toString()) }
                order("start_at", Order.DESCENDING)
            }
            .decodeList<VisitRow>()

    // DELETE ALL in Setup: every location row of this account, here and on the phone.
    suspend fun deleteAll(context: Context) {
        RoutineOutbox.clear(context)
        val epoch = "1970-01-01T00:00:00Z"
        supabase.postgrest.from("place_visits").delete { filter { gte("start_at", epoch) } }
        supabase.postgrest.from("location_fixes").delete { filter { gte("ts", epoch) } }
        supabase.postgrest.from("activity_transitions").delete { filter { gte("ts", epoch) } }
        supabase.postgrest.from("collector_heartbeats").delete { filter { eq("stream", "location") } }
    }

    private companion object {
        const val CHUNK = 500
        const val PAGE = 1000L
    }
}
