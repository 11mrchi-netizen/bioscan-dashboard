package com.bioscan.fieldterminal.domain.trail

import com.bioscan.fieldterminal.domain.RoutePoint
import com.bioscan.fieldterminal.domain.TimePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrailElevationProfileTest {

    private val routePoints = listOf(
        RoutePoint(offsetSeconds = 0, lat = 0.0, lon = 0.0, elevationM = 100.0),
        RoutePoint(offsetSeconds = 20, lat = 0.0001, lon = 0.0, elevationM = 100.0),
        RoutePoint(offsetSeconds = 40, lat = 0.0002, lon = 0.0, elevationM = 100.0),
    )

    @Test
    fun exactTimeMatchesOverlayCorrectly() {
        val heartRate = listOf(TimePoint(0, 140.0), TimePoint(20, 150.0), TimePoint(40, 160.0))

        val profile = elevationProfileWithOverlays(routePoints, heartRate, speedKmh = emptyList(), cadenceSpm = emptyList())

        assertEquals(3, profile.size)
        assertEquals(140.0, profile[0].heartRate!!, 0.001)
        assertEquals(150.0, profile[1].heartRate!!, 0.001)
        assertEquals(160.0, profile[2].heartRate!!, 0.001)
        profile.forEach { assertEquals(100.0, it.elevationM, 0.001) }
    }

    @Test
    fun distanceIsMonotonicallyIncreasing() {
        val profile = elevationProfileWithOverlays(routePoints, emptyList(), emptyList(), emptyList())
        assertTrue(profile[1].distanceM > profile[0].distanceM)
        assertTrue(profile[2].distanceM > profile[1].distanceM)
    }

    @Test
    fun samplesBeyondToleranceAreNullNotInterpolated() {
        // Every real speed sample is 1000s away from any route point -- far
        // outside the 30s match window, so pace must come back null rather
        // than snapping to a wildly distant reading.
        val speedKmh = listOf(TimePoint(1000, 20.0))
        val profile = elevationProfileWithOverlays(routePoints, emptyList(), speedKmh, emptyList())
        profile.forEach { assertNull(it.paceMinPerKm) }
    }

    @Test
    fun emptyOverlaySeriesGivesNullEverywhere() {
        val profile = elevationProfileWithOverlays(routePoints, emptyList(), emptyList(), emptyList())
        profile.forEach {
            assertNull(it.heartRate)
            assertNull(it.paceMinPerKm)
            assertNull(it.cadenceSpm)
        }
    }

    @Test
    fun paceIsDerivedFromMatchedSpeed() {
        // 12 km/h -> 5:00 /km.
        val speedKmh = listOf(TimePoint(0, 12.0), TimePoint(20, 12.0), TimePoint(40, 12.0))
        val profile = elevationProfileWithOverlays(routePoints, emptyList(), speedKmh, emptyList())
        profile.forEach { assertEquals(5.0, it.paceMinPerKm!!, 0.001) }
    }
}
