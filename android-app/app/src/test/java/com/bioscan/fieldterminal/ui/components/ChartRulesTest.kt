package com.bioscan.fieldterminal.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

// Analysis review phase C: the pure parts of the chart rules.
class ChartRulesTest {
    @Test
    fun atLeastThreeNiceEvenlySpacedTicksInsideTheRange() {
        for ((lo, hi) in listOf(41.3 to 63.8, 5.9 to 6.3, 0.0 to 1.0, 52.0 to 53.1, 1200.0 to 14800.0, -3.2 to 2.7)) {
            val ticks = niceTicks(lo, hi)
            assertTrue("$lo..$hi -> $ticks", ticks.size >= 3)
            assertTrue(ticks.all { it >= lo - 1e-9 && it <= hi + 1e-9 })
            val step = ticks[1] - ticks[0]
            ticks.zipWithNext().forEach { (a, b) -> assertEquals(step, b - a, step * 1e-6) }
            val mag = Math.pow(10.0, Math.floor(Math.log10(step) + 1e-9))
            val norm = Math.round(step / mag * 1000) / 1000.0
            assertTrue("$step is not 1, 2 or 5 x 10^k", norm == 1.0 || norm == 2.0 || norm == 5.0)
        }
        assertEquals(listOf(45.0, 50.0, 55.0, 60.0), niceTicks(41.3, 63.8))
    }

    @Test
    fun tickLabelsCarryTheDecimalsTheirSpacingNeeds() {
        assertEquals(0, tickDecimals(listOf(45.0, 50.0, 55.0)))
        assertEquals(1, tickDecimals(listOf(6.0, 6.1, 6.2)))
        assertEquals(1, tickDecimals(listOf(0.0, 0.5, 1.0)))
        assertEquals(2, tickDecimals(listOf(0.05, 0.1, 0.15)))
    }

    @Test
    fun thePersonalScaleIsTheMiddle96PercentOfTheirOwnReadings() {
        val scale = personalScale((1..100).map { it.toDouble() })!!
        assertEquals(2.98, scale.start, 1e-9)
        assertEquals(98.02, scale.endInclusive, 1e-9)
        assertNull(personalScale((1..13).map { it.toDouble() }))
        assertNull(personalScale(List(30) { 5.0 }))
    }

    @Test
    fun aDailySeriesBreaksAtMissingDaysAndASessionSeriesDoesNot() {
        val d = LocalDate.of(2026, 10, 1)
        val pts = listOf(d to 1.0, d.plusDays(1) to 2.0, d.plusDays(4) to 3.0, d.plusDays(5) to 4.0, d.plusDays(9) to 5.0)
        assertEquals(listOf(2, 2, 1), trendSegments(pts, maxGapDays = 1).map { it.size })
        assertEquals(listOf(5), trendSegments(pts, maxGapDays = null).map { it.size })
        assertEquals(listOf(4, 1), trendSegments(pts, maxGapDays = 3).map { it.size })
        assertTrue(trendSegments(emptyList<Pair<LocalDate, Double>>(), 1).isEmpty())
    }
}
