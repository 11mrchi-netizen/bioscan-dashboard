package com.bioscan.fieldterminal.domain.trail

import com.bioscan.fieldterminal.domain.TimePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UphillPerformanceTest {

    private fun eligibleClimb(elevationChangeM: Double, movingDurationSeconds: Long, gradePercent: Double = 10.0) = TerrainSegment(
        direction = SegmentDirection.UPHILL,
        startOffsetSeconds = 0,
        endOffsetSeconds = movingDurationSeconds,
        startDistanceM = 0.0,
        endDistanceM = 500.0,
        elevationChangeM = elevationChangeM,
        movingDurationSeconds = movingDurationSeconds,
        averageGradePercent = gradePercent,
        gradeBand = GradeBand.MODERATE_UP,
    )

    @Test
    fun testMinettiCostOfTransport_flatGroundIsBaseConstant() {
        assertEquals(3.6, minettiCostOfTransport(0.0), 0.0001)
    }

    // Doc 02's own worked example: a 5% grade multiplier is ~1.29-1.30x, so
    // a 5:00/km actual pace reads as roughly a 3.84/km flat-equivalent GAP.
    @Test
    fun testGradeAdjustedPace_fivePercentGrade() {
        assertEquals(5.0, gradeAdjustedPaceMinPerKm(5.0, 0.0), 0.0001) // flat: no adjustment
        assertEquals(3.84, gradeAdjustedPaceMinPerKm(5.0, 5.0), 0.05)
    }

    // Hand-verified: 50m gain over 300s moving = 50 / (300/3600 h) = 600 m/h.
    // Speed 6 km/h -> pace 10 min/km.
    @Test
    fun testComputeUphillPerformance_vamAndGap() {
        val segment = eligibleClimb(elevationChangeM = 50.0, movingDurationSeconds = 300, gradePercent = 10.0)
        val heartRate = listOf(TimePoint(0, 140.0), TimePoint(150, 150.0), TimePoint(300, 160.0))
        val speed = listOf(TimePoint(0, 6.0), TimePoint(300, 6.0))

        val climbs = computeUphillPerformance(listOf(segment), heartRate, emptyList(), speed)

        assertEquals(1, climbs.size)
        assertEquals(600.0, climbs[0].vamMetersPerHour, 0.001)
        assertEquals(150.0, climbs[0].averageHeartRateBpm!!, 0.001)
        // pace 10 min/km at 10% grade -> GAP well below 10 (climbing costs more).
        val gap = climbs[0].averageGradeAdjustedPaceMinPerKm!!
        org.junit.Assert.assertTrue(gap < 10.0 && gap > 5.0)
    }

    // A short/brief climb (below doc 04's 100m/60s bar) never gets a
    // per-climb performance number, even though it's real terrain.
    @Test
    fun testComputeUphillPerformance_excludesIneligibleSegments() {
        val tooShort = TerrainSegment(
            direction = SegmentDirection.UPHILL, startOffsetSeconds = 0, endOffsetSeconds = 30,
            startDistanceM = 0.0, endDistanceM = 50.0, elevationChangeM = 10.0,
            movingDurationSeconds = 30, averageGradePercent = 20.0, gradeBand = GradeBand.STEEP_UP,
        )
        assertEquals(0, computeUphillPerformance(listOf(tooShort), emptyList(), emptyList(), emptyList()).size)
    }

    @Test
    fun testClimbConsistency() {
        val a = eligibleClimb(elevationChangeM = 50.0, movingDurationSeconds = 300) // VAM 600
        val b = eligibleClimb(elevationChangeM = 200.0 / 3.0, movingDurationSeconds = 300) // VAM 800

        val climbsSame = computeUphillPerformance(listOf(a, a), emptyList(), emptyList(), emptyList())
        assertEquals(0.0, climbConsistency(climbsSame)!!, 0.0001)

        val climbsDifferent = computeUphillPerformance(listOf(a, b), emptyList(), emptyList(), emptyList())
        assertEquals(0.1429, climbConsistency(climbsDifferent)!!, 0.001)

        assertNull(climbConsistency(listOf(computeUphillPerformance(listOf(a), emptyList(), emptyList(), emptyList())[0])))
    }
}
