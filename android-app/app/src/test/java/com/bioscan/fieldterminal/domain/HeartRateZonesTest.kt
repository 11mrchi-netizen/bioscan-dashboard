package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HeartRateZonesTest {

    private val maxHr = 200.0

    @Test
    fun boundaryValuesResolveToTheExpectedZone() {
        assertEquals(HeartRateZone.BelowZ1, heartRateZoneFor(98.0, maxHr)) // 49%
        assertEquals(HeartRateZone.Z1, heartRateZoneFor(100.0, maxHr)) // 50%
        assertEquals(HeartRateZone.Z1, heartRateZoneFor(118.0, maxHr)) // 59%
        assertEquals(HeartRateZone.Z2, heartRateZoneFor(120.0, maxHr)) // 60%
        assertEquals(HeartRateZone.Z3, heartRateZoneFor(140.0, maxHr)) // 70%
        assertEquals(HeartRateZone.Z4, heartRateZoneFor(160.0, maxHr)) // 80%
        assertEquals(HeartRateZone.Z5, heartRateZoneFor(180.0, maxHr)) // 90%
        assertEquals(HeartRateZone.Z5, heartRateZoneFor(200.0, maxHr)) // 100%
    }

    @Test
    fun breakdownSumsDurationsPerZoneFromStartingSample() {
        // 0-60s at 100bpm (Z1), 60-180s at 140bpm (Z3), 180-240s at 100bpm (Z1) again.
        val points = listOf(
            TimePoint(0, 100.0),
            TimePoint(60, 140.0),
            TimePoint(180, 100.0),
            TimePoint(240, 100.0),
        )
        val breakdown = heartRateZoneBreakdown(points, maxHr)

        val totalSeconds = breakdown.sumOf { it.seconds }
        assertEquals(240L, totalSeconds)
        assertEquals(120L, breakdown.first { it.zone == HeartRateZone.Z1 }.seconds) // 0-60 + 180-240
        assertEquals(120L, breakdown.first { it.zone == HeartRateZone.Z3 }.seconds) // 60-180
    }

    @Test
    fun fewerThanTwoPointsReturnsEmpty() {
        assertTrue(heartRateZoneBreakdown(listOf(TimePoint(0, 100.0)), maxHr).isEmpty())
        assertTrue(heartRateZoneBreakdown(emptyList(), maxHr).isEmpty())
    }
}
