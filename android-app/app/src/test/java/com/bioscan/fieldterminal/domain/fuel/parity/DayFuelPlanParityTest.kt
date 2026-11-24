package com.bioscan.fieldterminal.domain.fuel.parity

import com.bioscan.fieldterminal.domain.events.EventKind
import com.bioscan.fieldterminal.domain.events.EventPriority
import com.bioscan.fieldterminal.domain.fuel.DayFuelPlan
import com.bioscan.fieldterminal.domain.fuel.Intensity
import com.bioscan.fieldterminal.domain.fuel.PlanSource
import com.bioscan.fieldterminal.domain.fuel.PlannedDay
import com.bioscan.fieldterminal.domain.fuel.PlannedItem
import com.bioscan.fieldterminal.domain.fuel.WeekEnergy
import com.bioscan.fieldterminal.domain.fuel.fatFreeMass
import com.bioscan.fieldterminal.domain.fuel.planFuelDays
import com.bioscan.fieldterminal.domain.fuel.planInWeekBlocks
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

// Parity with the web's day fuel engine (supabase/functions/_shared/fuel-core/day.ts). Synthetic training days are written as the
// phone serialises a PlannedDay (fixtures/day-input.json); the day plans they give, reasons and notes included, are recorded
// (fixtures/day-expected.json). The TypeScript test reads the input and must reproduce the expected plans exactly.
// After a deliberate change: FT_PARITY_WRITE=1 ./gradlew :app:testDebugUnitTest --tests '*DayFuelPlanParityTest*'
class DayFuelPlanParityTest {
    private val dir = File("../../supabase/functions/_shared/fuel-core/fixtures")
    private val zone = ZoneId.of("Asia/Taipei")
    private val monday = LocalDate.of(2026, 11, 23)
    private val json = Json { encodeDefaults = true; prettyPrint = false }

    private fun item(
        date: LocalDate, hour: Int?, minutes: Double, intensity: Intensity, title: String, kcal: Double?,
        priority: EventPriority? = null, type: String = "run",
    ) = PlannedItem(
        source = if (priority != null) PlanSource.EVENT else PlanSource.PLANNED_SESSION, sourceId = "t:$title:$date", title = title, type = type,
        start = hour?.let { date.atTime(it, 30).atZone(zone) }, durationMin = minutes, intensity = intensity, intensityReason = "test", kcal = kcal,
        eventKind = if (priority != null) EventKind.RACE else null, eventPriority = priority,
    )

    private fun day(i: Int, vararg items: PlannedItem) = PlannedDay(monday.plusDays(i.toLong()), items.toList())

    private class Scenario(
        val name: String, val days: List<PlannedDay>, val energy: WeekEnergy, val weightKg: Double, val ffmKg: Double?,
        val modes: List<Int?>, // null: planFuelDays; n: planInWeekBlocks with today = day n
    )

    private fun energyJson(e: WeekEnergy): JsonObject = buildJsonObject {
        put("dailyKcal", e.dailyKcal?.let { JsonPrimitive(it) } ?: JsonNull)
        put("proteinG", e.proteinG?.let { JsonPrimitive(it) } ?: JsonNull)
        put("fromGoal", e.fromGoal)
        put("deficit", e.deficit)
        put("observedMaintenance", e.observedMaintenance)
    }

