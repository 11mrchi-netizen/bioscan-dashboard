package com.bioscan.fieldterminal.domain.trail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GradeDistributionTest {

    private fun tp(offsetSeconds: Long, distanceM: Double, elevationM: Double?) =
        Trackpoint(offsetSeconds, lat = 0.0, lon = 0.0, elevationM = elevationM, cumulativeDistanceM = distanceM)

    // Interval 1: 0->5m over 100m/100s -- grade 5% (GENTLE_UP), moving.
    // Interval 2: 5->5.5m over 100m/100s -- grade 0.5% (FLAT), moving.
    @Test
    fun testGradeBandDistribution_classifiesEachInterval() {
        val points = listOf(tp(0, 0.0, 0.0), tp(100, 100.0, 5.0), tp(200, 200.0, 5.5))
        val smoothed = points.map { SmoothedPoint(it, it.elevationM) }

        val distribution = gradeBandDistribution(smoothed)

        assertEquals(100.0, distribution.getValue(GradeBand.GENTLE_UP).distanceM, 0.001)
        assertEquals(100L, distribution.getValue(GradeBand.GENTLE_UP).movingDurationSeconds)
        assertEquals(100.0, distribution.getValue(GradeBand.FLAT).distanceM, 0.001)
        assertEquals(100L, distribution.getValue(GradeBand.FLAT).movingDurationSeconds)
    }

    // A near-stationary interval (5m over 100s = 0.05 m/s, under the 0.3
    // stopped threshold) still counts its distance toward the band, but not
    // its time.
    @Test
    fun testGradeBandDistribution_stoppedIntervalExcludesDurationOnly() {
        val points = listOf(tp(0, 0.0, 0.0), tp(100, 5.0, 0.0))
        val smoothed = points.map { SmoothedPoint(it, it.elevationM) }

        val distribution = gradeBandDistribution(smoothed)

        assertEquals(5.0, distribution.getValue(GradeBand.FLAT).distanceM, 0.001)
        assertEquals(0L, distribution.getValue(GradeBand.FLAT).movingDurationSeconds)
    }

    // Same reversal fixture as TerrainSegmentationTest/CourseDemandTest:
    // one 500m/+50m climb, one 500m/-60m descent, 1000m total session.
    @Test
    fun testComputeClimbDescentStructure() {
        val points = listOf(tp(0, 0.0, 0.0), tp(100, 250.0, 25.0), tp(200, 500.0, 50.0), tp(300, 750.0, 20.0), tp(400, 1000.0, -10.0))
        val smoothed = points.map { SmoothedPoint(it, it.elevationM) }
        val segments = segmentClimbsAndDescents(smoothed)

        val structure = computeClimbDescentStructure(segments, totalDistanceM = 1000.0)

        assertEquals(1, structure.uphill.segmentCount)
        assertEquals(500.0, structure.uphill.totalDistanceM, 0.001)
        assertEquals(500.0, structure.uphill.averageDistanceM!!, 0.001)
        assertEquals(500.0, structure.uphill.maxDistanceM!!, 0.001)
        assertEquals(50.0, structure.uphill.totalElevationChangeM, 0.001)
        assertEquals(0.5, structure.uphill.distanceShareOfTotal!!, 0.001)

        assertEquals(1, structure.downhill.segmentCount)
        assertEquals(-60.0, structure.downhill.totalElevationChangeM, 0.001)
        assertEquals(0.5, structure.downhill.distanceShareOfTotal!!, 0.001)
    }

    @Test
    fun testComputeClimbDescentStructure_noSegmentsGivesNullAverages() {
        val structure = computeClimbDescentStructure(emptyList(), totalDistanceM = 1000.0)
        assertEquals(0, structure.uphill.segmentCount)
        assertNull(structure.uphill.averageDistanceM)
        assertNull(structure.uphill.maxDistanceM)
    }
}
