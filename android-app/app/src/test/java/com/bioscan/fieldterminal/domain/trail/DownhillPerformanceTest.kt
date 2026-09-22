package com.bioscan.fieldterminal.domain.trail

import com.bioscan.fieldterminal.domain.TimePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DownhillPerformanceTest {

    private fun eligibleDescent(gradePercent: Double = -10.0) = TerrainSegment(
        direction = SegmentDirection.DOWNHILL, startOffsetSeconds = 0, endOffsetSeconds = 300,
        startDistanceM = 0.0, endDistanceM = 500.0, elevationChangeM = -50.0,
        movingDurationSeconds = 300, averageGradePercent = gradePercent, gradeBand = GradeBand.MODERATE_DOWN,
    )

    @Test
    fun testComputeDownhillPerformance_speedAndGap() {
        val segment = eligibleDescent()
        val speed = listOf(TimePoint(0, 12.0), TimePoint(300, 12.0))
        val heartRate = listOf(TimePoint(0, 130.0), TimePoint(300, 140.0))

        val descents = computeDownhillPerformance(listOf(segment), heartRate, emptyList(), speed)

        assertEquals(1, descents.size)
        assertEquals(12.0, descents[0].averageSpeedKmh!!, 0.001)
        assertEquals(135.0, descents[0].averageHeartRateBpm!!, 0.001)
        // pace 5 min/km at -10% grade: a moderate downhill is metabolically
        // *cheaper* than flat ground (Minetti's curve), so the same actual
        // pace corresponds to a slower flat-equivalent effort -- GAP reads
        // higher (slower) than the raw 5 min/km pace, not lower.
        val gap = descents[0].averageGradeAdjustedPaceMinPerKm!!
        org.junit.Assert.assertTrue(gap > 5.0)
    }

    @Test
    fun testComputeDownhillPerformance_excludesIneligible() {
        val tooShort = TerrainSegment(
            direction = SegmentDirection.DOWNHILL, startOffsetSeconds = 0, endOffsetSeconds = 20,
            startDistanceM = 0.0, endDistanceM = 40.0, elevationChangeM = -12.0,
            movingDurationSeconds = 20, averageGradePercent = -30.0, gradeBand = GradeBand.STEEP_DOWN,
        )
        assertEquals(0, computeDownhillPerformance(listOf(tooShort), emptyList(), emptyList(), emptyList()).size)
    }

    @Test
    fun testDescentConsistency() {
        val slow = eligibleDescent()
        val fast = eligibleDescent()
        val speedsSame = listOf(TimePoint(0, 10.0), TimePoint(300, 10.0))
        val speedsFast = listOf(TimePoint(0, 14.0), TimePoint(300, 14.0))

        val same = computeDownhillPerformance(listOf(slow, slow), emptyList(), emptyList(), speedsSame)
        assertEquals(0.0, descentConsistency(same)!!, 0.0001)

        val mixed = listOf(
            computeDownhillPerformance(listOf(slow), emptyList(), emptyList(), speedsSame)[0],
            computeDownhillPerformance(listOf(fast), emptyList(), emptyList(), speedsFast)[0],
        )
        // mean=12, variance=((10-12)^2+(14-12)^2)/2=4, sd=2, cv=2/12=0.1667
        assertEquals(0.1667, descentConsistency(mixed)!!, 0.001)

        assertNull(descentConsistency(listOf(mixed[0])))
    }
}
