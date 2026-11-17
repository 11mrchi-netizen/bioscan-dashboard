package com.bioscan.fieldterminal.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartSmoothingTest {
    @Test
    fun aStraightLineIsKeptExactlyEvenAtTheEnds() {
        val xs = (0..20).map { it.toDouble() }
        val ys = xs.map { 70 - 0.1 * it }
        assertEquals(70.0, localTrendAt(xs, ys, 0.0, 3.0), 1e-9)
        assertEquals(68.0, localTrendAt(xs, ys, 20.0, 3.0), 1e-9)
    }

    @Test
    fun dayToDayNoiseIsFlattened() {
        val xs = (0..40).map { it.toDouble() }
        val ys = xs.map { if (it.toInt() % 2 == 0) 71.0 else 69.0 } // zigzag around 70
        val curve = trendCurve(xs, ys, 3.0)
        val inner = curve.filter { it.first in 5.0..35.0 }.map { it.second }
        assertTrue(inner.all { kotlin.math.abs(it - 70.0) < 0.1 })
        assertEquals(40.0, curve.last().first, 1e-9)
    }

    @Test
    fun readingsSplitAtLongSilences() {
        val runs = splitAtGaps(listOf(0.0, 1.0, 2.0, 30.0, 31.0), { it }, maxGap = 21.0)
        assertEquals(listOf(listOf(0.0, 1.0, 2.0), listOf(30.0, 31.0)), runs)
    }

    @Test
    fun farReadingsFallBackToTheNearest() {
        assertEquals(5.0, localTrendAt(listOf(0.0, 100.0), listOf(5.0, 9.0), 1.0, 0.5), 1e-9)
    }
}
