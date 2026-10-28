package com.bioscan.fieldterminal.domain.fuel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class HydrationAccountingTest {
    private val d = LocalDate.of(2026, 10, 20)
    private val prior = SweatProfile(emptyList(), 1.0, 0)

    private fun run(vararg days: HydrationDayInputs) = hydrationAccounting(days.toList(), prior, 70.0, 2500.0, 3500.0)

    @Test
    fun aRestDayDemandIsTheBaselineRange() {
        val day = run(HydrationDayInputs(d, 3000.0, emptyList(), null)).single()
        assertEquals(2500.0, day.demandLowMl, 0.0)
        assertEquals(3500.0, day.demandHighMl, 0.0)
        assertEquals(HydrationStatus.Within, day.status)
        assertTrue(day.temperatureKnown)
    }

    @Test
    fun aSessionWidensAndRaisesTheRange() {
        val rest = run(HydrationDayInputs(d, 3000.0, emptyList(), 18.0)).single()
        val trained = run(HydrationDayInputs(d, 3000.0, listOf(HydrationWorkout(60.0, 5)), 18.0)).single()
        assertTrue(trained.demandLowMl > rest.demandLowMl)
        assertTrue(trained.demandHighMl - trained.demandLowMl > rest.demandHighMl - rest.demandLowMl)
        assertEquals(60.0, trained.workoutMinutes, 0.0)
    }

    @Test
    fun aHotDayCostsMoreThanACoolOne() {
        val cool = run(HydrationDayInputs(d, null, listOf(HydrationWorkout(60.0, 5)), 10.0)).single()
        val hot = run(HydrationDayInputs(d, null, listOf(HydrationWorkout(60.0, 5)), 30.0)).single()
        assertTrue(hot.exerciseHighMl > cool.exerciseHighMl)
    }

    @Test
    fun nothingLoggedIsAGapNotZero() {
        val day = run(HydrationDayInputs(d, null, emptyList(), null)).single()
        assertEquals(HydrationStatus.NoIntake, day.status)
    }

    @Test
    fun intakeBelowAndAboveTheRangeIsSaidSo() {
        val days = run(HydrationDayInputs(d, 1500.0, emptyList(), null), HydrationDayInputs(d.plusDays(1), 4200.0, emptyList(), null))
        assertEquals(listOf(HydrationStatus.Below, HydrationStatus.Above), days.map { it.status })
    }

    @Test
    fun aSessionDayWithoutWeatherIsFlagged() {
        val day = run(HydrationDayInputs(d, 3000.0, listOf(HydrationWorkout(45.0, null)), null)).single()
        assertFalse(day.temperatureKnown)
    }

    @Test
    fun effortComesFromRpeAndLongSessionsAreLong() {
        assertEquals(Intensity.EASY, hydrationIntensity(40.0, 2))
        assertEquals(Intensity.MODERATE, hydrationIntensity(40.0, null))
        assertEquals(Intensity.HARD, hydrationIntensity(40.0, 8))
        assertEquals(Intensity.LONG, hydrationIntensity(150.0, 3))
    }
}
