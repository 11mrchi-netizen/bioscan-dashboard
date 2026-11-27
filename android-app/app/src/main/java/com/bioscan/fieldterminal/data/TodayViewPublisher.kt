package com.bioscan.fieldterminal.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.time.LocalDate

// The web desk opens any day's rings with the phone's depth (saved day view, Nov 2026): each time TODAY has scored the morning,
// the whole ScoresView goes to today_views (supabase/migrations/20261127090000_today_views.sql), written by the phone's own
// serializer, and the web reads it and never recomputes a score. The last 120 days are kept.

const val TODAY_VIEW_KEEP_DAYS = 120L

/** A view bigger than this loses its chart series (the numbers, scorecards and strips stay). */
const val TODAY_VIEW_MAX_BYTES = 150_000

private val viewJson = Json { ignoreUnknownKeys = true; allowSpecialFloatingPointValues = true; encodeDefaults = true }

/** Postgres JSON has no NaN or Infinity: they become null. */
internal fun finiteOnly(e: JsonElement): JsonElement = when (e) {
    is JsonObject -> JsonObject(e.mapValues { finiteOnly(it.value) })
    is JsonArray -> JsonArray(e.map { finiteOnly(it) })
    is JsonPrimitive -> if (!e.isString && e.content in setOf("NaN", "Infinity", "-Infinity")) JsonNull else e
    else -> e
}

/** The day's scored view as the web reads it, and whether the chart series had to be dropped to keep it small. */
fun todayViewSnapshot(view: ScoresView): Pair<JsonObject, Boolean> {
    val full = finiteOnly(viewJson.encodeToJsonElement(ScoresView.serializer(), view)) as JsonObject
    if (full.toString().length <= TODAY_VIEW_MAX_BYTES) return full to false
    return JsonObject(full + ("series" to JsonObject(emptyMap()))) to true
}

object TodayViewPublisher {
    const val MODEL_VERSION = 1
    @Volatile private var lastSent: Pair<LocalDate, Int>? = null
    @Volatile private var prunedOn: LocalDate? = null

    /** Writes the day's view when it changed since the last write from this process, and prunes rows older than the keep window once a day. */
    suspend fun publish(supabase: SupabaseClient, view: ScoresView) {
        val (snapshot, _) = todayViewSnapshot(view)
        val stamp = view.day to snapshot.hashCode()
        if (lastSent != stamp) {
            supabase.postgrest.from("today_views").upsert(
                buildJsonObject {
                    put("day", view.day.toString()); put("model_version", MODEL_VERSION); put("view", snapshot)
                    put("updated_at", Instant.now().toString())
                },
            ) { onConflict = "user_id,day" }
            lastSent = stamp
        }
        if (prunedOn != view.day) {
            supabase.postgrest.from("today_views").delete { filter { lt("day", view.day.minusDays(TODAY_VIEW_KEEP_DAYS).toString()) } }
            prunedOn = view.day
        }
    }
}
