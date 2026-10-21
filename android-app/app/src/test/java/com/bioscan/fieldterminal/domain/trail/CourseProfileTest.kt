package com.bioscan.fieldterminal.domain.trail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CourseProfileTest {
    // A synthetic 2 km course: 1 km climbing 100 m, then 1 km back down, a point every 10 m.
    private val points = (0..200).map { i ->
        val d = i * 10.0
        val e = if (d <= 1000) 500 + d / 10 else 600 - (d - 1000) / 10
        Trackpoint(offsetSeconds = i.toLong(), lat = 45.0 + i * 1e-5, lon = 9.0, elevationM = e, cumulativeDistanceM = d)
    }

    @Test
    fun theProfileIsSampledEveryHundredMetresAndEndsWithTheCourse() {
        val profile = courseProfileFromTrackpoints(points)
        assertEquals(21, profile.points.size)
        assertEquals(0.0, profile.points.first().distanceM, 0.0)
        assertEquals(2000.0, profile.points.last().distanceM, 0.0)
        assertEquals(2000.0, profile.distanceM, 0.0)
        assertEquals(45.0, profile.startLat!!, 0.0)
    }

    @Test
    fun theElevationFollowsTheSmoothedCourse() {
        val profile = courseProfileFromTrackpoints(points)
        assertEquals(550.0, profile.points[5].elevationM!!, 1.0)
        assertTrue(profile.points.maxOf { it.elevationM!! } in 595.0..600.0)
        assertTrue((profile.gainM ?: 0.0) in 90.0..100.0)
    }

    @Test
    fun aProfileRebuiltAsTrackpointsKeepsDistanceAndElevation() {
        val back = trackpointsFromCourse(courseProfileFromTrackpoints(points).points)
        assertEquals(2000.0, back.last().cumulativeDistanceM, 0.0)
        assertEquals(21, back.size)
    }
}
