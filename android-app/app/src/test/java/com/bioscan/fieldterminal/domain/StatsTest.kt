package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class StatsTest {

    // mean

    @Test fun testMean_empty() = assertEquals(0.0, mean(emptyList()), 0.0)

    @Test fun testMean_single() = assertEquals(5.0, mean(listOf(5.0)), 0.0)

    @Test fun testMean_symmetric() = assertEquals(3.0, mean(listOf(1.0, 2.0, 3.0, 4.0, 5.0)), 0.0)

    // populationStdDev

    @Test fun testStdDev_empty() = assertEquals(0.0, populationStdDev(emptyList()), 0.0)

    @Test fun testStdDev_constant() = assertEquals(0.0, populationStdDev(listOf(7.0, 7.0, 7.0)), 0.0)

    @Test fun testStdDev_twoValues() {
        // SD of [2, 4] = sqrt(((2-3)^2 + (4-3)^2) / 2) = sqrt(1) = 1
        assertEquals(1.0, populationStdDev(listOf(2.0, 4.0)), 1e-9)
    }

    // median

    @Test fun testMedian_empty() = assertEquals(0.0, median(emptyList()), 0.0)

    @Test fun testMedian_odd() = assertEquals(3.0, median(listOf(5.0, 1.0, 3.0)), 0.0)

    @Test fun testMedian_even() = assertEquals(2.5, median(listOf(1.0, 2.0, 3.0, 4.0)), 0.0)

    // interquartileRange

    @Test fun testIqr_lessThanTwo() = assertEquals(0.0, interquartileRange(listOf(5.0)), 0.0)

    @Test fun testIqr_fourValues() {
        // [1,2,3,4] lower=[1,2] upper=[3,4]; Q1=1.5, Q3=3.5 → IQR=2
        assertEquals(2.0, interquartileRange(listOf(1.0, 2.0, 3.0, 4.0)), 0.0)
    }

    @Test fun testIqr_fiveValues() {
        // [1,2,3,4,5] lower=[1,2] upper=[4,5]; Q1=1.5, Q3=4.5 → IQR=3
        assertEquals(3.0, interquartileRange(listOf(1.0, 2.0, 3.0, 4.0, 5.0)), 0.0)
    }

    // windowEndingAt

    @Test fun testWindowEndingAt_exact() {
        val today = LocalDate.of(2026, 1, 10)
        val series = (1..10).map { LocalDate.of(2026, 1, it) to it.toDouble() }
        val window = windowEndingAt(series, today, 7)
        assertEquals(7, window.size)
        assertEquals(LocalDate.of(2026, 1, 4), window.first().first)
        assertEquals(today, window.last().first)
    }

    @Test fun testWindowEndingAt_fewerAvailable() {
        val today = LocalDate.of(2026, 1, 3)
        val series = (1..3).map { LocalDate.of(2026, 1, it) to it.toDouble() }
        assertEquals(3, windowEndingAt(series, today, 28).size)
    }

    // mannKendall

    @Test fun testMannKendall_upwardTrend() {
        // strictly increasing → S = n*(n-1)/2, significant
        val result = mannKendall(listOf(1.0, 2.0, 3.0, 4.0, 5.0, 6.0, 7.0, 8.0))
        assertTrue(result.tau > 0)
        assertTrue(result.significant)
    }

    @Test fun testMannKendall_downwardTrend() {
        val result = mannKendall(listOf(8.0, 7.0, 6.0, 5.0, 4.0, 3.0, 2.0, 1.0))
        assertTrue(result.tau < 0)
        assertTrue(result.significant)
    }

    @Test fun testMannKendall_constant() {
        val result = mannKendall(listOf(5.0, 5.0, 5.0, 5.0, 5.0))
        assertEquals(0.0, result.tau, 0.0)
        assertFalse(result.significant)
    }

    // dualEwma

    @Test fun testDualEwma_constantSeries() {
        val start = LocalDate.of(2026, 1, 1)
        val end = LocalDate.of(2026, 1, 30)
        val data = generateSequence(start) { it.plusDays(1) }
            .takeWhile { !it.isAfter(end) }
            .associateWith { 10.0 }
        val (adaptation, fatigue) = dualEwma(data, start, end, fatigueTauDays = 7.0, adaptationTauDays = 42.0)
        // Both should converge near 10 for a constant series of 30 days
        assertEquals(10.0, adaptation, 0.5)
        assertEquals(10.0, fatigue, 0.5)
    }

    @Test fun testDualEwma_emptyHistoryYieldsZero() {
        val start = LocalDate.of(2026, 1, 1)
        val (adaptation, fatigue) = dualEwma(emptyMap(), start, start, fatigueTauDays = 7.0, adaptationTauDays = 42.0)
        assertEquals(0.0, adaptation, 0.0)
        assertEquals(0.0, fatigue, 0.0)
    }
}
