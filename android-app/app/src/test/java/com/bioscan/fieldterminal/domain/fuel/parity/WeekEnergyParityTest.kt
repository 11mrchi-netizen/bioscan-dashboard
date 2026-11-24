package com.bioscan.fieldterminal.domain.fuel.parity

import com.bioscan.fieldterminal.data.MaintenanceInputs
import com.bioscan.fieldterminal.data.NutritionGoals
import com.bioscan.fieldterminal.data.weekEnergyOf
import com.bioscan.fieldterminal.domain.DailyNutrition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.time.LocalDate

// Parity with the web's week energy (supabase/functions/_shared/fuel-core/weekEnergy.ts): the goal or maintenance that a week's days
// average to, with the observed-expenditure path (logged intake and the weight trend). Scenarios are JSON (fixtures/week-energy-input.json);
// the energy the phone derives from each is recorded (fixtures/week-energy-expected.json).
// After a deliberate change: FT_PARITY_WRITE=1 ./gradlew :app:testDebugUnitTest --tests '*WeekEnergyParityTest*'
class WeekEnergyParityTest {
    private val dir = File("../../supabase/functions/_shared/fuel-core/fixtures")
    private val json = Json { encodeDefaults = true; prettyPrint = false }
    private val asOf = LocalDate.of(2026, 11, 24)
    private var seed = 4242L
    private fun next(m: Int): Int { seed = (seed * 1103515245L + 12345L) and 0x7fffffffL; return (seed % m).toInt() }

    private fun prim(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is JsonElement -> v
        is Boolean -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is String -> JsonPrimitive(v)
        is List<*> -> JsonArray(v.map { prim(it) })
        else -> error("unsupported $v")
    }
    private fun obj(vararg p: Pair<String, Any?>) = buildJsonObject { p.forEach { (k, v) -> put(k, prim(v)) } }

    private fun nutrition(days: Int, mealsPerDay: (Int) -> Int, kcal: (Int) -> Double): List<JsonObject> = (0 until days).map { i ->
        val d = asOf.minusDays((days - 1 - i).toLong())
        val kc = kcal(i)
        obj("date" to d.toString(), "calories" to kc, "proteinG" to kc * 0.18 / 4, "fatG" to kc * 0.3 / 9, "carbsG" to kc * 0.52 / 4, "mealCount" to mealsPerDay(i))
    }
    private fun weights(days: Int, start: Double, perWeek: Double, every: Int = 1): List<List<Any>> =
        (0 until days step every).map { i -> listOf(asOf.minusDays((days - 1 - i).toLong()).toString(), start + perWeek * i / 7.0 + (next(5) - 2) * 0.1) }

