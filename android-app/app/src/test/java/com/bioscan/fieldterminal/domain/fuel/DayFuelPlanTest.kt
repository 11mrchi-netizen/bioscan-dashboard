package com.bioscan.fieldterminal.domain.fuel

import com.bioscan.fieldterminal.domain.events.EventKind
import com.bioscan.fieldterminal.domain.events.EventPriority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class DayFuelPlanTest {
    private val zone = ZoneId.of("Europe/Rome")
    private val monday = LocalDate.of(2026, 11, 2)
    private val kg = 70.0
    private val goal = WeekEnergy(dailyKcal = 2800.0, proteinG = 130.0, fromGoal = true)
    private fun at(d: LocalDate, h: Int, m: Int = 0) = ZonedDateTime.of(d.atTime(h, m), zone)

    private fun item(d: LocalDate, h: Int, min: Double, i: Intensity, kcal: Double, title: String = i.label, race: EventPriority? = null) =
        PlannedItem(
            if (race != null) PlanSource.EVENT else PlanSource.PLANNED_SESSION, "x$title$h", title, "run", at(d, h), min, i, "test", kcal,
            eventId = race?.let { 1L }, eventKind = race?.let { EventKind.RACE }, eventPriority = race,
        )

    // A synthetic week: rest, intervals, easy, rest, tempo, rest, long run on Sunday morning.
    private val week = listOf(
        PlannedDay(monday, emptyList()),
        PlannedDay(monday.plusDays(1), listOf(item(monday.plusDays(1), 18, 60.0, Intensity.HARD, 700.0, "Intervals"))),
        PlannedDay(monday.plusDays(2), listOf(item(monday.plusDays(2), 7, 40.0, Intensity.EASY, 400.0, "Easy"))),
        PlannedDay(monday.plusDays(3), emptyList()),
        PlannedDay(monday.plusDays(4), listOf(item(monday.plusDays(4), 18, 50.0, Intensity.HARD, 600.0, "Tempo"))),
        PlannedDay(monday.plusDays(5), emptyList()),
        PlannedDay(monday.plusDays(6), listOf(item(monday.plusDays(6), 8, 120.0, Intensity.LONG, 1300.0, "Long run"))),
    )

    @Test
    fun aRestDayIsLowAndALongDayHigh() {
        val plans = planFuelDays(week, goal, kg)
        assertEquals(DayClass.LOW, plans[0].dayClass)
        assertEquals(BAND_LIGHT, plans[0].band)
        assertEquals(BAND_HIGH, plans[6].band)
        assertTrue(plans[6].carbG > plans[0].carbG * 1.8)
    }

    @Test
    fun theGoalStaysTheWeeklyAverage() {
        val roomy = goal.copy(dailyKcal = 3200.0)
        val plans = planFuelDays(week, roomy, kg)
        assertEquals(3200.0 * 7, plans.sumOf { it.kcal!! }, 1.0)
        assertTrue(plans[6].kcal!! > plans[0].kcal!!)
        // At 2,800 the rest day before the long run gives up part of its step-up to keep fat at 20 %; the week still holds.
        val tight = planFuelDays(week, goal, kg)
        assertTrue(tight[5].notes.any { "kept at" in it })
        assertEquals(2800.0 * 7, tight.sumOf { it.kcal!! }, 1.0)
    }

    @Test
    fun proteinStaysAtTheGoalAndFatNeverDropsUnderTwentyPercent() {
        planFuelDays(week, goal, kg).forEach { p ->
            assertEquals(130.0, p.proteinG, 0.0)
            assertTrue(p.fatG!! * 9 >= 0.2 * p.kcal!! - 0.5)
            assertEquals(p.kcal!!, p.proteinG * 4 + p.carbG * 4 + p.fatG!! * 9, 0.5)
        }
    }

    @Test
    fun theDayBeforeAHardMorningMovesUpAStep() {
        val plans = planFuelDays(week, goal, kg)
        // Tuesday: an hour of intervals (moderate band), and Wednesday's 07:00 session is easy: no step.
        assertEquals(BAND_MODERATE, plans[1].band)
        // Saturday is a rest day before the Sunday morning long run: a step up from light.
        assertEquals(BAND_MODERATE, plans[5].band)
        assertTrue(plans[5].reasons.any { "Tomorrow morning" in it })
    }

    @Test
    fun twoDaysBeforeAnARaceOverNinetyMinutesLoad() {
        val race = PlannedDay(monday.plusDays(6), listOf(item(monday.plusDays(6), 9, 210.0, Intensity.RACE, 2500.0, "Synthetic Marathon", EventPriority.A)))
        val days = week.dropLast(1) + race
        val plans = planFuelDays(days, goal, kg)
        assertTrue(plans[4].loading)
        assertTrue(plans[5].loading)
        assertFalse(plans[3].loading)
        assertEquals(DayClass.LOADING, plans[5].dayClass)
        assertEquals(11.0 * kg, plans[5].carbG, 0.5)
        assertTrue(plans[5].notes.any { "fibre" in it })
    }

    @Test
    fun aCRaceOrAShortRaceDoesNotLoad() {
        val c = PlannedDay(monday.plusDays(6), listOf(item(monday.plusDays(6), 9, 200.0, Intensity.RACE, 2000.0, "Training race", EventPriority.C)))
        assertFalse(planFuelDays(week.dropLast(1) + c, goal, kg)[5].loading)
        val short = PlannedDay(monday.plusDays(6), listOf(item(monday.plusDays(6), 9, 45.0, Intensity.RACE, 600.0, "10K", EventPriority.A)))
        assertFalse(planFuelDays(week.dropLast(1) + short, goal, kg)[5].loading)
    }

    @Test
    fun aTightGoalCapsCarbohydrateThenRaisesEnergyRatherThanUnderfuel() {
        val tight = WeekEnergy(dailyKcal = 1900.0, proteinG = 130.0, fromGoal = true, deficit = true)
        val plans = planFuelDays(week, tight, kg)
        // Every day keeps at least the low end of its own training's band; where even that does not fit, energy rises.
        plans.zip(week).forEach { (p, d) -> assertTrue(p.carbGPerKg >= bandForTraining(d).lowGPerKg - 1e-6) }
        assertTrue(plans.any { p -> p.notes.any { "Energy raised" in it } })
        assertTrue(plans[1].notes.any { "Deficit goal" in it })
    }

    @Test
    fun lowEnergyAvailabilityIsNamed() {
        val tight = WeekEnergy(dailyKcal = 1900.0, proteinG = 130.0, fromGoal = true, deficit = true)
        val plan = planFuelDays(week, tight, kg, fatFreeMassKg = 60.0)[6]
        assertTrue(plan.energyAvailability!! < 30)
        assertTrue(plan.notes.any { "REDs" in it })
    }

    @Test
    fun withoutEnergyOnlyCarbohydrateAndProteinArePlanned() {
        val plan = planFuelDays(week, WeekEnergy(null, null, fromGoal = false), kg)[0]
        assertNull(plan.kcal)
        assertNull(plan.fatG)
        assertEquals(1.8 * kg, plan.proteinG, 1e-9)
    }

    @Test
    fun fatFreeMassFromLeanOrBodyFat() {
        assertEquals(60.0, fatFreeMass(75.0, 60.0, null)!!, 0.0)
        assertEquals(63.75, fatFreeMass(75.0, null, 15.0)!!, 1e-9)
        assertNull(fatFreeMass(75.0, null, null))
    }
}
