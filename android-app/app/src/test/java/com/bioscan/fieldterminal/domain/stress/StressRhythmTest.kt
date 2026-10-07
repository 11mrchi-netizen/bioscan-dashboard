package com.bioscan.fieldterminal.domain.stress

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class StressRhythmTest {

    private val date = LocalDate.of(2026, 9, 30)
    private val zone = ZoneOffset.UTC

    private fun sampleAt(hour: Int, minute: Int, value: Int) =
        PhysiologicalStressReading(date.atTime(hour, minute).atOffset(zone).toInstant(), value)

    @Test
    fun excludesExerciseWindowSamplesFromPeakDetection() {
        val samples = buildList {
            // Baseline all day, one real elevated block, one exercise-driven
            // spike that must NOT count as a stress peak, evening wind-down.
            for (h in 0 until 24) for (m in 0 until 60 step 10) {
                val value = when {
                    h == 12 -> 70 // real elevated block, 12:00-12:50
                    h == 15 && m in 0..20 -> 95 // exercise-driven spike, 15:00-15:20
                    h in 21..23 -> 25 // evening wind-down, below baseline
                    else -> 40
                }
                add(sampleAt(h, m, value))
            }
        }
        val exerciseWindow = date.atTime(15, 0).atOffset(zone).toInstant() to date.atTime(15, 20).atOffset(zone).toInstant()

        val recentDays = (1..30).map { StressDay(date.minusDays(it.toLong()), emptyList(), StressDailySummary(40, null, null, null, null, null, null)) }
        val today = StressDay(date, samples, StressDailySummary(45, null, null, null, null, null, null))

        val result = analyzeStressDay(today, recentDays, listOf(exerciseWindow), zone)

        assertEquals(40.0, result.baseline!!, 0.01)
        assertEquals(30, result.peakMagnitude) // 70 - baseline(40), the 95-spike excluded
        assertTrue("expected a real elevated-run duration, got ${result.peakDurationMinutes}", result.peakDurationMinutes!! >= 40)
        assertEquals(100.0, result.eveningDownRegulationPct!!, 0.01) // dropped below baseline -- clamped
        assertEquals(5.0, result.deviationFromPersonalPattern!!, 0.01) // 45 - personal baseline 40
        assertTrue(result.confidence.met)
    }

    @Test
    fun tooFewSamplesReturnsNoFabricatedNumbers() {
        val sparse = StressDay(date, listOf(sampleAt(8, 0, 40), sampleAt(9, 0, 42)), StressDailySummary(41, null, null, null, null, null, null))

        val result = analyzeStressDay(sparse, emptyList(), emptyList(), zone)

        assertNull(result.baseline)
        assertNull(result.peakMagnitude)
        assertNull(result.eveningDownRegulationPct)
        assertTrue(!result.confidence.met)
    }

    @Test
    fun excludeExerciseWindowsDropsOnlyOverlappingSamples() {
        val samples = listOf(sampleAt(8, 0, 40), sampleAt(9, 0, 90), sampleAt(10, 0, 40))
        val window = date.atTime(8, 55).atOffset(zone).toInstant() to date.atTime(9, 5).atOffset(zone).toInstant()

        val filtered = excludeExerciseWindows(samples, listOf(window))

        assertEquals(2, filtered.size)
        assertTrue(filtered.none { it.value == 90 })
    }
}
