package com.bioscan.fieldterminal.domain.fuel

import com.bioscan.fieldterminal.domain.events.AidStation
import com.bioscan.fieldterminal.domain.trail.CoursePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class FuelSnapshotTest {
    private val base = DaySnapshot(
        date = "2026-11-04", dayClass = "MEDIUM", carbG = 420, proteinG = 130, fatG = 80, kcal = 2900,
        meals = listOf(MealSnapshot("BREAKFAST", "07:30", "MEDIUM", 90), MealSnapshot("DINNER", "19:30", "MEDIUM", 110)),
        sessions = listOf(SessionSnapshot("Intervals", "18:00", 60, "HARD")),
    )

    @Test
    fun anUnchangedPlanHasNothingToSay() {
        assertEquals(emptyList<String>(), describeChanges(base, base))
    }

    @Test
    fun aMovedSessionAndTheCodesItChangesAreNamed() {
        val moved = base.copy(
            sessions = listOf(SessionSnapshot("Intervals", "07:00", 60, "HARD")),
            meals = listOf(MealSnapshot("BREAKFAST", "04:30", "HIGH", 150), MealSnapshot("DINNER", "19:30", "HIGH", 120)),
        )
        val lines = describeChanges(base, moved)
        assertTrue(lines.any { "Intervals moved to 07:00" in it })
        assertTrue(lines.any { "Breakfast is now HIGH" in it })
    }

    @Test
    fun addedAndDroppedSessionsAndTheDayClass() {
        val rest = base.copy(sessions = emptyList(), dayClass = "LOW", carbG = 280)
        val lines = describeChanges(base, rest)
        assertTrue(lines.any { it == "Dropped: Intervals." })
        assertTrue(lines.any { "now LOW" in it })
        assertTrue(lines.any { "down to 280 g" in it })
        assertTrue(describeChanges(rest, base).any { it.startsWith("Added: Intervals") })
    }

    @Test
    fun anEventPlanSaysWhenTheForecastArrivesAndTheHeatChanges() {
        val old = EventSnapshot(420, 60, 450, 500, 22, "TYPICAL", 400, 3100)
        val new = old.copy(weatherSource = "FORECAST", maxTempC = 27, fluidMlPerH = 520, sodiumMgPerL = 700)
        val lines = describeChanges(old, new)
        assertTrue(lines.any { "forecast now covers" in it })
        assertTrue(lines.any { "27 °C" in it })
        assertTrue(lines.any { "up to 520 ml" in it })
        assertTrue(lines.any { "700 mg" in it })
    }

    @Test
    fun aPlanReadsAsTextForACrew() {
        val zone = ZoneId.of("Europe/Rome")
        val start = ZonedDateTime.of(LocalDate.of(2026, 9, 13).atTime(8, 0), zone)
        val course = (0..300).map { CoursePoint(it * 100.0, 500.0 + it) }
        val plan = planRaceFuel(
            RaceFuelInput(
                "Synthetic 30K", "run", Intensity.RACE, start, 180.0, 170.0, 190.0, "your target time",
                course = course, distanceKm = 30.0, aidStations = listOf(AidStation("Half", 15.0, water = true, food = true)),
                weather = WeatherSeries(WeatherSource.FORECAST, (0..5).map { HourWeather(start.plusHours(it.toLong() - 1), 20.0, 60.0) }),
                sweat = sweatProfile(emptyList(), 70.0), weightKg = 70.0,
            ),
        )
        val text = raceFuelPlanText(plan, "Sun 13 Sep 08:00")
        assertTrue(text.startsWith("SYNTHETIC 30K"))
        listOf("BEFORE", "DURING", "CARRY", "AFTER", "Assumptions", "Start → Half").forEach { assertTrue(it, text.contains(it)) }
        val snap = snapshotOf(plan)
        assertEquals(180, snap.durationMin)
        assertEquals(plan.steps.size, snap.steps.size)
    }
}