    private fun scenarios(): List<JsonObject> {
        val male = obj("sex" to "male", "ageYears" to 38, "heightCm" to 178.0, "weightKg" to 80.0, "weeklyTrainingMin" to 300.0)
        val steady = nutrition(40, { 3 + next(2) }) { 2300.0 + (next(400) - 200) }
        return listOf(
            obj("name" to "equation_deficit", "goals" to obj("caloriesKcal" to 2200.0, "proteinG" to 160.0), "inputs" to male, "nutritionDays" to null, "weights" to emptyList<Any>()),
            obj("name" to "equation_no_goal", "goals" to obj(), "inputs" to male, "nutritionDays" to emptyList<Any>(), "weights" to emptyList<Any>()),
            obj("name" to "female_chosen_level", "goals" to obj("caloriesKcal" to 1900.0, "activityLevel" to "light", "proteinG" to 120.0),
                "inputs" to obj("sex" to "female", "ageYears" to 31, "heightCm" to 165.0, "weightKg" to 58.0, "weeklyTrainingMin" to 90.0), "nutritionDays" to null, "weights" to emptyList<Any>()),
            obj("name" to "no_sex_goal_only", "goals" to obj("caloriesKcal" to 2500.0), "inputs" to obj("ageYears" to 40, "heightCm" to 180.0, "weightKg" to 75.0), "nutritionDays" to null, "weights" to emptyList<Any>()),
            obj("name" to "nothing_known", "goals" to obj(), "inputs" to obj(), "nutritionDays" to null, "weights" to emptyList<Any>()),
            obj("name" to "within_the_band", "goals" to obj("caloriesKcal" to 2850.0, "proteinG" to 150.0), "inputs" to male, "nutritionDays" to null, "weights" to emptyList<Any>()),
            obj("name" to "observed_losing", "goals" to obj("caloriesKcal" to 2000.0, "proteinG" to 150.0), "inputs" to male, "nutritionDays" to steady, "weights" to weights(40, 82.0, -0.4)),
            obj("name" to "observed_gaining_goal_above", "goals" to obj("caloriesKcal" to 3000.0), "inputs" to male, "nutritionDays" to steady, "weights" to weights(40, 78.0, 0.3, every = 2)),
            obj("name" to "observed_no_goal", "goals" to obj(), "inputs" to male, "nutritionDays" to steady, "weights" to weights(40, 80.0, -0.1)),
            obj("name" to "observed_partial_days", "goals" to obj("caloriesKcal" to 2300.0),
                "inputs" to male, "nutritionDays" to nutrition(40, { if (it % 3 == 0) 1 else 3 }) { if (it % 5 == 0) 600.0 else 2400.0 + (next(300) - 150) }, "weights" to weights(40, 80.0, -0.2)),
            obj("name" to "intake_not_enough_days", "goals" to obj("caloriesKcal" to 2400.0), "inputs" to male, "nutritionDays" to nutrition(9, { 3 }) { 2300.0 }, "weights" to weights(40, 80.0, -0.2)),
            obj("name" to "weights_not_enough", "goals" to obj("caloriesKcal" to 2400.0), "inputs" to male, "nutritionDays" to steady, "weights" to weights(8, 80.0, -0.2)),
            obj("name" to "weights_shift_but_met", "goals" to obj("caloriesKcal" to 2600.0), "inputs" to male, "nutritionDays" to steady, "weights" to weights(60, 90.0, -0.9, every = 3)),
            // 0.46 of the median is a partial day: ten complete days of the last fourteen, no more.
            obj("name" to "observed_low_days", "goals" to obj("caloriesKcal" to 2300.0), "inputs" to male,
                "nutritionDays" to nutrition(40, { 3 }) { i -> if (i >= 36) 1100.0 else 2400.0 }, "weights" to weights(40, 80.0, -0.2)),
            obj("name" to "weights_span_14", "goals" to obj("caloriesKcal" to 2300.0), "inputs" to male, "nutritionDays" to steady, "weights" to spanned(14)),
            obj("name" to "weights_span_13", "goals" to obj("caloriesKcal" to 2300.0), "inputs" to male, "nutritionDays" to steady, "weights" to spanned(13)),
        )
    }

    private fun spanned(span: Int): List<List<Any>> = (0 until 10).map { i ->
        val off = span - Math.round(i * span / 9.0).toInt()
        listOf(asOf.minusDays(off.toLong()).toString(), 80.0 - 0.05 * i)
    }

    private fun JsonObject.d(k: String) = this[k]?.takeIf { it !is JsonNull }?.jsonPrimitive?.doubleOrNull
    private fun JsonObject.s(k: String) = this[k]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content

    private fun run(s: JsonObject): JsonObject {
        val g = s["goals"]!!.jsonObject
        val i = s["inputs"]!!.jsonObject
        val goals = NutritionGoals(caloriesKcal = g.d("caloriesKcal"), proteinG = g.d("proteinG"), activityLevel = g.s("activityLevel"))
        val inputs = MaintenanceInputs(i.s("sex"), i["ageYears"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.intOrNull, i.d("heightCm"), i.d("weightKg"), null, i.d("weeklyTrainingMin"))
        val days = s["nutritionDays"]?.takeIf { it !is JsonNull }?.jsonArray?.map { it.jsonObject }?.map {
            DailyNutrition(it.s("date")!!, it.d("calories")!!, it.d("proteinG")!!, it.d("fatG")!!, it.d("carbsG")!!, it["mealCount"]!!.jsonPrimitive.content.toInt())
        }
        val weights = s["weights"]!!.jsonArray.map { LocalDate.parse(it.jsonArray[0].jsonPrimitive.content) to it.jsonArray[1].jsonPrimitive.content.toDouble() }
        val e = weekEnergyOf(goals, inputs, days, weights, asOf)
        return obj("dailyKcal" to e.dailyKcal, "proteinG" to e.proteinG, "fromGoal" to e.fromGoal, "deficit" to e.deficit, "observedMaintenance" to e.observedMaintenance)
    }

    @Test
    fun theWebFindsTheSameWeekEnergy() {
        val inputFile = File(dir, "week-energy-input.json")
        val expectedFile = File(dir, "week-energy-expected.json")
        val scenarios = scenarios()
        val input = obj("asOf" to asOf.toString(), "scenarios" to scenarios)
        val expected = buildJsonObject { scenarios.forEach { put(it.s("name")!!, run(it)) } }
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
