package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionDetailTest {

    @Test
    fun testTwoEvenKmSplitsWithHeartRate() {
        // 0->1km in 300s, 1->2km in 330s -- evenly-sampled distance, real
        // heart-rate samples split cleanly across the two windows.
        val distance = listOf(TimePoint(0, 0.0), TimePoint(300, 1.0), TimePoint(630, 2.0))
        val heartRate = listOf(TimePoint(100, 140.0), TimePoint(200, 150.0), TimePoint(500, 160.0))
        val splits = computeKmSplits(distance, heartRate)
        assertEquals(2, splits.size)
        assertEquals(1, splits[0].km)
        assertEquals(300L, splits[0].durationSec)
        assertEquals(145.0, splits[0].avgHr!!, 0.01) // 140, 150 both before 300s
        assertEquals(2, splits[1].km)
        assertEquals(330L, splits[1].durationSec)
        assertEquals(160.0, splits[1].avgHr!!, 0.01) // only 160 falls in 300..630
    }

    @Test
    fun testCurrentPartialKmIsNotEmittedAsASplit() {
        // 1.6km covered -- one completed split, the trailing 0.6km in
        // progress is left off rather than shown as a fake short split.
        val distance = listOf(TimePoint(0, 0.0), TimePoint(240, 1.0), TimePoint(384, 1.6))
        val splits = computeKmSplits(distance, emptyList())
        assertEquals(1, splits.size)
        assertEquals(240L, splits[0].durationSec)
    }

    @Test
    fun testUnderOneKmProducesNoSplits() {
        assertTrue(computeKmSplits(listOf(TimePoint(0, 0.0), TimePoint(200, 0.8)), emptyList()).isEmpty())
    }

    @Test
    fun testInterpolatesAcrossASparseDistanceSample() {
        // A single real interval spanning 0->2km over 600s -- the 1km
        // boundary is linearly interpolated at the midpoint, 300s.
        val distance = listOf(TimePoint(0, 0.0), TimePoint(600, 2.0))
        val splits = computeKmSplits(distance, emptyList())
        assertEquals(2, splits.size)
        assertEquals(300L, splits[0].durationSec)
        assertEquals(300L, splits[1].durationSec)
    }
}
