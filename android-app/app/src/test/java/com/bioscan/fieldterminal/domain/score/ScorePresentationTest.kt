package com.bioscan.fieldterminal.domain.score

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ScorePresentationTest {
    private val day = LocalDate.of(2026, 11, 3)

    @Test
    fun formats() {
        assertEquals("8h05", formatInputValue(ScoreKeys.SLEEP_HOURS, 8 + 5 / 60.0))
        assertEquals("mid 03:10", formatInputValue(ScoreKeys.SLEEP_MIDPOINT, 15 * 60 + 10.0))
        assertEquals("2h41", formatInputValue(ScoreKeys.SLEEP_DEEP_REM, 161.0))
        assertEquals("18 min", formatInputValue(ScoreKeys.SLEEP_DEEP_REM, 18.0))
        assertEquals("58 ms", formatInputValue(ScoreKeys.HRV, 58.2))
        assertEquals("96 %", formatInputValue(ScoreKeys.ENERGY_RATIO, 0.96))
        assertEquals("08:10", formatInputValue(ScoreKeys.GUT_TIMING, 490.0))
        assertEquals("yes", formatInputValue(ScoreKeys.LATE_SCREEN, 1.0))
        assertEquals("none", formatInputValue(ScoreKeys.SLEEP_DEBT, 0.0))
    }

    @Test
    fun inputPointsAddUpToTheDomainsDistanceFromTypical() {
        val series = mapOf(
            ScoreKeys.FUEL_LOGGED_SHARE to mapOf(day to 1.0),
            ScoreKeys.ENERGY_RATIO to mapOf(day to 1.0),
            ScoreKeys.PROTEIN_RATIO to mapOf(day to 0.7),
            ScoreKeys.CARB_RATIO to mapOf(day to 1.0),
            ScoreKeys.WATER_RATIO to mapOf(day to 1.0),
        )
        val raw = combineDomain(ScoreSpecs.FUEL, series, day)
        val points = inputPoints(raw)
        assertEquals(raw.raw!! - ScoreScale.TYPICAL, points.values.sum(), 1e-6)
        assertTrue(points.getValue(ScoreKeys.PROTEIN_RATIO) < points.getValue(ScoreKeys.ENERGY_RATIO))
    }

    @Test
    fun sentencesNameTheBiggestMovers() {
        val charge = ChargeScore(
            day = day, score = 112.0, uncapped = 112.0, raw = 105.0, percentile = 0.93,
            drivers = listOf(Driver(DomainKey.SLEEP, 14.0), Driver(DomainKey.RECOVERY, 11.0), Driver(DomainKey.LOAD, -6.0)),
            domainsUsed = DomainKey.entries.toSet(), cap = null, calibrating = false, previous = 100.0,
        )
        assertEquals("Better than 93 of your last 100 mornings.", chargeRankLine(charge))
        assertEquals("Mostly SLEEP (+14) and RECOVERY (+11). LOAD holds it back (-6).", driverSummary(charge))
    }

    @Test
    fun referenceLinesSayWhatTheValueWasComparedWith() {
        val atLeast = InputScore(InputSpec(ScoreKeys.PROTEIN_RATIO, "Protein", 1.0, InputKind.AtLeast(1.0, 0.2)), 0.8, 1.0, null, 0.0, 80.0, InputDirection.SHORT)
        assertEquals("target at least 100 %", referenceLine(atLeast))
        val band = InputScore(InputSpec(ScoreKeys.RHR, "Resting HR", 1.0, InputKind.Baseline(betterHigher = false)), 47.0, 50.0, 1.5, 0.0, 80.0, InputDirection.BELOW)
        assertEquals("your normal 49 bpm – 52 bpm", referenceLine(band))
    }
}
