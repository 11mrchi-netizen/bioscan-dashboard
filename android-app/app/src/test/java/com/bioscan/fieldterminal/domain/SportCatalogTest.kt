package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class SportCatalogTest {
    private val today = LocalDate.of(2026, 11, 3) // a Tuesday

    private fun s(id: Long, type: String?, daysAgo: Long, min: Double = 60.0, km: Double? = null, source: String? = "health_connect") =
        SportSession(id, type, source, today.minusDays(daysAgo), min, km, null, null, null)

    @Test
    fun typesMapToSportsAndConditioningIsTheCoachsImport() {
        assertEquals("Running", sportOf("run", null).label)
        assertEquals(SportKind.CONDITIONING, sportOf("other", "notion").kind)
        assertEquals("Other workouts", sportOf("other", "zepp").label)
        assertEquals("Cycling", sportOf("ride", null).label)
        assertEquals("Padel", sportOf("padel", null).label) // a new type shows up by itself
    }

    @Test
    fun mainSportsComeFirstThenTheRestByRecency() {
        val sessions = listOf(
            s(1, "walk", 1), s(2, "ride", 40), s(3, "strength", 100), s(4, "run", 2, km = 10.0), s(5, "run", 30, km = 8.0),
            s(6, "other", 3, source = "notion"), s(7, "hike", 5),
        )
        val tiles = summarizeSports(sessions, today)
        assertEquals(listOf("run", "strength", "conditioning", "walk", "hike", "ride"), tiles.map { it.sport.key })
        val run = tiles.first()
        assertEquals(1, run.sessions4w)
        assertEquals(10.0, run.km4w!!, 1e-9)
        assertEquals(2, run.totalSessions)
        assertEquals(12, run.weeklyHours.size)
        // The run two days ago (Sunday) belongs to last week; this week (from Monday) is empty.
        assertEquals(0.0, run.weeklyHours.last(), 1e-9)
        assertEquals(1.0, run.weeklyHours[10], 1e-9)
        assertEquals(0, tiles.first { it.sport.key == "strength" }.sessions4w)
    }
}

class SportWindowTest {
    @org.junit.Test
    fun aBlockCountsOnlyItsOwnDays() {
        val from = java.time.LocalDate.of(2026, 11, 4) // a Wednesday
        val to = java.time.LocalDate.of(2026, 11, 17)
        val weeks = weekStarts(from, to)
        org.junit.Assert.assertEquals(listOf(java.time.LocalDate.of(2026, 11, 2), java.time.LocalDate.of(2026, 11, 9), java.time.LocalDate.of(2026, 11, 16)), weeks)
        val s = listOf(
            SportSession(1, "run", null, java.time.LocalDate.of(2026, 11, 3), 60.0, 10.0, null, null, null),
            SportSession(2, "run", null, java.time.LocalDate.of(2026, 11, 5), 30.0, 5.0, null, null, null),
        )
        val run = summarizeSportsIn(s, from, to, weeks).single()
        org.junit.Assert.assertEquals(1, run.sessions4w)
        org.junit.Assert.assertEquals(listOf(0.5, 0.0, 0.0), run.weeklyHours)
    }
}
