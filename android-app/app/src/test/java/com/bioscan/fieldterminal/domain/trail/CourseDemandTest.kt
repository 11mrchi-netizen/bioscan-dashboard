package com.bioscan.fieldterminal.domain.trail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CourseDemandTest {

    private fun tp(offsetSeconds: Long, distanceM: Double, elevationM: Double?) =
        Trackpoint(offsetSeconds, lat = 0.0, lon = 0.0, elevationM = elevationM, cumulativeDistanceM = distanceM)

    // Same fixture as TerrainSegmentationTest's reversal case: climb 0->50m
    // over 500m, descend 50->-10m over 500m, 1000m total distance.
    // Hand-verified: gain=50, loss=60, mountainIndex=50/1.0km=50 m/km,
    // kmEffort=1.0km + 50/100 = 1.5.
    @Test
    fun testComputeCourseDemand_climbAndDescent() {
        val points = listOf(tp(0, 0.0, 0.0), tp(100, 250.0, 25.0), tp(200, 500.0, 50.0), tp(300, 750.0, 20.0), tp(400, 1000.0, -10.0))
        val smoothed = points.map { SmoothedPoint(it, it.elevationM) }
        val segments = segmentClimbsAndDescents(smoothed)

        val demand = computeCourseDemand(points, segments)

        assertEquals(1000.0, demand.totalDistanceM, 0.001)
        assertEquals(50.0, demand.elevationGainM!!, 0.001)
        assertEquals(60.0, demand.elevationLossM!!, 0.001)
        assertEquals(110.0, demand.totalVerticalM!!, 0.001)
        assertEquals(50.0, demand.mountainIndex!!, 0.001)
        assertEquals(1.5, demand.kmEffort!!, 0.001)
    }

    // A single point isn't a real route -- every derived figure is null,
    // never a fabricated 0.0 sum-of-nothing.
    @Test
    fun testComputeCourseDemand_noRealRouteGivesAllNull() {
        val points = listOf(tp(0, 0.0, 100.0))
        val demand = computeCourseDemand(points, emptyList())

        assertEquals(0.0, demand.totalDistanceM, 0.001)
        assertNull(demand.elevationGainM)
        assertNull(demand.elevationLossM)
        assertNull(demand.totalVerticalM)
        assertNull(demand.mountainIndex)
        assertNull(demand.kmEffort)
    }

    // Text book example from doc 02's own source: a 50K with 3,000m of gain
    // reads Mountain Index 60.
    @Test
    fun testComputeCourseDemand_docExample() {
        val points = listOf(tp(0, 0.0, 0.0), tp(1, 50_000.0, 3_000.0))
        val segments = listOf(
            TerrainSegment(
                direction = SegmentDirection.UPHILL,
                startOffsetSeconds = 0,
                endOffsetSeconds = 1,
                startDistanceM = 0.0,
                endDistanceM = 50_000.0,
                elevationChangeM = 3_000.0,
                movingDurationSeconds = 1,
                averageGradePercent = 6.0,
                gradeBand = GradeBand.GENTLE_UP,
            ),
        )

        val demand = computeCourseDemand(points, segments)

        assertEquals(60.0, demand.mountainIndex!!, 0.001)
    }
}
