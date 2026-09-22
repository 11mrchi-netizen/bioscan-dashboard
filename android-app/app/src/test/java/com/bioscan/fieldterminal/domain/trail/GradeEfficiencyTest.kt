package com.bioscan.fieldterminal.domain.trail

import com.bioscan.fieldterminal.domain.TimePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GradeEfficiencyTest {

    private fun tp(offsetSeconds: Long, distanceM: Double, elevationM: Double?) =
        Trackpoint(offsetSeconds, lat = 0.0, lon = 0.0, elevationM = elevationM, cumulativeDistanceM = distanceM)

    // Hand-verified: VAM 600 m/h / 150 bpm = 4.0 m/h per bpm.
    @Test
    fun testComputeUphillEfficiency() {
        val segment = TerrainSegment(
            direction = SegmentDirection.UPHILL, startOffsetSeconds = 0, endOffsetSeconds = 300,
            startDistanceM = 0.0, endDistanceM = 500.0, elevationChangeM = 50.0,
            movingDurationSeconds = 300, averageGradePercent = 10.0, gradeBand = GradeBand.MODERATE_UP,
        )
        val climbs = computeUphillPerformance(listOf(segment), listOf(TimePoint(0, 150.0), TimePoint(300, 150.0)), emptyList(), emptyList())

        val efficiency = computeUphillEfficiency(climbs)

        assertEquals(1, efficiency.size)
        assertEquals(600.0, efficiency[0].verticalSpeedMetersPerHour, 0.001)
        assertEquals(4.0, efficiency[0].metersPerHourPerBpm, 0.001)
    }

    @Test
    fun testComputeUphillEfficiency_missingHrExcluded() {
        val segment = TerrainSegment(
            direction = SegmentDirection.UPHILL, startOffsetSeconds = 0, endOffsetSeconds = 300,
            startDistanceM = 0.0, endDistanceM = 500.0, elevationChangeM = 50.0,
            movingDurationSeconds = 300, averageGradePercent = 10.0, gradeBand = GradeBand.MODERATE_UP,
        )
        val climbs = computeUphillPerformance(listOf(segment), emptyList(), emptyList(), emptyList())
        assertTrue(computeUphillEfficiency(climbs).isEmpty())
    }

    // Hand-verified: -50m over 300s moving = -600 m/h; -600/140 = -4.2857.
    @Test
    fun testComputeDownhillEfficiency() {
        val segment = TerrainSegment(
            direction = SegmentDirection.DOWNHILL, startOffsetSeconds = 0, endOffsetSeconds = 300,
            startDistanceM = 0.0, endDistanceM = 500.0, elevationChangeM = -50.0,
            movingDurationSeconds = 300, averageGradePercent = -10.0, gradeBand = GradeBand.MODERATE_DOWN,
        )
        val descent = DescentPerformance(segment, averageSpeedKmh = 12.0, averageGradeAdjustedPaceMinPerKm = 5.0, averageHeartRateBpm = 140.0, averagePowerWatts = null)

        val efficiency = computeDownhillEfficiency(listOf(descent))

        assertEquals(1, efficiency.size)
        assertEquals(-600.0, efficiency[0].verticalSpeedMetersPerHour, 0.001)
        assertEquals(-4.2857, efficiency[0].metersPerHourPerBpm, 0.001)
    }

    // A 150m/200s climb averages 0.75 m/s throughout -- well under the 2.1
    // m/s walk-run threshold, so it's hike-dominant and eligible for the
    // hiking-only efficiency cut.
    @Test
    fun testComputeUphillHikingEfficiency_onlyHikeDominantClimbs() {
        val points = listOf(tp(0, 0.0, 0.0), tp(100, 75.0, 15.0), tp(200, 150.0, 30.0))
        val smoothed = points.map { SmoothedPoint(it, it.elevationM) }
        val segment = TerrainSegment(
            direction = SegmentDirection.UPHILL, startOffsetSeconds = 0, endOffsetSeconds = 200,
            startDistanceM = 0.0, endDistanceM = 150.0, elevationChangeM = 30.0,
            movingDurationSeconds = 200, averageGradePercent = 20.0, gradeBand = GradeBand.STEEP_UP,
        )
        val heartRate = listOf(TimePoint(0, 150.0), TimePoint(200, 160.0))

        val efficiency = computeUphillHikingEfficiency(listOf(segment), smoothed, heartRate)

        assertEquals(1, efficiency.size)
        assertEquals(540.0, efficiency[0].verticalSpeedMetersPerHour, 0.001)
        assertEquals(155.0, efficiency[0].averageHeartRateBpm, 0.001)
    }

    @Test
    fun testComputeUphillHikingEfficiency_runDominantClimbExcluded() {
        // Same climb, but covered fast (2.5 m/s throughout) -- running, not hiking.
        val points = listOf(tp(0, 0.0, 0.0), tp(60, 150.0, 30.0))
        val smoothed = points.map { SmoothedPoint(it, it.elevationM) }
        val segment = TerrainSegment(
            direction = SegmentDirection.UPHILL, startOffsetSeconds = 0, endOffsetSeconds = 60,
            startDistanceM = 0.0, endDistanceM = 150.0, elevationChangeM = 30.0,
            movingDurationSeconds = 60, averageGradePercent = 20.0, gradeBand = GradeBand.STEEP_UP,
        )
        val heartRate = listOf(TimePoint(0, 170.0))

        assertTrue(computeUphillHikingEfficiency(listOf(segment), smoothed, heartRate).isEmpty())
    }
}
