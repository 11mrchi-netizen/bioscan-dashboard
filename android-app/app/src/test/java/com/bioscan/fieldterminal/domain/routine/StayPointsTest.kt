package com.bioscan.fieldterminal.domain.routine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Routine intelligence R2: stays from fixes, and a silent collector never passes for a stay.
class StayPointsTest {
    private val min = 60_000L
    private val home = 25.0330 to 121.5654
    private val office = 25.0478 to 121.5170 // about 5 km away

    private fun at(minute: Long, p: Pair<Double, Double>, jitterM: Double = 0.0, acc: Double? = 10.0) =
        Fix(minute * min, p.first + jitterM / 111_000.0, p.second, acc)

    @Test
    fun haversineKnownDistance() {
        // one degree of latitude is about 111.2 km
        assertEquals(111_195.0, haversineM(0.0, 0.0, 1.0, 0.0), 50.0)
    }

    @Test
    fun aTripBetweenTwoStaysGivesTwoStays() {
        val fixes = listOf(at(0, home), at(60, home, 20.0), at(120, home, -30.0)) + // home with hourly fixes
            (1..19).map { k -> Fix((120 + k) * min, home.first + k * 0.0007, home.second - k * 0.0024, 15.0) } + // the trip
            listOf(at(141, office), at(201, office, 25.0), at(261, office))
        val stays = detectStays(fixes)
        assertEquals(2, stays.size)
        assertEquals(0L, stays[0].startTs)
        assertEquals(120 * min, stays[0].endTs)
        assertFalse(stays[0].open)
        assertEquals(1.0, stays[0].confidence, 1e-9)
        assertTrue(stays[1].open)
        assertTrue(haversineM(stays[1].lat, stays[1].lon, office.first, office.second) < 30)
    }

    @Test
    fun shortStopsAreNotStays() {
        val fixes = listOf(at(0, home), at(5, home), at(9, home), at(30, office), at(31, office))
        assertTrue(detectStays(fixes).isEmpty())
    }

    @Test
    fun aSilentCollectorLowersConfidence() {
        // arrived at 0, nothing for 10 hours (no fixes, no heartbeats), then a fix in the same place
        val silent = detectStays(listOf(at(0, home), at(600, home))).single()
        assertEquals(1 - (600 - 90) / 600.0, silent.confidence, 1e-9)
        // the same stay with hourly heartbeats is fully covered
        val alive = detectStays(listOf(at(0, home), at(600, home)), heartbeats = (1..9).map { it * 60 * min }).single()
        assertEquals(1.0, alive.confidence, 1e-9)
    }

    @Test
    fun inaccurateFixesAreIgnored() {
        val fixes = listOf(at(0, home), at(30, office, acc = 1500.0), at(60, home))
        val stay = detectStays(fixes).single()
        assertEquals(2, stay.nFixes)
        assertEquals(60 * min, stay.endTs)
    }

    @Test
    fun aliveShareCountsOnlyTheExcessOfLongGaps() {
        assertEquals(1.0, aliveShare(0, 100, listOf(50), 60), 1e-9)
        assertEquals(1 - 80 / 200.0, aliveShare(0, 200, listOf(100), 60), 1e-9)
    }

    @Test
    fun aTripSettlesOnlyAfterTenQuietMinutes() {
        val quiet = (0..10).map { at(it.toLong(), office, jitterM = (it % 3) * 10.0) }
        assertTrue(isSettled(quiet, now = 10 * min))
        // still moving: the first fixes of the window are 400 m away
        val moving = (0..10).map { at(it.toLong(), office, jitterM = if (it < 3) 400.0 else 0.0) }
        assertFalse(isSettled(moving, now = 10 * min))
        // too little history to tell
        assertFalse(isSettled(quiet.takeLast(4), now = 10 * min))
    }
}
