package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.data.model.LogExerciseRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.LocalDateTime

class LogTest {

    // -------------------------------------------------------------------------
    // dedupeLogExerciseSessions
    // -------------------------------------------------------------------------

    private fun run(
        id: Long,
        startTime: String,
        durationMin: Double?,
        distanceKm: Double? = null,
        avgHr: Double? = null,
        source: String? = null,
    ) = LogExerciseRow(id = id, type = "run", startTime = startTime, durationMin = durationMin, distanceKm = distanceKm, avgHr = avgHr, source = source)

    @Test fun testDedupeEmpty() {
        assertEquals(emptyList<LogExerciseRow>(), dedupeLogExerciseSessions(emptyList()))
    }

    @Test fun testDedupeSingleSession() {
        val session = run(1, "2026-09-19T07:00:00+00:00", 45.0)
        assertEquals(listOf(session), dedupeLogExerciseSessions(listOf(session)))
    }

    @Test fun testDedupeIdenticalDuplicatePrefersRichest() {
        // Two sessions at the same time — one has HR, one doesn't.
        val withHr = run(1, "2026-09-19T07:00:00+00:00", 45.0, distanceKm = 8.0, avgHr = 145.0, source = "com.garmin")
        val noHr = run(2, "2026-09-19T07:00:00+00:00", 45.0, distanceKm = 8.0, source = "com.zepp")
        val result = dedupeLogExerciseSessions(listOf(withHr, noHr))
        assertEquals(1, result.size)
        assertEquals(withHr.id, result[0].id)
    }

    @Test fun testDedupeDistinctSameDay() {
        val morning = run(1, "2026-09-19T07:00:00+00:00", 30.0)
        val evening = run(2, "2026-09-19T18:00:00+00:00", 40.0)
        val result = dedupeLogExerciseSessions(listOf(morning, evening))
        assertEquals(2, result.size)
    }

    @Test fun testDedupeWithin15MinStartTolerance() {
        // Sessions 10 min apart with same duration → same real workout
        val a = run(1, "2026-09-19T07:00:00+00:00", 45.0, distanceKm = 10.0)
        val b = run(2, "2026-09-19T07:10:00+00:00", 45.0, distanceKm = 10.0, avgHr = 160.0)
        val result = dedupeLogExerciseSessions(listOf(a, b))
        assertEquals(1, result.size)
    }

    @Test fun testNoDedupeBeyond15MinStartTolerance() {
        val a = run(1, "2026-09-19T07:00:00+00:00", 45.0)
        val b = run(2, "2026-09-19T07:20:00+00:00", 45.0)
        val result = dedupeLogExerciseSessions(listOf(a, b))
        assertEquals(2, result.size)
    }

    @Test fun testDedupeManualSourceWins() {
        // dedup winner priority: manual source > avgHr > distanceKm > durationMin
        val manual = run(1, "2026-09-19T07:00:00+00:00", 30.0, distanceKm = 5.0, source = "manual")
        val device = run(2, "2026-09-19T07:05:00+00:00", 30.0, distanceKm = 8.0, avgHr = 150.0, source = "com.zepp")
        val result = dedupeLogExerciseSessions(listOf(manual, device))
        assertEquals(1, result.size)
        assertEquals(manual.id, result[0].id)
    }

    // -------------------------------------------------------------------------
    // buildLogEntries — parseTimestamp fallback
    // -------------------------------------------------------------------------

    @Test fun testBuildLogEntries_malformedTimestampDoesNotCrash() {
        // A meal with a malformed logged_at string should not throw; the entry
        // gets LocalDateTime.MIN and still appears in the output.
        val meals = listOf(
            com.bioscan.fieldterminal.data.model.LogMealRow(
                id = 99,
                loggedAt = "not-a-date",
                description = "Bad entry",
                calories = 100.0,
            )
        )
        val entries = buildLogEntries(meals = meals, exerciseSessions = emptyList(), sleep = emptyList(), arousal = emptyList(), stool = emptyList(), encounters = emptyList())
        assertEquals(1, entries.size)
        assertEquals(LocalDateTime.MIN, entries[0].timestamp)
    }

    @Test fun testBuildLogEntries_isoOffsetTimestampParsed() {
        val meals = listOf(
            com.bioscan.fieldterminal.data.model.LogMealRow(
                id = 1,
                loggedAt = "2026-09-19T12:30:00+00:00",
                description = "Lunch",
                calories = 500.0,
            )
        )
        val entries = buildLogEntries(meals = meals, exerciseSessions = emptyList(), sleep = emptyList(), arousal = emptyList(), stool = emptyList(), encounters = emptyList())
        assertEquals(1, entries.size)
        assertNotEquals(LocalDateTime.MIN, entries[0].timestamp)
        assertEquals(12, entries[0].timestamp.hour)
    }

    @Test fun testBuildLogEntries_sortedDescending() {
        val meals = listOf(
            com.bioscan.fieldterminal.data.model.LogMealRow(id = 1, loggedAt = "2026-09-19T08:00:00+00:00", description = "Breakfast"),
            com.bioscan.fieldterminal.data.model.LogMealRow(id = 2, loggedAt = "2026-09-19T13:00:00+00:00", description = "Lunch"),
            com.bioscan.fieldterminal.data.model.LogMealRow(id = 3, loggedAt = "2026-09-19T19:00:00+00:00", description = "Dinner"),
        )
        val entries = buildLogEntries(meals = meals, exerciseSessions = emptyList(), sleep = emptyList(), arousal = emptyList(), stool = emptyList(), encounters = emptyList())
        assertEquals(3, entries.size)
        assertEquals(19, entries[0].timestamp.hour)  // dinner first (most recent)
        assertEquals(8, entries[2].timestamp.hour)   // breakfast last (oldest)
    }
}
