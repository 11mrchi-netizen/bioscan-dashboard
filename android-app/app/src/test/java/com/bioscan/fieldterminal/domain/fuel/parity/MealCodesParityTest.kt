package com.bioscan.fieldterminal.domain.fuel.parity

import com.bioscan.fieldterminal.domain.fuel.CodedDay
import com.bioscan.fieldterminal.domain.fuel.MealTimes
import com.bioscan.fieldterminal.domain.fuel.PlannedDay
import com.bioscan.fieldterminal.domain.fuel.WeekEnergy
import com.bioscan.fieldterminal.domain.fuel.codeMeals
import com.bioscan.fieldterminal.domain.fuel.planFuelDays
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.time.LocalTime
import java.time.ZoneId

// Parity with the web's coded meals (supabase/functions/_shared/fuel-core/mealCodes.ts). The days and energy of fixtures/day-input.json (written by
// DayFuelPlanParityTest) are planned and their meals coded under several settings (fixtures/meals-input.json); the meals, reasons included,
// are recorded (fixtures/meals-expected.json). After a deliberate change: FT_PARITY_WRITE=1 ./gradlew :app:testDebugUnitTest --tests '*MealCodesParityTest*'
class MealCodesParityTest {
    private val dir = File("../../supabase/functions/_shared/fuel-core/fixtures")
    private val json = Json { encodeDefaults = true; prettyPrint = false }
    private val zone = ZoneId.of("Asia/Taipei")

    private fun v(name: String, trainLow: Boolean, deficit: Boolean, tol: Double, b: String, l: String, s: String?, d: String) = buildJsonObject {
        put("name", name); put("trainLow", trainLow); put("deficit", deficit); put("tolerated", tol)
        put("breakfast", b); put("lunch", l); put("snack", s?.let { JsonPrimitive(it) } ?: JsonNull); put("dinner", d)
    }
    private val variants = listOf(
        v("default", false, false, 60.0, "09:00", "12:30", "16:00", "19:30"),
        v("train_low", true, false, 60.0, "09:00", "12:30", "16:00", "19:30"),
        v("train_low_deficit", true, true, 75.0, "09:00", "12:30", "16:00", "19:30"),
        v("early_no_snack", false, false, 90.0, "06:30", "11:00", null, "18:30"),
        v("very_early", true, false, 45.0, "05:00", "10:15", "14:45", "20:00"),
        v("low_tolerance", false, false, 32.0, "08:00", "12:00", "15:30", "19:00"),
    )

    @Test
    fun theWebCodesTheSameMeals() {
        val days = Json.parseToJsonElement(File(dir, "day-input.json").readText()).jsonArray.map { it.jsonObject }
        val inputFile = File(dir, "meals-input.json")
        val expectedFile = File(dir, "meals-expected.json")
        // The hand-made weeks in full under every setting; the sweep (many one-day weeks) under the default only.
        val expected = buildJsonObject {
            var sweepSeen = 0
            for (s in days) {
                val name = s["name"]!!.jsonPrimitive.content
                if (name.startsWith("sweep_") && sweepSeen++ % 8 != 0) continue
                val e = s["energy"]!!.jsonObject
                val energy = WeekEnergy(
                    e["dailyKcal"]!!.takeIf { it !is JsonNull }?.jsonPrimitive?.doubleOrNull, e["proteinG"]!!.takeIf { it !is JsonNull }?.jsonPrimitive?.doubleOrNull,
                    e["fromGoal"]!!.jsonPrimitive.booleanOrNull!!, e["deficit"]!!.jsonPrimitive.booleanOrNull!!, e["observedMaintenance"]!!.jsonPrimitive.booleanOrNull!!,
                )
                val weight = s["weightKg"]!!.jsonPrimitive.content.toDouble()
                val plannedDays = json.decodeFromJsonElement(ListSerializer(PlannedDay.serializer()), s["days"]!!)
                val plans = planFuelDays(plannedDays, energy, weight, s["fatFreeMassKg"]!!.takeIf { it !is JsonNull }?.jsonPrimitive?.doubleOrNull)
                val use = if (name.startsWith("sweep_") || name.startsWith("tie_")) variants.take(1) else variants
                put(name, buildJsonObject {
                    for (variant in use) {
                        val vo = variant
                        val times = MealTimes(
                            LocalTime.parse(vo["breakfast"]!!.jsonPrimitive.content), LocalTime.parse(vo["lunch"]!!.jsonPrimitive.content),
                            vo["snack"]!!.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.let { LocalTime.parse(it) }, LocalTime.parse(vo["dinner"]!!.jsonPrimitive.content),
                        )
                        val coded = plannedDays.indices.map { i ->
                            codeMeals(
                                plans[i], plannedDays[i], plannedDays.getOrNull(i + 1), weight, zone, times, vo["trainLow"]!!.jsonPrimitive.booleanOrNull!!,
                                vo["deficit"]!!.jsonPrimitive.booleanOrNull!!, vo["tolerated"]!!.jsonPrimitive.content.toDouble(),
                            )
                        }
                        put(vo["name"]!!.jsonPrimitive.content, json.encodeToJsonElement(ListSerializer(CodedDay.serializer()), coded))
                    }
                })
            }
        }
        val input = buildJsonObject { put("variants", JsonArray(variants)) }
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
