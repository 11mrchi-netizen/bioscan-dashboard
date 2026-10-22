package com.bioscan.fieldterminal.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZonedScaleTest {
    private fun f(v: Double, lo: Double?, hi: Double?) = zonedFraction(v, lo, hi)!!.toDouble()

    @Test
    fun aTargetRangeSitsAtSeventyToNinety() {
        // 2,160-2,640 kcal (a 2,400 goal +-10 %): over runs to 2,640 + 240.
        assertEquals(0.0, f(0.0, 2160.0, 2640.0), 1e-6)
        assertEquals(0.35, f(1080.0, 2160.0, 2640.0), 1e-6)
        assertEquals(0.70, f(2160.0, 2160.0, 2640.0), 1e-6)
        assertEquals(0.80, f(2400.0, 2160.0, 2640.0), 1e-6)
        assertEquals(0.90, f(2640.0, 2160.0, 2640.0), 1e-6)
        assertEquals(1.0, f(2880.0, 2160.0, 2640.0), 1e-6)
        assertEquals(1.0, f(9000.0, 2160.0, 2640.0), 1e-6)
        assertEquals(ZONE_TARGET_START to ZONE_TARGET_END, zoneEdges(2160.0, 2640.0))
    }

    @Test
    fun withNoUpperLimitTheLastThirtyPercentIsTheGoodRange() {
        assertEquals(0.70, f(90.0, 90.0, null), 1e-6)
        assertEquals(0.85, f(135.0, 90.0, null), 1e-6)
        assertEquals(1.0, f(180.0, 90.0, null), 1e-6)
        assertEquals(1.0, f(500.0, 90.0, null), 1e-6)
        assertEquals(ZONE_TARGET_START to 1f, zoneEdges(90.0, null))
    }

    @Test
    fun withOnlyAnUpperLimitEverythingUnderItIsGood() {
        assertEquals(0.45, f(1150.0, null, 2300.0), 1e-6)
        assertEquals(0.90, f(2300.0, null, 2300.0), 1e-6)
        assertEquals(1.0, f(3450.0, null, 2300.0), 1e-6)
        assertEquals(0f to ZONE_TARGET_END, zoneEdges(null, 2300.0))
    }

    @Test
    fun itRisesWithTheValueAndNeverLeavesTheBar() {
        for ((lo, hi) in listOf(2160.0 to 2640.0, 90.0 to null, null to 2300.0)) {
            val fs = (0..400).map { zonedFraction(it * 10.0, lo, hi)!! }
            fs.zipWithNext().forEach { (a, b) -> assertTrue(b >= a) }
            assertTrue(fs.all { it in 0f..1f })
        }
        assertEquals(0.0, f(-5.0, 2160.0, 2640.0), 1e-6)
    }

    @Test
    fun noBoundsOrAnEmptyRangeDoNotBreak() {
        assertNull(zonedFraction(10.0, null, null))
        assertNull(zoneEdges(0.0, 0.0))
        // An upper limit at or under the RDA is ignored: the RDA alone zones the bar.
        assertEquals(0.70, f(50.0, 50.0, 50.0), 1e-6)
        assertEquals(ZONE_TARGET_START to 1f, zoneEdges(50.0, 40.0))
    }
}
