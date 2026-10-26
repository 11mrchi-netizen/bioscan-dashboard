package com.bioscan.fieldterminal.domain.analysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

// Analysis review A4: r with its 95% interval, at least 20 pairs, and a strength word only when the interval excludes 0.
class CorrelationTest {
    @Test
    fun fisherIntervalMatchesTheTextbookValue() {
        // r = 0.5 from 7 pairs: z = 0.549, SE = 0.5, interval tanh(0.549 +/- 0.98) = -0.41 to 0.91.
        val (lo, hi) = fisherInterval(0.5, 7)
        assertEquals(-0.41, lo, 0.01)
        assertEquals(0.91, hi, 0.01)
    }

    @Test
    fun fewerThanTwentyPairsIsNotEnough() {
        assertNull(pearsonCorrelation((0 until 19).map { it.toDouble() to it * 2.0 }))
    }

    @Test
    fun aClearStrongRelationshipGetsItsWord() {
        val pairs = (0 until 30).map { it.toDouble() to it * 2.0 + (it % 3) }
        val r = pearsonCorrelation(pairs)!!
        assertTrue(r.clear)
        assertEquals("strong", r.strength)
        assertTrue(r.lower > 0.9)
    }

    @Test
    fun aWeakRelationshipOnFewPairsIsUnclear() {
        // r about 0.3 on 20 pairs: the interval crosses zero.
        val ys = listOf(3.0, 1.0, 4.0, 1.0, 5.0, 9.0, 2.0, 6.0, 5.0, 3.0, 5.0, 8.0, 9.0, 7.0, 9.0, 3.0, 2.0, 3.0, 8.0, 4.0)
        val r = pearsonCorrelation(ys.mapIndexed { i, y -> i.toDouble() to y })!!
        assertFalse(r.clear)
        assertEquals("unclear", r.strength)
        assertTrue(r.lower < 0 && r.upper > 0)
    }
}
