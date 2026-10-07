package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

class CircadianAlignmentTest {

    private val asOf = LocalDate.of(2026, 9, 30)
    private val noSri = SriEvaluation(null, Confidence(0, 14))

    @Test
    fun identicalClockTimesScoreMaximumRegularity() {
        val score = timingRegularityScore(List(10) { 480.0 }) // 08:00 every time
        assertEquals(100.0, score!!, 0.01)
    }

    @Test
    fun wideSpreadScoresZero() {
        // Alternating 06:00/22:00 -- an 8h stdev, far past the 3h max-regular threshold.
        val minutes = List(10) { if (it % 2 == 0) 360.0 else 1320.0 }
        val score = timingRegularityScore(minutes)
        assertEquals(0.0, score!!, 0.01)
    }

    @Test
    fun fewerThanMinObservationsIsNull() {
        assertNull(timingRegularityScore(listOf(480.0, 490.0)))
    }

    @Test
    fun regularMealsAndExerciseWithNoSriStillProducesAScore() {
        val mealTimestamps = (0 until 20).map { asOf.minusDays(it.toLong()).atTime(8, 0).atOffset(ZoneOffset.UTC) }
        val exerciseTimestamps = (0 until 20).map { asOf.minusDays(it.toLong()).atTime(7, 0).atOffset(ZoneOffset.UTC) }

        val result = computeCircadianAlignment(noSri, mealTimestamps, exerciseTimestamps, asOf)

        assertEquals(setOf(CircadianAlignmentComponent.MEAL_TIMING, CircadianAlignmentComponent.EXERCISE_TIMING), result.components.keys)
        assertTrue(result.score!! > 95.0)
        assertEquals("Regular", result.band)
    }

    @Test
    fun noDataAtAllIsBuildingNotFabricated() {
        val result = computeCircadianAlignment(noSri, emptyList(), emptyList(), asOf)

        assertNull(result.score)
        assertEquals(EvalState.Building, result.state)
    }
}
