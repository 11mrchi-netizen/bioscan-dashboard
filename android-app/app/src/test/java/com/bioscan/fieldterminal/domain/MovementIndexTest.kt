package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

class MovementIndexTest {

    private val asOf = LocalDate.of(2026, 9, 30)

    @Test
    fun stepsAtOwnBaselineScoresNearMaximum() {
        val days = (0..59).map { asOf.minusDays((59 - it).toLong()) }
        val steps = days.map { it to 8000.0 }

        val result = computeMovementIndex(steps, emptyList(), asOf)

        assertEquals(1, result.components.size)
        assertEquals(setOf(MovementIndexComponent.ACTIVE_CALORIES, MovementIndexComponent.ZONE_MINUTES), result.unavailableComponents)
        assertTrue("expected a near-maximum score, got ${result.score}", result.score!! > 95.0)
        assertEquals(EvalState.Stable, result.state)
    }

    @Test
    fun exerciseSessionsAddASecondComponent() {
        val days = (0..59).map { asOf.minusDays((59 - it).toLong()) }
        val steps = days.map { it to 8000.0 }
        // One session every day for the whole window -- consistent with its
        // own baseline, same treatment as steps above.
        val sessions = days.map { it.atTime(7, 0).atOffset(ZoneOffset.UTC) }

        val result = computeMovementIndex(steps, sessions, asOf)

        assertEquals(2, result.components.size)
        assertTrue(result.score!! > 90.0)
    }

    @Test
    fun noDataIsBuildingNotFabricated() {
        val result = computeMovementIndex(emptyList(), emptyList(), asOf)

        assertNull(result.score)
        assertEquals(EvalState.Building, result.state)
        assertTrue(result.components.isEmpty())
    }

    @Test
    fun aWeekOffFromRunningScoresLowOnEngagementNotStepVolume() {
        val days = (0..59).map { asOf.minusDays((59 - it).toLong()) }
        val steps = days.map { it to 8000.0 } // unaffected -- still walking normally
        // Sessions every day except the most recent 7 -- a real drop in
        // exercise engagement the index should reflect.
        val sessions = days.dropLast(7).map { it.atTime(7, 0).atOffset(ZoneOffset.UTC) }

        val result = computeMovementIndex(steps, sessions, asOf)

        val engagement = result.components.getValue(MovementIndexComponent.EXERCISE_ENGAGEMENT).score
        val stepVolume = result.components.getValue(MovementIndexComponent.STEP_VOLUME).score
        assertTrue("expected engagement to drop, got $engagement", engagement < 50.0)
        assertTrue("expected step volume to stay high, got $stepVolume", stepVolume > 95.0)
    }
}
