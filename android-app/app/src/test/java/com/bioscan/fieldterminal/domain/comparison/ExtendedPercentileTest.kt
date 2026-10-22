package com.bioscan.fieldterminal.domain.comparison

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtendedPercentileTest {
    // The live ACSM VO2max rows: men 30-39 (about symmetric) and women 60-69 (right-skewed).
    private val men30 = mapOf(10 to 33.0, 25 to 37.9, 50 to 42.4, 75 to 48.3, 90 to 52.9)
    private val women60 = mapOf(10 to 16.2, 25 to 17.9, 50 to 20.0, 75 to 23.4, 90 to 27.1)

    @Test
    fun insideTheTableItIsTheTablesOwnInterpolation() {
        assertEquals(61.0, extendedPercentile(45.0, men30)!!, 0.05)
        assertEquals(interpolatePercentile(45.0, men30.entries.sortedBy { it.key }), extendedPercentile(45.0, men30)!!, 1e-9)
        assertEquals(90.0, extendedPercentile(52.9, men30)!!, 1e-6)
        assertEquals(10.0, extendedPercentile(33.0, men30)!!, 1e-6)
    }

    @Test
    fun pastTheTableASplitNormalCarriesOnFromItsEnds() {
        // Upper spread (52.9 - 42.4) / 1.2816: 60 mL/kg/min is about the 98th percentile; 28 about the 2nd.
        assertEquals(98.4, extendedPercentile(60.0, men30)!!, 0.2)
        assertEquals(2.5, extendedPercentile(28.0, men30)!!, 0.2)
        // Continuous at both ends.
        assertEquals(90.0, extendedPercentile(52.9001, men30)!!, 0.01)
        assertEquals(10.0, extendedPercentile(16.1999, women60)!!, 0.01)
    }

    @Test
    fun itRisesWithTheValueAcrossTheWholeRangeAndStaysInOneToNinetyNine() {
        for (table in listOf(men30, women60)) {
            val values = (0..800).map { 5.0 + it * 0.08 }
            val ps = values.map { extendedPercentile(it, table)!! }
            ps.zipWithNext().forEach { (a, b) -> assertTrue("not monotone: $a then $b", b >= a) }
            assertTrue(ps.all { it in 1.0..99.0 })
        }
        assertEquals(1.0, extendedPercentile(0.0, women60)!!, 0.0)
        assertEquals(99.0, extendedPercentile(90.0, men30)!!, 0.0)
    }

    @Test
    fun aTableWithoutAMedianOrWithoutBothSidesHasNoExtension() {
        assertNull(extendedPercentile(40.0, mapOf(10 to 30.0, 90 to 50.0)))
        assertNull(extendedPercentile(40.0, mapOf(50 to 40.0, 90 to 50.0)))
    }

    @Test
    fun theNormalCdfIsPhi() {
        assertEquals(0.975, normalCdf(1.96), 1e-4)
        assertEquals(0.5, normalCdf(0.0), 1e-9)
        assertEquals(0.1, normalCdf(inverseNormalCdf(0.1)), 1e-9)
        assertEquals(-1.2816, inverseNormalCdf(0.1), 1e-3)
    }
}
