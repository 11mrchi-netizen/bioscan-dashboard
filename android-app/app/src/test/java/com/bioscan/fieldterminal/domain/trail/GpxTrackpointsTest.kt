package com.bioscan.fieldterminal.domain.trail

import org.junit.Assert.assertEquals
import org.junit.Test

class GpxTrackpointsTest {

    // Hand-verified: 0.001 degrees of latitude ~= 111.2m (1 degree ~=
    // 111,195m); 60 real seconds between the two <time> values.
    @Test
    fun testTrackpointsFromGpx_realTimestampsGiveRealOffsets() {
        val xml = """
            <?xml version="1.0"?>
            <gpx><trk><trkseg>
              <trkpt lat="0.0" lon="0.0"><time>2026-01-01T00:00:00Z</time></trkpt>
              <trkpt lat="0.001" lon="0.0"><time>2026-01-01T00:01:00Z</time></trkpt>
            </trkseg></trk></gpx>
        """.trimIndent()

        val trackpoints = trackpointsFromGpx(xml)

        assertEquals(2, trackpoints.size)
        assertEquals(0L, trackpoints[0].offsetSeconds)
        assertEquals(60L, trackpoints[1].offsetSeconds)
        assertEquals(111.2, trackpoints[1].cumulativeDistanceM, 0.5)
    }

    // A planned route's <rtept> list commonly has no <time> at all --
    // offsetSeconds falls back to a synthetic 0,1,2... index rather than a
    // fabricated real-looking duration.
    @Test
    fun testTrackpointsFromGpx_noTimestampsFallsBackToSequentialIndex() {
        val xml = """
            <?xml version="1.0"?>
            <gpx><rte>
              <rtept lat="0.0" lon="0.0"></rtept>
              <rtept lat="0.001" lon="0.0"></rtept>
              <rtept lat="0.002" lon="0.0"></rtept>
            </rte></gpx>
        """.trimIndent()

        val trackpoints = trackpointsFromGpx(xml)

        assertEquals(listOf(0L, 1L, 2L), trackpoints.map { it.offsetSeconds })
        assertEquals(222.4, trackpoints[2].cumulativeDistanceM, 1.0)
    }
}
