package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class CaptureCoverageTest {
    private val today = LocalDate.of(2026, 10, 12)

    @Test
    fun testCoverage_countsOnlyDatesInsideTheWindow() {
        val streams = mapOf(
            "wellbeing" to setOf(today, today.minusDays(27), today.minusDays(28), today.plusDays(1)),
            "hydration" to (0L..9L).map { today.minusDays(it) }.toSet(),
        )
        val result = captureCoverage(streams, today, windowDays = 28).associateBy { it.stream }
        assertEquals(2, result.getValue("wellbeing").daysLogged) // day 28 ago and tomorrow are outside
        assertEquals(10, result.getValue("hydration").daysLogged)
        assertEquals(10.0 / 28.0, result.getValue("hydration").fraction, 1e-9)
    }

    @Test
    fun testCoverage_listsEveryStreamEvenWhenEmptyAndMarksAlcoholInformational() {
        val result = captureCoverage(emptyMap(), today)
        assertEquals(CAPTURE_STREAMS.map { it.first }, result.map { it.stream })
        assertTrue(result.all { it.daysLogged == 0 })
        assertTrue(result.first { it.stream == "alcohol" }.informational)
        assertFalse(result.first { it.stream == "wellbeing" }.informational)
    }
}
