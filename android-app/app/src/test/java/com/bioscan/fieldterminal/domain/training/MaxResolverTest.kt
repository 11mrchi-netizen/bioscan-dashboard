package com.bioscan.fieldterminal.domain.training

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class MaxResolverTest {
    private val today = LocalDate.of(2026, 10, 7)
    private val key = movementKey("Squat")

    private fun rec(kg: Double, asOf: String, source: String = "test", kind: String = "1rm") =
        RecordedMax(key, "Squat", kind, kg, LocalDate.parse(asOf), source)

    private fun logged(date: String, weight: Double, pct: Double) = mapOf(key to listOf(DatedSet(LocalDate.parse(date), 5, weight, pct)))

    @Test
    fun aNewerLoggedPercentBeatsAnOlderRecordedMax() {
        val r = resolveMaxes(listOf(rec(100.0, "2026-08-01")), logged("2026-09-20", 75.0, 75.0), emptyMap(), today)[key]!!
        assertEquals(100.0, r.entry.oneRmKg!!, 0.001) // implied: 75 / 0.75 = 100, from a newer session
        assertEquals("implied_logged_percent", r.source)
        val older = resolveMaxes(listOf(rec(90.0, "2026-08-01")), logged("2026-09-20", 75.0, 75.0), emptyMap(), today)[key]!!
        assertEquals(100.0, older.entry.oneRmKg!!, 0.001)
    }

    @Test
    fun aRecordedMaxWinsWhenItIsNewerOrTheSameDay() {
        val newer = resolveMaxes(listOf(rec(105.0, "2026-10-01", "manual")), logged("2026-09-20", 75.0, 75.0), emptyMap(), today)[key]!!
        assertEquals(105.0, newer.entry.oneRmKg!!, 0.001)
        assertEquals("manual", newer.source)
        val tie = resolveMaxes(listOf(rec(105.0, "2026-09-20")), logged("2026-09-20", 75.0, 75.0), emptyMap(), today)[key]!!
        assertEquals("test", tie.source)
    }

    @Test
    fun trainingMaxAndMaxRepsRideAlongAndNothingIsInvented() {
        val rows = listOf(rec(100.0, "2026-10-01"), rec(90.0, "2026-10-01", kind = "training_max"), rec(12.0, "2026-10-01", kind = "max_reps"))
        val r = resolveMaxes(rows, emptyMap(), emptyMap(), today)[key]!!
        assertEquals(90.0, r.entry.trainingMaxKg!!, 0.001)
        assertEquals(12, r.entry.maxReps)
        val none = resolveMaxes(emptyList(), mapOf(key to emptyList()), emptyMap(), today)[key]!!
        assertNull(none.entry.oneRmKg)
        assertEquals("none", none.source)
    }
}
