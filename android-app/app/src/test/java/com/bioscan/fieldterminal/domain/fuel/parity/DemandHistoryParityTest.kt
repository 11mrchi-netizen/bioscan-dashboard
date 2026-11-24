package com.bioscan.fieldterminal.domain.fuel.parity

import com.bioscan.fieldterminal.data.demandHistory
import com.bioscan.fieldterminal.domain.aggregateMealsByDay
import com.bioscan.fieldterminal.data.model.MealRow
import com.bioscan.fieldterminal.domain.fuel.RecordedSessionInput
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.time.OffsetDateTime
import java.time.ZoneId

// Parity with the web's demand history and day totals (fuel-core/plannedDay.ts demandHistory, weekEnergy.ts aggregateMealsByDay): what the
// person's recorded sessions say about kcal and heart-rate load by type, and the meals summed by day (fixtures/history-*.json).
// After a deliberate change: FT_PARITY_WRITE=1 ./gradlew :app:testDebugUnitTest --tests '*DemandHistoryParityTest*'
class DemandHistoryParityTest {
    private val dir = File("../../supabase/functions/_shared/fuel-core/fixtures")
    private val json = Json { encodeDefaults = true; prettyPrint = false }
    private val zone = ZoneId.of("Asia/Taipei")
    private var seed = 99L
    private fun next(m: Int): Int { seed = (seed * 1103515245L + 12345L) and 0x7fffffffL; return (seed % m).toInt() }
    private fun prim(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is JsonElement -> v
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is String -> JsonPrimitive(v)
        is List<*> -> JsonArray(v.map { prim(it) })
        is Map<*, *> -> buildJsonObject { v.forEach { (k, x) -> put(k as String, prim(x)) } }
        else -> error("unsupported $v")
    }

    private fun recordings(): List<Map<String, Any?>> = (0 until 90).map { i ->
        val type = listOf("run", "run", "run", "ride", "strength", "walk", "swim", "hike")[next(8)]
        val duration = if (next(9) == 0) 6.0 else 25.0 + next(120)
        val date = java.time.LocalDate.of(2026, 11, 19).minusDays(next(190).toLong())
        mapOf(
            "id" to 8000L + i, "type" to type, "start" to "%s,T%02d:%02d:00+08:00".format(date, 5 + next(14), next(60)).replace(",", ""), "durationMin" to duration,
            "caloriesActive" to if (next(4) == 0) null else if (next(11) == 0) 0.0 else duration * (6.0 + next(70) / 10.0),
            "avgHr" to if (next(4) == 0) null else 110.0 + next(75), "rpe" to null,
            "distanceKm" to if (type == "run" && next(5) != 0) 4.0 + next(220) / 10.0 else null, "ascentM" to if (next(3) == 0) null else next(900).toDouble(),
        )
    } + listOf(
        // The latest recording and, 120 days before it to the minute, one that is not after the cut and one a minute later that is.
        mapOf("id" to 8900L, "type" to "run", "start" to "2026-11-20T10:00:00+08:00", "durationMin" to 50.0, "caloriesActive" to null, "avgHr" to null, "rpe" to null, "distanceKm" to 8.0, "ascentM" to 100.0),
        mapOf("id" to 8901L, "type" to "run", "start" to "2026-07-23T10:00:00+08:00", "durationMin" to 40.0, "caloriesActive" to null, "avgHr" to null, "rpe" to null, "distanceKm" to 6.5, "ascentM" to 10.0),
        mapOf("id" to 8902L, "type" to "run", "start" to "2026-07-23T10:01:00+08:00", "durationMin" to 41.0, "caloriesActive" to null, "avgHr" to null, "rpe" to null, "distanceKm" to 6.6, "ascentM" to 11.0),
    )

    private fun toInput(m: Map<String, Any?>) = RecordedSessionInput(
        m["id"] as Long, m["type"] as String, OffsetDateTime.parse(m["start"] as String).atZoneSameInstant(zone), m["durationMin"] as Double,
        m["caloriesActive"] as Double?, m["avgHr"] as Double?, null, m["distanceKm"] as Double?, m["ascentM"] as Double?,
    )

    @Test
    fun theWebSumsTheSameHistory() {
        val recs = recordings()
        val anchors = listOf(Triple(50.0, 190.0, 70.0), Triple(null, null, null), Triple(60.0, 170.0, null), Triple(60.0, 60.0, 70.0))
        val meals = (0 until 60).map { i ->
            mapOf("logged_at" to "2026-11-%02dT%02d:%02d:00+00:00".format(1 + i % 24, 7 + next(13), next(60)),
                "calories" to if (next(6) == 0) null else 200.0 + next(700), "protein_g" to if (next(5) == 0) null else next(60).toDouble(),
                "fat_g" to next(40).toDouble(), "carbs_g" to if (next(7) == 0) null else next(120).toDouble())
        }
        val input = prim(mapOf("recordings" to recs, "anchors" to anchors.map { listOf(it.first, it.second, it.third) }, "meals" to meals))
        val expected = buildJsonObject {
            put("histories", JsonArray(anchors.map { (rest, max, kg) ->
                val h = demandHistory(recs.map { toInput(it) }, rest, max, kg)
                prim(mapOf(
                    "kcalPerMinByType" to h.kcalPerMinByType, "trimpPerMinByType" to h.trimpPerMinByType, "restingHr" to h.restingHr, "maxHr" to h.maxHr, "weightKg" to h.weightKg,
                    "recentRuns" to h.recentRuns.map { mapOf("distanceKm" to it.distanceKm, "ascentM" to it.ascentM, "durationMin" to it.durationMin) },
                ))
            }))
            put("days", JsonArray(aggregateMealsByDay(meals.map { MealRow(loggedAt = it["logged_at"] as String, calories = it["calories"] as Double?, proteinG = it["protein_g"] as Double?, fatG = it["fat_g"] as Double?, carbsG = it["carbs_g"] as Double?) })
                .map { prim(mapOf("date" to it.date, "calories" to it.calories, "proteinG" to it.proteinG, "fatG" to it.fatG, "carbsG" to it.carbsG, "mealCount" to it.mealCount)) }))
        }
        val inputFile = File(dir, "history-input.json")
        val expectedFile = File(dir, "history-expected.json")
        if (System.getenv("FT_PARITY_WRITE") != null || !expectedFile.exists()) {
            dir.mkdirs()
            inputFile.writeText(json.encodeToString(JsonElement.serializer(), input) + "\n")
            expectedFile.writeText(json.encodeToString(JsonElement.serializer(), expected) + "\n")
            return
        }
        assertEquals(Json.parseToJsonElement(expectedFile.readText()), expected)
        assertEquals(Json.parseToJsonElement(inputFile.readText()), input)
    }
}
