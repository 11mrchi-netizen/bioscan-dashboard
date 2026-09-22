package com.bioscan.fieldterminal.domain.trail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerrainSegmentationTest {

    private fun tp(offsetSeconds: Long, distanceM: Double, elevationM: Double?) =
        Trackpoint(offsetSeconds, lat = 0.0, lon = 0.0, elevationM = elevationM, cumulativeDistanceM = distanceM)

    // Hand-verified 5-point moving average: window at index 2 covers indices
    // 0-4 -> (100+102+104+106+108)/5 = 104.0; boundary index 0 covers only
    // indices 0-2 (half-window clipped) -> (100+102+104)/3 = 102.0.
    @Test
    fun testSmoothElevation_movingAverage() {
        val points = listOf(100.0, 102.0, 104.0, 106.0, 108.0, 110.0)
            .mapIndexed { i, e -> tp(i * 60L, i * 200.0, e) }

        val smoothed = smoothElevation(points)

        assertEquals(102.0, smoothed[0].smoothedElevationM!!, 0.001)
        assertEquals(104.0, smoothed[2].smoothedElevationM!!, 0.001)
    }

    @Test
    fun testSmoothElevation_missingElevationStaysNull() {
        val points = listOf(tp(0, 0.0, 100.0), tp(60, 200.0, null), tp(120, 400.0, 104.0))
        val smoothed = smoothElevation(points)
        assertNull(smoothed[1].smoothedElevationM)
    }

    // A single climb with a 5m dip mid-climb (below the 10m noise threshold)
    // must NOT split into two segments -- the dip is absorbed as noise.
    // 0m -> 50m over 800m (points 0-4), dip to 45m (point 5, ignored), then
    // on to 70m over the full 1200m (point 6). Net: one UPHILL segment,
    // 0 -> 70m over 1200m = 5.833% grade (GENTLE_UP).
    @Test
    fun testSegmentClimbsAndDescents_absorbsNoiseWithinThreshold() {
        val raw = listOf(0.0, 15.0, 30.0, 40.0, 50.0, 45.0, 70.0)
        val points = raw.mapIndexed { i, e -> tp(i * 60L, i * 200.0, e) }
        val smoothed = points.map { SmoothedPoint(it, it.elevationM) } // pre-smoothed, isolates the walk itself

        val segments = segmentClimbsAndDescents(smoothed)

        assertEquals(1, segments.size)
        val segment = segments[0]
        assertEquals(SegmentDirection.UPHILL, segment.direction)
        assertEquals(0.0, segment.startDistanceM, 0.001)
        assertEquals(1200.0, segment.endDistanceM, 0.001)
        assertEquals(70.0, segment.elevationChangeM, 0.001)
        assertEquals(5.8333, segment.averageGradePercent, 0.001)
        assertEquals(GradeBand.GENTLE_UP, segment.gradeBand)
        assertEquals(360L, segment.movingDurationSeconds)
    }

    // A real reversal (30m pullback, over the 10m threshold) splits into two
    // segments: 0->50m climb over 500m (10% = MODERATE_UP), then 50->-10m
    // descent over 500m (-12% = MODERATE_DOWN).
    @Test
    fun testSegmentClimbsAndDescents_realReversalSplitsSegments() {
        val points = listOf(
            tp(0, 0.0, 0.0),
            tp(100, 250.0, 25.0),
            tp(200, 500.0, 50.0),
            tp(300, 750.0, 20.0),
            tp(400, 1000.0, -10.0),
        )
        val smoothed = points.map { SmoothedPoint(it, it.elevationM) }

        val segments = segmentClimbsAndDescents(smoothed)

        assertEquals(2, segments.size)
        val climb = segments[0]
        assertEquals(SegmentDirection.UPHILL, climb.direction)
        assertEquals(50.0, climb.elevationChangeM, 0.001)
        assertEquals(10.0, climb.averageGradePercent, 0.001)
        assertEquals(GradeBand.MODERATE_UP, climb.gradeBand)
        assertEquals(200L, climb.movingDurationSeconds)

        val descent = segments[1]
        assertEquals(SegmentDirection.DOWNHILL, descent.direction)
        assertEquals(-60.0, descent.elevationChangeM, 0.001)
        assertEquals(-12.0, descent.averageGradePercent, 0.001)
        assertEquals(GradeBand.MODERATE_DOWN, descent.gradeBand)

        assertEquals(50.0, totalElevationGainM(segments), 0.001)
        assertEquals(60.0, totalElevationLossM(segments), 0.001)
    }

    // A stopped interval (near-zero distance over real time, implied speed
    // under STOPPED_SPEED_MPS) must not count toward moving duration.
    @Test
    fun testSegmentClimbsAndDescents_excludesStoppedTimeFromDuration() {
        val points = listOf(
            tp(0, 0.0, 0.0),
            tp(60, 200.0, 20.0), // moving: 200m/60s
            tp(360, 205.0, 20.0), // "stopped": 5m/300s = 0.017 m/s, well under 0.3
            tp(420, 405.0, 20.0), // moving again: 200m/60s
        )
        val smoothed = points.map { SmoothedPoint(it, it.elevationM) }

        val segments = segmentClimbsAndDescents(smoothed)

        assertEquals(1, segments.size)
        // Total elapsed 420s, but the 300s stopped interval is excluded.
        assertEquals(120L, segments[0].movingDurationSeconds)
    }

    // Net elevation change under the 10m threshold for the whole session ->
    // no segment at all, not a fabricated flat segment.
    @Test
    fun testSegmentClimbsAndDescents_flatSessionProducesNoSegments() {
        val raw = listOf(100.0, 103.0, 101.0, 104.0, 102.0)
        val points = raw.mapIndexed { i, e -> tp(i * 60L, i * 200.0, e) }
        val smoothed = points.map { SmoothedPoint(it, it.elevationM) }

        assertTrue(segmentClimbsAndDescents(smoothed).isEmpty())
    }

    @Test
    fun testGradeBandFor_boundaries() {
        assertEquals(GradeBand.FLAT, gradeBandFor(0.0))
        assertEquals(GradeBand.FLAT, gradeBandFor(-3.0))
        assertEquals(GradeBand.GENTLE_UP, gradeBandFor(3.1))
        assertEquals(GradeBand.STEEP_UP, gradeBandFor(20.0))
        assertEquals(GradeBand.STEEP_DOWN, gradeBandFor(-20.0))
    }
}
