package com.bioscan.fieldterminal.domain.fuel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class IntakeHistoryTest {
    private val d = LocalDate.of(2026, 11, 6)
    private val kg = 70.0
    private fun plan(date: LocalDate, carbG: Double, loading: Boolean) = DayFuelPlan(
        date, if (loading) DayClass.LOADING else DayClass.MEDIUM, if (loading) BAND_LOADING else BAND_MODERATE,
        carbG / kg, carbG, 130.0, 80.0, 3000.0, emptyList(), emptyList(), loading, null, 0.0,
    )

    @Test
    fun aShortLoadingDayIsCaughtUpBeforeTheRace() {
        val planned = listOf(plan(d.minusDays(1), 770.0, true))
        val logged = listOf(LoggedDay(d.minusDays(1), 4, 600.0, 3200.0))
        val r = checkIntakeHistory(planned, logged, kg, raceSoon = true)
        assertEquals(IntakeStatus.UNDER, r.days.single().status)
        assertEquals(170.0, r.loadingShortfallG, 1e-9)
        assertEquals(140.0, r.catchUpG, 1e-9) // capped at 2 g/kg
        assertTrue(r.earlyFuelling)
    }

    @Test
    fun aDayWithFewMealsIsNotLoggedAndAssumedAsPlanned() {
        val planned = listOf(plan(d.minusDays(1), 770.0, true))
        val r = checkIntakeHistory(planned, listOf(LoggedDay(d.minusDays(1), 2, 200.0, 900.0)), kg, raceSoon = true)
        assertEquals(IntakeStatus.NOT_LOGGED, r.days.single().status)
        assertEquals(0.0, r.catchUpG, 0.0)
        assertTrue(r.notes.any { "assumed" in it })
    }

    @Test
    fun withoutARaceNothingIsCaughtUp() {
        val planned = listOf(plan(d.minusDays(1), 770.0, true))
        val r = checkIntakeHistory(planned, listOf(LoggedDay(d.minusDays(1), 4, 500.0, 2900.0)), kg, raceSoon = false)
        assertEquals(0.0, r.catchUpG, 0.0)
        assertFalse(r.earlyFuelling)
    }

    @Test
    fun aLowMorningWeightRaisesPreHydration() {
        val r = checkIntakeHistory(emptyList(), emptyList(), kg, raceSoon = true, morningWeightKg = 69.0, usualWeightKg = 70.0)
        assertTrue(r.preHydrationBoost)
        assertFalse(checkIntakeHistory(emptyList(), emptyList(), kg, true, 69.6, 70.0).preHydrationBoost)
    }

    @Test
    fun catchUpAddsToTheDay() {
        val p = withCatchUp(plan(d, 700.0, true), 140.0)
        assertEquals(840.0, p.carbG, 0.0)
        assertEquals(3560.0, p.kcal!!, 0.0)
        assertEquals(12.0, p.carbGPerKg, 1e-9)
    }
}
