package com.bioscan.fieldterminal.domain.trail

import com.bioscan.fieldterminal.domain.RoutePoint
import com.bioscan.fieldterminal.domain.routeKmMarkers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GradeTimeSplitTest {
    // A synthetic out-and-back: 10 min climbing at 10 %, 10 min flat, 10 min descending at 10 %, 3 m per second along a
    // meridian (1 degree of latitude is about 111,195 m).
    private fun leg(startSec: Long, seconds: Int, startLat: Double, startElev: Double, gradePct: Double): List<RoutePoint> =
        (0 until seconds).map { s ->
            val meters = s * 3.0
            RoutePoint(startSec + s, startLat + meters / 111_195.0, 121.0, startElev + meters * gradePct / 100)
        }

    @Test
    fun climbFlatAndDescentShareTheTime() {
        val up = leg(0, 600, 25.0, 100.0, 10.0)
        val flat = leg(600, 600, up.last().lat + 3 / 111_195.0, up.last().elevationM!!, 0.0)
        val down = leg(1200, 600, flat.last().lat + 3 / 111_195.0, flat.last().elevationM!!, -10.0)
        val split = gradeTimeSplit(up + flat + down)!!
        assertEquals(1.0 / 3, split.share(split.uphillSec), 0.05)
        assertEquals(1.0 / 3, split.share(split.flatSec), 0.05)
        assertEquals(1.0 / 3, split.share(split.downhillSec), 0.05)
    }

    @Test
    fun aPauseCountsNoTime() {
        val first = leg(0, 300, 25.0, 100.0, 0.0)
        val after = leg(1500, 300, first.last().lat + 3 / 111_195.0, 100.0, 0.0) // 1,200 s standing still
        val split = gradeTimeSplit(first + after)!!
        assertEquals(598.0, split.totalSec.toDouble(), 10.0)
    }

    @Test
    fun noElevationNoSplit() {
        assertNull(gradeTimeSplit(listOf(RoutePoint(0, 25.0, 121.0, null), RoutePoint(1, 25.0001, 121.0, null))))
    }

    @Test
    fun kmMarkersFallOnEachWholeKilometre() {
        val track = leg(0, 1000, 25.0, 100.0, 0.0) // 2,997 m
        val markers = routeKmMarkers(track)
        assertEquals(listOf(1, 2), markers.map { it.km })
        assertEquals(25.0 + 1000 / 111_195.0, markers[0].lat, 1e-5)
        assertEquals(25.0 + 2000 / 111_195.0, markers[1].lat, 1e-5)
    }
}
