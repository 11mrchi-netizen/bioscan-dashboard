package com.bioscan.fieldterminal.domain.fuel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

class MealCodesTest {
    private val zone = ZoneId.of("Europe/Rome")
    private val day = LocalDate.of(2026, 11, 4)
    private val kg = 70.0
    private fun at(d: LocalDate, h: Int, m: Int = 0) = ZonedDateTime.of(d.atTime(h, m), zone)
    private fun session(h: Int, min: Double, i: Intensity, d: LocalDate = day) =
        PlannedItem(PlanSource.PLANNED_SESSION, "s$h", i.label.lowercase(), "run", at(d, h), min, i, "test", 600.0)
    private fun plan(pd: PlannedDay, next: PlannedDay? = null) =
        planFuelDay(pd, next, listOf(pd), WeekEnergy(2800.0, 130.0, true), kg)

    @Test
    fun mealsBeforeAndAfterAHardSessionAreHigh() {
        val pd = PlannedDay(day, listOf(session(17, 60.0, Intensity.HARD)))
        val coded = codeMeals(plan(pd), pd, null, kg, zone)
        val lunch = coded.meals.first { it.kind == MealSlotKind.LUNCH }
        val snack = coded.meals.first { it.kind == MealSlotKind.SNACK }
        val dinner = coded.meals.first { it.kind == MealSlotKind.DINNER }
        assertEquals(CarbCode.MEDIUM, lunch.code) // 4.5 h before
        assertEquals(CarbCode.HIGH, snack.code) // 16:00, 1 h before
        assertEquals(CarbCode.HIGH, dinner.code) // 19:30, 1.5 h after the end
        assertTrue(snack.reason.contains("before"))
    }

    @Test
    fun aRestDayIsLowExceptTheDinnerBeforeAHardMorning() {
        val rest = PlannedDay(day, emptyList())
        val tomorrow = PlannedDay(day.plusDays(1), listOf(session(8, 120.0, Intensity.LONG, day.plusDays(1))))
        val coded = codeMeals(plan(rest, tomorrow), rest, tomorrow, kg, zone)
        assertEquals(CarbCode.LOW, coded.meals.first { it.kind == MealSlotKind.BREAKFAST }.code)
        assertEquals(CarbCode.HIGH, coded.meals.first { it.kind == MealSlotKind.DINNER }.code)
    }

    @Test
    fun trainingLowIsOnlyByChoiceAndNeverOnADeficitDay() {
        val pd = PlannedDay(day, listOf(session(9, 45.0, Intensity.EASY)))
        val p = plan(pd)
        assertEquals(CarbCode.MEDIUM, codeMeals(p, pd, null, kg, zone).meals.first { it.kind == MealSlotKind.BREAKFAST }.code)
        assertEquals(CarbCode.LOW, codeMeals(p, pd, null, kg, zone, trainLow = true).meals.first { it.kind == MealSlotKind.BREAKFAST }.code)
        assertEquals(CarbCode.MEDIUM, codeMeals(p, pd, null, kg, zone, trainLow = true, deficit = true).meals.first { it.kind == MealSlotKind.BREAKFAST }.code)
    }

    @Test
    fun aSnackIsAddedWhenNoMealFallsBeforeAKeySession() {
        // 06:00 race-pace session: breakfast at 07:30 is after it, so a pre-session snack goes in at 04:30... and the
        // breakfast clash moves nothing (it does not fall inside the session).
        val pd = PlannedDay(day, listOf(session(10, 90.0, Intensity.HARD)))
        val coded = codeMeals(plan(pd), pd, null, kg, zone, times = MealTimes(snack = null))
        // Breakfast 07:30 is 2.5 h before: HIGH, no extra snack needed.
        assertTrue(coded.meals.none { it.kind == MealSlotKind.PRE_SESSION })
        val noBreakfastGap = codeMeals(plan(pd), pd, null, kg, zone, times = MealTimes(breakfast = java.time.LocalTime.of(5, 0), snack = null))
        assertTrue(noBreakfastGap.meals.any { it.kind == MealSlotKind.PRE_SESSION })
        // A recovery snack: lunch at 12:30 is 1 h after the 11:30 end, so none is added here.
        assertTrue(coded.meals.none { it.kind == MealSlotKind.RECOVERY })
    }

    @Test
    fun aMealDuringASessionMovesBeforeIt() {
        val pd = PlannedDay(day, listOf(session(7, 120.0, Intensity.LONG)))
        val breakfast = codeMeals(plan(pd), pd, null, kg, zone).meals.first { it.kind == MealSlotKind.BREAKFAST }
        assertEquals(at(day, 4, 30), breakfast.time)
    }

    @Test
    fun theMealsCarryTheDayLessWhatIsTakenDuringSessions() {
        val pd = PlannedDay(day, listOf(session(8, 180.0, Intensity.LONG)))
        val p = plan(pd)
        val coded = codeMeals(p, pd, null, kg, zone)
        val inSession = coded.sessions.sumOf { it.fuel.midG }
        assertTrue(inSession > 100)
        assertEquals(p.carbG - inSession, coded.meals.sumOf { it.carbMidG }, 1.0)
        // Protein at main meals only.
        assertNull(coded.meals.first { it.kind == MealSlotKind.SNACK }.proteinLowG)
        assertEquals(21.0, coded.meals.first { it.kind == MealSlotKind.LUNCH }.proteinLowG!!, 1e-9)
    }

    @Test
    fun aLoggedMealIsMatchedToItsSlotAndRead() {
        val pd = PlannedDay(day, emptyList())
        val meals = codeMeals(plan(pd), pd, null, kg, zone).meals
        val lunch = mealForLog(meals, at(day, 13, 10))!!
        assertEquals(MealSlotKind.LUNCH, lunch.kind)
        assertNull(mealForLog(meals, at(day, 2)))
        assertEquals(MealAdherence.NOT_LOGGED, adherence(lunch, null))
        assertEquals(MealAdherence.UNDER, adherence(lunch, lunch.carbLowG - 1))
        assertEquals(MealAdherence.WITHIN, adherence(lunch, lunch.carbMidG))
    }

    @Test
    fun sessionFuelFollowsDuration() {
        assertEquals(SessionFuelKind.NONE, sessionFuel(40.0, Intensity.HARD).kind)
        assertEquals(SessionFuelKind.RINSE, sessionFuel(60.0, Intensity.EASY).kind)
        val twoHours = sessionFuel(120.0, Intensity.RACE)
        assertEquals(45.0, twoHours.gPerHourLow, 0.0)
        assertEquals(60.0, twoHours.gPerHourHigh, 0.0)
        // Over 2.5 h, capped at the tolerated 60 g/h until the gut is trained; 90 once it is.
        assertEquals(60.0, sessionFuel(240.0, Intensity.LONG).gPerHourHigh, 0.0)
        assertEquals(90.0, sessionFuel(240.0, Intensity.LONG, toleratedGPerHour = 90.0).gPerHourHigh, 0.0)
        assertEquals(240.0, sessionFuel(240.0, Intensity.LONG).totalHighG, 1e-9)
    }
}
