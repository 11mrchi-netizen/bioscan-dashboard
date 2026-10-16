package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

// Synthetic records only.
class HealthConnectDistanceTest {
    private val min = 60_000L
    private fun rec(fromMin: Long, toMin: Long, m: Double) = DistanceInterval(fromMin * min, toMin * min, m)

    @Test
    fun `consecutive records add up`() {
        val r = listOf(rec(0, 10, 2000.0), rec(10, 20, 2100.0), rec(20, 30, 1900.0))
        assertEquals(6000.0, distanceWithoutOverlap(r, 0, 30 * min), 1e-6)
    }

    @Test
    fun `a workout written twice by the same app counts once`() {
        val once = listOf(rec(0, 10, 2000.0), rec(10, 20, 2100.0))
        assertEquals(4100.0, distanceWithoutOverlap(once + once, 0, 20 * min), 1e-6)
        // One whole-workout record next to the per-segment ones of the same stretch.
        assertEquals(4100.0, distanceWithoutOverlap(listOf(rec(0, 20, 4100.0)) + once, 0, 20 * min), 1e-6)
    }

    @Test
    fun `a record reaching outside the workout counts only for its part inside`() {
        // A 60-minute record of 6 km, of which 30 minutes fall inside the workout.
        assertEquals(3000.0, distanceWithoutOverlap(listOf(rec(-30, 30, 6000.0)), 0, 30 * min), 1e-6)
        // Partly overlapping records: the overlap is counted once.
        assertEquals(3000.0, distanceWithoutOverlap(listOf(rec(0, 20, 2000.0), rec(10, 30, 2000.0)), 0, 30 * min), 1e-6)
    }

    @Test
    fun `the largest single app wins, apps are never added together`() {
        val byApp = mapOf(
            "app.a" to listOf(rec(0, 30, 6000.0)),
            "app.b" to listOf(rec(0, 30, 5800.0), rec(0, 30, 5800.0)),
        )
        assertEquals(6000.0, workoutDistanceMeters(byApp, 0, 30 * min), 1e-6)
        assertEquals(0.0, workoutDistanceMeters(emptyMap(), 0, 30 * min), 1e-6)
    }

    @Test
    fun `history is re-read from Zepp 31 days at a time, oldest first`() {
        val w = syncWindows(LocalDate.parse("2026-01-01"), LocalDate.parse("2026-03-05"))
        assertEquals(LocalDate.parse("2026-01-01") to LocalDate.parse("2026-01-31"), w.first())
        assertEquals(LocalDate.parse("2026-03-04") to LocalDate.parse("2026-03-05"), w.last())
        assertEquals(3, w.size)
        assertEquals(emptyList<Pair<LocalDate, LocalDate>>(), syncWindows(LocalDate.parse("2026-02-01"), LocalDate.parse("2026-01-01")))
    }
}
