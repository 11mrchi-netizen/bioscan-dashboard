package com.bioscan.fieldterminal.domain.trail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlannedRoutePreviewTest {

    // 21 rtept points, ~50m apart, climbing 0->50m over the first 10 then
    // descending 50->-10m over the next 10 -- same shape as
    // TrailSessionStateTest's Health-Connect fixture, expressed as a planned
    // (untimed) GPX route instead. Long enough that DAV-134's real 5-point
    // moving average doesn't flatten the whole profile (that was this
    // milestone's own first bug, caught writing TrailSessionStateTest).
    private val climbAndDescentGpx = buildString {
        append("<?xml version=\"1.0\"?><gpx><rte>")
        for (i in 0..20) {
            val elevation = if (i <= 10) 5.0 * i else 50.0 - 6.0 * (i - 10)
            val lat = i * 0.00044966
            append("<rtept lat=\"$lat\" lon=\"0.0\"><ele>$elevation</ele></rtept>")
        }
        append("</rte></gpx>")
    }

    @Test
    fun testComputePlannedRoutePreview_realGpxProducesCourseDemandOnly() {
        val preview = computePlannedRoutePreview(climbAndDescentGpx)

        assertEquals("full", preview.confidenceTier)
        assertNotEquals("", preview.gpxChecksum)

        // Loose bounds, matching TrailSessionStateTest's own philosophy --
        // this test verifies wiring through the real smoothing pipeline
        // (unlike CourseDemandTest's exact-value fixtures using
        // pre-smoothed literals), not a re-verification of the formulas.
        assertTrue(preview.courseDemand.mountainIndex!! in 30.0..60.0)
        assertTrue(preview.courseDemand.elevationGainM!! in 35.0..55.0)
        assertTrue(preview.courseDemand.elevationLossM!! in 45.0..65.0)

        // Course demand only -- no VAM/GAP/durability in this shape at all.
        val distributionTotal = preview.gradeDistributionByBand.values.sum()
        assertTrue(distributionTotal > 900.0) // ~1000m route, minus a couple of null-elevation edge intervals
    }

    @Test
    fun testComputePlannedRoutePreview_sameGpxGivesSameChecksum() {
        val a = computePlannedRoutePreview(climbAndDescentGpx)
        val b = computePlannedRoutePreview(climbAndDescentGpx)
        assertEquals(a.gpxChecksum, b.gpxChecksum)
    }

    @Test
    fun testComputePlannedRoutePreview_noRealRouteIsMinimalConfidence() {
        val preview = computePlannedRoutePreview("<?xml version=\"1.0\"?><gpx><rte><rtept lat=\"0.0\" lon=\"0.0\"></rtept></rte></gpx>")
        assertEquals("minimal", preview.confidenceTier)
    }
}
