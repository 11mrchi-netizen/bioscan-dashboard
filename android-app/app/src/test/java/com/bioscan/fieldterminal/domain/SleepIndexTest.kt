package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class SleepIndexTest {

    private val zone = ZoneId.of("UTC")
    private val asOf = LocalDate.of(2026, 9, 30)

    @Test
    fun perfectlyRegularSixtyDaysScoresNearMaximum() {
        val days = (0..59).map { asOf.minusDays((59 - it).toLong()) }
        val hours = days.map { it to 7.5 }
        val deepMinutes = days.map { it to 90.0 }
        val respiratory = days.map { it to 14.0 }
        val nights = days.map { date ->
            val bedtime = date.minusDays(1).atTime(23, 0).atOffset(ZoneOffset.UTC).toInstant()
            val wake = date.atTime(7, 0).atOffset(ZoneOffset.UTC).toInstant()
            SleepNight(date, bedtime, wake)
        }

        val result = computeSleepIndex(hours, deepMinutes, respiratory, nights, zone, asOf)

        assertEquals(4, result.components.size)
        assertEquals(EvalState.Stable, result.state)
        assertTrue("expected a near-maximum score, got ${result.score}", result.score!! > 95.0)
        assertEquals("Good", result.band)
    }

    @Test
    fun noDataIsBuildingNotFabricated() {
        val result = computeSleepIndex(emptyList(), emptyList(), emptyList(), emptyList(), zone, asOf)

        assertNull(result.score)
        assertNull(result.band)
        assertEquals(EvalState.Building, result.state)
        assertTrue(result.components.isEmpty())
    }

    @Test
    fun onlyTwoComponentsStillProducesARenormalizedScore() {
        // Duration + respiratory only (no deep-minutes, no consecutive-night
        // bedtime/wake data) -- should still compute, renormalized over just
        // those two weights, not silently dragged down by the missing pair.
        val days = (0..59).map { asOf.minusDays((59 - it).toLong()) }
        val hours = days.map { it to 7.5 }
        val respiratory = days.map { it to 14.0 }

        val result = computeSleepIndex(hours, emptyList(), respiratory, emptyList(), zone, asOf)

        assertEquals(2, result.components.size)
        assertTrue("expected a high score from two clean components, got ${result.score}", result.score!! > 95.0)
    }
}
