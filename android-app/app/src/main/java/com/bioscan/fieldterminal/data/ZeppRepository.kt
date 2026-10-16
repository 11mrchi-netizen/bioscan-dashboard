package com.bioscan.fieldterminal.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.functions.functions
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import com.bioscan.fieldterminal.domain.training.movementKey
import com.bioscan.fieldterminal.domain.training.parsePlannedLifts
import com.bioscan.fieldterminal.domain.training.warmupSets
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
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

@Serializable
private data class ZeppPlannedRow(
    val id: Long,
    val title: String,
    @SerialName("scheduled_date") val scheduledDate: String,
    @SerialName("week_index") val weekIndex: Int = 0,
    val prescription: JsonElement? = null,
    @SerialName("zepp_template_id") val zeppTemplateId: String? = null,
    @SerialName("zepp_pushed_hash") val zeppPushedHash: String? = null,
)

sealed class ZeppSyncResult {
    data class Success(val summary: String) : ZeppSyncResult()
    data class Failed(val message: String) : ZeppSyncResult()
}

@Serializable
private data class FirstWorkoutRow(@SerialName("start_time") val startTime: String)

class ZeppRepository(private val supabase: SupabaseClient) {
    // Default is a small window for the automatic on-app-open sync
    // (ZeppSyncWorker) -- keeping recent workouts current, not a backfill.
    // The Settings "SYNC NOW" button calls this with a larger daysBack for an
    // ad hoc historical pull (server caps at 31 days per request either way).
    // band_data (v9) is the unified heart_rate/hrv/sleep/spo2 pull via the
    // corrected /v1/ endpoint; training_load and vo2max still 404/500.
    suspend fun sync(daysBack: Long = 3, metrics: String? = "sport_history,stress,band_data"): ZeppSyncResult {
        val to = LocalDate.now()
        return syncRange(to.minusDays(daysBack), to, metrics)
    }

    /** The day of the oldest stored workout of [type] (null when there is none). */
    suspend fun earliestWorkoutDate(type: String = "run"): LocalDate? =
        supabase.postgrest.from("exercise_sessions")
            .select(columns = Columns.list("start_time")) {
                filter { eq("type", type) }
                order("start_time", Order.ASCENDING)
                limit(1)
            }
            .decodeList<FirstWorkoutRow>()
            .firstOrNull()
            ?.let { runCatching { LocalDate.parse(it.startTime.take(10)) }.getOrNull() }

