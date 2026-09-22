package com.bioscan.fieldterminal.domain.trail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocomotionModeTest {

    private fun tp(offsetSeconds: Long, distanceM: Double, elevationM: Double?) =
        Trackpoint(offsetSeconds, lat = 0.0, lon = 0.0, elevationM = elevationM, cumulativeDistanceM = distanceM)

    // Interval 1: 250m/100s = 2.5 m/s (RUN), uphill (0->10m).
    // Interval 2: 50m/100s = 0.5 m/s (HIKE), uphill (10->15m).
    // Interval 3: 250m/100s = 2.5 m/s (RUN), flat (15->15m, not uphill).
    @Test
    fun testClassifyLocomotion_splitsAndCountsTransitions() {
        val points = listOf(tp(0, 0.0, 0.0), tp(100, 250.0, 10.0), tp(200, 300.0, 15.0), tp(300, 550.0, 15.0))
        val smoothed = points.map { SmoothedPoint(it, it.elevationM) }

        val split = classifyLocomotion(smoothed)

        assertEquals(500.0, split.runDistanceM, 0.001)
        assertEquals(200L, split.runDurationSeconds)
        assertEquals(50.0, split.hikeDistanceM, 0.001)
        assertEquals(100L, split.hikeDurationSeconds)
        assertEquals(250.0, split.uphillRunDistanceM, 0.001)
        assertEquals(50.0, split.uphillHikeDistanceM, 0.001)
        assertEquals(2, split.transitionCount)
        assertEquals(83.333, split.uphillRunPercent!!, 0.01)
        assertEquals(16.667, split.uphillHikePercent!!, 0.01)
    }

    @Test
    fun testClassifyLocomotion_noUphillGivesNullPercent() {
        val points = listOf(tp(0, 0.0, 10.0), tp(100, 250.0, 10.0))
        val smoothed = points.map { SmoothedPoint(it, it.elevationM) }
        val split = classifyLocomotion(smoothed)
        assertNull(split.uphillRunPercent)
        assertNull(split.uphillHikePercent)
    }
}