    private fun scenarios(): List<Scenario> {
        val out = mutableListOf<Scenario>()
        val d = { i: Int -> monday.plusDays(i.toLong()) }
        // Fourteen days that reach every band, the 72-hour step (morning and afternoon), loading for a B race, a C race that does not load.
        val mixed = listOf(
            day(0),
            day(1, item(d(1), 7, 30.0, Intensity.EASY, "Easy jog", 270.0)),
            day(2, item(d(2), 18, 60.0, Intensity.MODERATE, "Tempo-ish", 620.0)),
            day(3, item(d(3), 7, 100.0, Intensity.HARD, "Intervals", 1050.0)),
            day(4),
            day(5, item(d(5), 6, 150.0, Intensity.LONG, "Long run", 1500.0)),
            day(6, item(d(6), 5, 270.0, Intensity.LONG, "Very long", 2700.0)),
            day(7),
            day(8, item(d(8), 16, 90.0, Intensity.HARD, "Hills", 950.0)),
            day(9, item(d(9), 17, 45.0, Intensity.MODERATE, "Shakeout", 450.0)),
            day(10, item(d(10), 9, 30.0, Intensity.EASY, "Strides", 280.0), item(d(10), 18, 40.0, Intensity.EASY, "Spin", 300.0, type = "ride")),
            day(11, item(d(11), 8, 150.0, Intensity.RACE, "B race 25 km", 1750.0, EventPriority.B)),
            day(12, item(d(12), 8, 100.0, Intensity.RACE, "C race 15 km", 1100.0, EventPriority.C)),
            day(13),
        )
        val goal = WeekEnergy(2600.0, 150.0, true)
        out += Scenario("mixed_week", mixed, goal, 78.4, 62.0, listOf(null, 0, 3))
        out += Scenario("no_goal", mixed, WeekEnergy(null, null, false), 70.0, null, listOf(null, 2))
        out += Scenario("deficit_goal", mixed, WeekEnergy(2100.0, 160.0, true, deficit = true), 82.0, 60.0, listOf(null, 1))
        out += Scenario("maintenance", mixed, WeekEnergy(2750.0, null, false, observedMaintenance = true), 66.3, fatFreeMass(66.3, null, 14.0), listOf(null))
        // A day the ceiling cannot hold: a 6-hour mountain day and an A race of three hours.
        val big = listOf(
            day(0, item(d(0), 8, 30.0, Intensity.EASY, "Jog", 280.0)),
            day(1, item(d(1), 6, 390.0, Intensity.LONG, "Mountain day", 4300.0)),
            day(2, item(d(2), 9, 45.0, Intensity.EASY, "Recovery", 400.0)),
            day(3),
            day(4, item(d(4), 7, 60.0, Intensity.MODERATE, "Tempo", 700.0)),
            day(5, item(d(5), 8, 200.0, Intensity.RACE, "A race 50 km", 2900.0, EventPriority.A)),
            day(6),
            day(7, item(d(7), 8, 420.0, Intensity.RACE, "A ultra", 4800.0, EventPriority.A)),
            day(8),
        )
        out += Scenario("ceiling", big, WeekEnergy(3300.0, 140.0, true), 74.0, 61.0, listOf(null, 0, 2))
        out += Scenario("ceiling_low_goal", big, WeekEnergy(2000.0, 170.0, true, deficit = true), 88.0, 70.0, listOf(null, 1))
        // Rounding: many weights and goals, so ties in the one-decimal and whole-number text show up.
        val sweep = mutableListOf<Scenario>()
        var w = 55.0
        while (w <= 100.0) {
            for (kcal in listOf(1700.0, 1950.0, 2150.0, 2400.0, 2650.0, 3000.0)) {
                val three = listOf(
                    day(0, item(d(0), 18, 100.0, Intensity.HARD, "Hard", 900.0)),
                    day(1, item(d(1), 7, 75.0, Intensity.MODERATE, "Morning", 700.0)),
                    day(2),
                )
                sweep += Scenario("sweep_${w}_${kcal.toInt()}", three, WeekEnergy(kcal, 1.7 * w, true), w, w * 0.8125, listOf(null))
            }
            w += 2.5
        }
        out += sweep
        // Meal splits at their edges: protein just covering the three main meals' floor (0.3 g/kg), just under it; key sessions of exactly an hour.
        val hour = listOf(6, 10, 13, 16, 18, 21).mapIndexed { i, h -> day(i, item(d(i), h, 60.0, Intensity.HARD, "Hour $h", 650.0)) }
        out += Scenario("protein_floor", hour, WeekEnergy(2600.0, 63.0, true), 70.0, null, listOf(null))
        out += Scenario("protein_floor_under", hour, WeekEnergy(2600.0, 62.9, true), 70.0, null, listOf(null))
        out += Scenario("protein_floor_over", hour, WeekEnergy(2600.0, 140.0, true), 70.0, null, listOf(null))
        // The one-decimal tie (5.05 g/kg: Java rounds it up, the binary value says down), found by search.
        for ((kcal, p, w) in listOf(Triple(2014.0, 120.0, 56.0), Triple(2039.0, 125.0, 56.0), Triple(2064.0, 130.0, 56.0), Triple(2115.0, 120.0, 60.0))) {
            out += Scenario("tie_${kcal.toInt()}", listOf(day(0, item(d(0), 18, 60.0, Intensity.MODERATE, "Steady", 0.0))), WeekEnergy(kcal, p, true), w, null, listOf(null))
        }
        // Noon is not morning; a session with no time is not either.
        val noon = listOf(
            day(0), day(1, item(d(1), 12, 100.0, Intensity.HARD, "Noon hard", 1000.0)), day(2),
            day(3, item(d(3), 11, 120.0, Intensity.LONG, "Late morning long", 1200.0)), day(4),
            day(5, item(d(5), null, 100.0, Intensity.HARD, "No time", 1000.0)), day(6),
        )
        out += Scenario("noon", noon, WeekEnergy(2800.0, 140.0, true), 75.0, 60.0, listOf(null, 4))
        return out
    }

    private fun plansJson(plans: List<DayFuelPlan>): JsonElement = json.encodeToJsonElement(ListSerializer(DayFuelPlan.serializer()), plans)

    @Test
    fun theWebPlansTheSameDays() {
        val inputFile = File(dir, "day-input.json")
        val expectedFile = File(dir, "day-expected.json")
        val input = buildJsonArray {
            for (s in scenarios()) add(buildJsonObject {
                put("name", s.name)
                put("weightKg", s.weightKg)
                put("fatFreeMassKg", s.ffmKg?.let { JsonPrimitive(it) } ?: JsonNull)
                put("energy", energyJson(s.energy))
                put("modes", JsonArray(s.modes.map { it?.let { n -> JsonPrimitive(n) } ?: JsonNull }))
                put("days", json.encodeToJsonElement(ListSerializer(PlannedDay.serializer()), s.days))
            })
        }
        val expected = buildJsonObject {
            for (s in scenarios()) put(s.name, JsonArray(s.modes.map { mode ->
                plansJson(if (mode == null) planFuelDays(s.days, s.energy, s.weightKg, s.ffmKg) else planInWeekBlocks(s.days, mode, s.energy, s.weightKg, s.ffmKg))
            }))
        }
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