    /**
     * Re-reads every workout from [from] to [to] from Zepp, 31 days per call, so each one Zepp has gets Zepp's own
     * distance (GPS, or the summary for a treadmill) on every stored copy of it. The one-off repair for the runs Health
     * Connect stored at about twice their distance (January to mid-August 2026); safe to run again.
     */
    suspend fun resyncWorkoutHistory(from: LocalDate, to: LocalDate = LocalDate.now(), onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }): ZeppSyncResult {
        val windows = com.bioscan.fieldterminal.domain.syncWindows(from, to)
        windows.forEachIndexed { i, (start, end) ->
            onProgress(i, windows.size)
            when (val result = syncRange(start, end, "sport_history")) {
                is ZeppSyncResult.Failed -> return ZeppSyncResult.Failed("$start to $end: ${result.message}")
                is ZeppSyncResult.Success -> Unit
            }
        }
        onProgress(windows.size, windows.size)
        return ZeppSyncResult.Success("${windows.size} windows from $from")
    }

    // A fixed date range (at most 31 days, the server's cap): RE-IMPORT FROM ZEPP on one workout pulls the days around it.
    suspend fun syncRange(from: LocalDate, to: LocalDate, metrics: String? = "sport_history"): ZeppSyncResult {
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

    // Feasibility check for Zepp's template/schedule cloud API: the server asks the Zepp API read-only
    // and stores the raw answers in zepp_raw_extracts (metric "training_probe"). Returns the short summary.
    suspend fun probeTrainingApi(action: String? = null): String = try {
        val response = supabase.functions.invoke(function = "zepp-training") {
            contentType(ContentType.Application.Json)
            setBody(action?.let { """{"action":"$it"}""" } ?: "{}")
        }
        response.bodyAsText().take(1500)
    } catch (e: Exception) {
        e.message ?: "unknown error"
    }

    // Block -> Zepp. Every strength session planned in the next `days` days becomes a custom workout in the
    // Zepp cloud (sets, reps, loads and warm-ups from the frozen prescription); the phone pulls templates by
    // itself, calendar entries it does not, so the day lives in the template's name and in this app.
    // An unchanged session is not pushed again (hash); a changed one replaces its old template; templates of
    // days already past are removed so the watch list stays short.
    suspend fun pushUpcoming(days: Long = 7): String { return try {
        val today = LocalDate.now()
        val table = supabase.postgrest.from("planned_sessions")
        val cols = Columns.list("id,title,scheduled_date,week_index,prescription,zepp_template_id,zepp_pushed_hash")
        val upcoming = table.select(columns = cols) {
            filter { gte("scheduled_date", today.toString()); lte("scheduled_date", today.plusDays(days).toString()) }
            order("scheduled_date", Order.ASCENDING)
        }.decodeList<ZeppPlannedRow>()
        val past = table.select(columns = cols) {
            filter { lt("scheduled_date", today.toString()); neq("zepp_template_id", "") }
        }.decodeList<ZeppPlannedRow>()

        val deleteIds = past.mapNotNull { it.zeppTemplateId }.toMutableList()
        val specs = mutableMapOf<Long, Pair<String, JsonObject>>() // planned id -> (hash, template json)
        for (row in upcoming) {
            val lifts = parsePlannedLifts(row.prescription)
            if (lifts.isEmpty()) continue
            val spec = buildJsonObject {
                put("key", row.id.toString())
                put("title", "${row.scheduledDate.substring(5)} W${row.weekIndex} ${row.title}".take(60))
                put("description", "planned session ${row.id}")
                putJsonArray("lifts") {
                    for (p in lifts) add(buildJsonObject {
                        val kg = p.targetKg ?: 0.0
                        put("movement", movementKey(p.exercise))
                        put("sets", p.sets?.max?.toInt() ?: 1)
                        put("reps", p.reps?.min?.toInt() ?: 1)
                        put("kg", kg)
                        putJsonArray("warmups") {
                            val ramp = when (p.loadKind) {
                                "pct_1rm", "fixed" -> warmupSets(kg)
                                "added" -> warmupSets(kg, bodyweight = true)
                                else -> emptyList()
                            }
                            for ((r, w) in ramp) add(buildJsonArray { add(JsonPrimitive(r)); add(JsonPrimitive(w)) })
                        }
                    })
                }
            }
            val hash = spec.toString().hashCode().toString()
            if (row.zeppTemplateId != null && row.zeppPushedHash == hash) continue
            row.zeppTemplateId?.let { deleteIds += it }
            specs[row.id] = hash to spec
        }
        if (specs.isEmpty() && deleteIds.isEmpty()) return "Zepp templates up to date"

        val response = supabase.functions.invoke(function = "zepp-training") {
            contentType(ContentType.Application.Json)
            setBody(buildJsonObject {
                put("action", "push_templates")
                putJsonArray("templates") { specs.values.forEach { add(it.second) } }
                putJsonArray("deleteIds") { deleteIds.forEach { add(JsonPrimitive(it)) } }
            }.toString())
        }
        if (!response.status.isSuccess()) return "Zepp push failed: HTTP ${response.status.value}"
        val answer = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val now = java.time.Instant.now().toString()
        for (c in answer["created"]?.jsonArray.orEmpty()) {
            val key = c.jsonObject["key"]!!.jsonPrimitive.content.toLong()
            table.update({
                set("zepp_template_id", c.jsonObject["id"]!!.jsonPrimitive.content)
                set("zepp_pushed_hash", specs[key]!!.first)
                set("zepp_pushed_at", now)
            }) { filter { eq("id", key) } }
        }
        // Sessions whose old template was deleted but whose new one failed would keep a dead id: clear it.
        val createdKeys = answer["created"]?.jsonArray.orEmpty().map { it.jsonObject["key"]!!.jsonPrimitive.content.toLong() }.toSet()
        val cleared = upcoming.filter { it.zeppTemplateId != null && it.id in specs && it.id !in createdKeys } + past
        for (row in cleared) {
            table.update({
                set("zepp_template_id", null as String?)
                set("zepp_pushed_hash", null as String?)
            }) { filter { eq("id", row.id) } }
        }
        val unmapped = answer["unmapped"]?.jsonArray.orEmpty().map { it.jsonObject["movement"]!!.jsonPrimitive.content }.distinct()
        val failed = answer["failed"]?.jsonArray?.size ?: 0
        buildString {
            append("Zepp: ${createdKeys.size} template(s) sent, ${answer["deleted"]?.jsonPrimitive?.content ?: "0"} removed")
            if (failed > 0) append(", $failed failed")
            if (unmapped.isNotEmpty()) append(". No Zepp exercise for: ${unmapped.joinToString()}")
        }
    } catch (e: Exception) {
        "Zepp push failed: ${e.message ?: "unknown error"}"
    } }

    suspend fun getSyncStatus(): ZeppSyncStateRow? =
        supabase.postgrest.from("zepp_sync_state")
            .select()
            .decodeSingleOrNull<ZeppSyncStateRow>()
}
