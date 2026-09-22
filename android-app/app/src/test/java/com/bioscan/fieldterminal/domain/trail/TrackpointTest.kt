package com.bioscan.fieldterminal.domain.trail

import com.bioscan.fieldterminal.domain.RoutePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackpointTest {

    // Hand-verified: 1 degree of latitude is ~111.19km at any longitude.
    @Test
    fun testHaversineMeters_oneDegreeLatitude() {
        val meters = haversineMeters(0.0, 0.0, 1.0, 0.0)
        assertEquals(111_195.0, meters, 500.0)
    }

    @Test
    fun testHaversineMeters_samePoint() {
        assertEquals(0.0, haversineMeters(45.0, 7.0, 45.0, 7.0), 0.001)
    }

    @Test
    fun testTrackpointsFromRoutePoints_accumulatesDistanceAndPreservesElevationNullability() {
        val points = listOf(
            RoutePoint(offsetSeconds = 0, lat = 0.0, lon = 0.0, elevationM = 100.0),
            RoutePoint(offsetSeconds = 60, lat = 0.0, lon = 0.0, elevationM = null),
            RoutePoint(offsetSeconds = 120, lat = 1.0, lon = 0.0, elevationM = 150.0),
        )

        val trackpoints = trackpointsFromRoutePoints(points)

        assertEquals(3, trackpoints.size)
        assertEquals(0.0, trackpoints[0].cumulativeDistanceM, 0.001)
        assertEquals(0.0, trackpoints[1].cumulativeDistanceM, 0.001)
        assertEquals(111_195.0, trackpoints[2].cumulativeDistanceM, 500.0)
        assertEquals(100.0, trackpoints[0].elevationM!!, 0.001)
        assertNull(trackpoints[1].elevationM)
    }
}
