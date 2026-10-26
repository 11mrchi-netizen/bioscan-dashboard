package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionDetailTest {

    @Test
    fun testTwoEvenKmSplitsWithHeartRate() {
        // 0->1km in 300s, 1->2km in 330s -- evenly-sampled distance, real
        // heart-rate samples split cleanly across the two windows.
        val distance = listOf(TimePoint(0, 0.0), TimePoint(300, 1.0), TimePoint(630, 2.0))
        val heartRate = listOf(TimePoint(100, 140.0), TimePoint(200, 150.0), TimePoint(500, 160.0))
        val splits = computeKmSplits(distance, heartRate)
        assertEquals(2, splits.size)
        assertEquals(1, splits[0].km)
        assertEquals(300L, splits[0].durationSec)
        assertEquals(145.0, splits[0].avgHr!!, 0.01) // 140, 150 both before 300s
        assertEquals(2, splits[1].km)
        assertEquals(330L, splits[1].durationSec)
        assertEquals(160.0, splits[1].avgHr!!, 0.01) // only 160 falls in 300..630
    }

    @Test
    fun testCurrentPartialKmIsNotEmittedAsASplit() {
        // 1.6km covered -- one completed split, the trailing 0.6km in
        // progress is left off rather than shown as a fake short split.
        val distance = listOf(TimePoint(0, 0.0), TimePoint(240, 1.0), TimePoint(384, 1.6))
        val splits = computeKmSplits(distance, emptyList())
        assertEquals(1, splits.size)
        assertEquals(240L, splits[0].durationSec)
    }

    // Run session page: a 600 s pause inside km 2 leaves its moving pace alone and shows the clock time beside it.
    @Test
    fun testAPauseIsLeftOutOfTheKmItFallsIn() {
        val distance = listOf(TimePoint(0, 0.0), TimePoint(300, 1.0), TimePoint(450, 1.5), TimePoint(1050, 1.5), TimePoint(1200, 2.0))
        val pauses = listOf(SessionPause(startOffset = 450, durationSec = 600))
        val splits = computeKmSplits(distance, emptyList(), pauses)
        assertEquals(300L, splits[0].durationSec)
        assertEquals(null, splits[0].elapsedSec)
        assertEquals(300L, splits[1].durationSec)
        assertEquals(900L, splits[1].elapsedSec)
    }

    @Test
    fun testPausedSecondsBetween() {
        val pauses = listOf(SessionPause(100, 50), SessionPause(400, 100))
        assertEquals(150L, pausedSecondsBetween(pauses, 0, 1000))
        assertEquals(25L, pausedSecondsBetween(pauses, 125, 300))
        assertEquals(0L, pausedSecondsBetween(pauses, 160, 390))
    }

    @Test
    fun testMovingOffsetClosesThePauses() {
        // Pauses 100-150 and 400-500: the run continues straight on through each.
        val pauses = listOf(100L to 150L, 400L to 500L)
        assertEquals(90L, movingOffset(90, pauses))
        assertEquals(100L, movingOffset(150, pauses)) // the end of the first pause lands where it began
        assertEquals(100L, movingOffset(120, pauses)) // a moment inside it sits at its start
        assertEquals(350L, movingOffset(400, pauses))
        assertEquals(400L, movingOffset(550, pauses))
        assertEquals(550L, movingOffset(550, emptyList()))
    }

    @Test
    fun testUnderOneKmProducesNoSplits() {
        assertTrue(computeKmSplits(listOf(TimePoint(0, 0.0), TimePoint(200, 0.8)), emptyList()).isEmpty())
    }

    @Test
    fun testInterpolatesAcrossASparseDistanceSample() {
        // A single real interval spanning 0->2km over 600s -- the 1km
        // boundary is linearly interpolated at the midpoint, 300s.
        val distance = listOf(TimePoint(0, 0.0), TimePoint(600, 2.0))
        val splits = computeKmSplits(distance, emptyList())
        assertEquals(2, splits.size)
        assertEquals(300L, splits[0].durationSec)
        assertEquals(300L, splits[1].durationSec)
    }

    @Test
    fun averageSpeedIsDistanceOverMovingTime() {
        // A stored speed is ignored when there is a distance and a duration: 7.4 km in 41 min is 10.8 km/h whatever the
        // stored mean of the speed samples says.
        org.junit.Assert.assertEquals(10.83, sessionAvgSpeedKmh("run", 7.09, 7.4, 41.0)!!, 0.01)
        org.junit.Assert.assertEquals(9.97, sessionAvgSpeedKmh("run", 9944.7, 9.97, 60.0)!!, 0.01)
        // A pause comes out: the shorter of the stored duration and the workout's moving time.
        org.junit.Assert.assertEquals(12.0, sessionAvgSpeedKmh("run", null, 10.0, 60.0, movingMin = 50.0)!!, 1e-9)
        org.junit.Assert.assertEquals(12.0, sessionAvgSpeedKmh("run", null, 10.0, 50.0, movingMin = 55.0)!!, 1e-9)
        // Without a distance the stored value is the fallback, when believable for the type.
        org.junit.Assert.assertEquals(9.4, sessionAvgSpeedKmh("run", 9.4, null, 81.0)!!, 1e-9)
        org.junit.Assert.assertNull(sessionAvgSpeedKmh("run", 9944.7, null, 60.0))
        org.junit.Assert.assertEquals(45.0, sessionAvgSpeedKmh("ride", 45.0, 30.0, 40.0)!!, 1e-9)
    }

    @Test
    fun movingMinutesLeaveThePausesOut() {
        val hr = (0..3600L step 60).map { TimePoint(it, 140.0) }
        val d = SessionDetail(hr, emptyList(), emptyList(), emptyList(), emptyList(), pauses = listOf(SessionPause(1200, 600)))
        org.junit.Assert.assertEquals(50.0, d.movingMinutes()!!, 1e-9)
        org.junit.Assert.assertNull(SessionDetail(emptyList(), emptyList(), emptyList(), emptyList(), emptyList()).movingMinutes())
    }
}
