package com.bioscan.fieldterminal.domain.comparison

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class PersonalBaselineTest {

    // Hand-calculated: 10 points, 2026-09-14..23, values 40..58 step 2 (an
    // arithmetic sequence, chosen for exact-by-hand stats rather than messy
    // real HRV floats). mean=49, population variance=33.0 (deviations
    // -9..9 step 2, squares sum to 330, /10), stdDev=sqrt(33)=5.7446...
    // Nearest-rank (not interpolated) percentiles over the sorted 10 values
    // [40,42,44,46,48,50,52,54,56,58]: P25 index=floor(0.25*9)=2 -> 44,
    // P50 index=floor(0.5*9)=4 -> 48, P75 index=floor(0.75*9)=6 -> 52,
    // P90 index=floor(0.9*9)=8 -> 56.
    private val tenPointFixture = (0..9).map { i -> LocalDate.of(2026, 9, 14 + i) to (40.0 + i * 2.0) }

    @Test
    fun testHandCalculatedStats() {
        val baseline = personalBaseline(tenPointFixture, windowDays = 30, asOf = LocalDate.of(2026, 9, 23))!!
        assertEquals(49.0, baseline.mean, 0.001)
        assertEquals(48.0, baseline.median, 0.001)
        assertEquals(kotlin.math.sqrt(33.0), baseline.stdDev, 0.001)
        assertEquals(44.0, baseline.quantiles.getValue(25), 0.001)
        assertEquals(52.0, baseline.quantiles.getValue(75), 0.001)
        assertEquals(56.0, baseline.quantiles.getValue(90), 0.001)
        assertEquals(58.0, baseline.max, 0.001)
        assertEquals(40.0, baseline.min, 0.001)
        assertEquals(10, baseline.observationCount)
    }

    @Test
    fun testBelowMinimumObservationsReturnsNull() {
        val sparse = tenPointFixture.take(4)
        assertNull(personalBaseline(sparse, windowDays = 30, asOf = LocalDate.of(2026, 9, 23)))
    }

    @Test
    fun testStabilityGate() {
        val notStable = personalBaseline(tenPointFixture, windowDays = 30, asOf = LocalDate.of(2026, 9, 23))!!
        assertFalse(notStable.isStable)

        val fourteenPoints = (0..13).map { i -> LocalDate.of(2026, 9, 1 + i) to (40.0 + i) }
        val stable = personalBaseline(fourteenPoints, windowDays = 30, asOf = LocalDate.of(2026, 9, 23))!!
        assertTrue(stable.isStable)
    }

    @Test
    fun testWindowExcludesPointsOutsideRange() {
        val outsideWindow = tenPointFixture + (LocalDate.of(2026, 1, 1) to 999.0)
        val baseline = personalBaseline(outsideWindow, windowDays = 30, asOf = LocalDate.of(2026, 9, 23))!!
        assertEquals(10, baseline.observationCount)
        assertEquals(58.0, baseline.max, 0.001)
    }
}
