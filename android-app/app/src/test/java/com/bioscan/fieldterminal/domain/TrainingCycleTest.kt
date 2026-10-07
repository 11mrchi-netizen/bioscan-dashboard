package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrainingCycleTest {

    @Test
    fun progressFractionWorksForHigherIsBetterGoals() {
        // e.g. lifting from a 60kg squat toward an 80kg target.
        assertEquals(0.5, progressFraction(startingValue = 60.0, targetValue = 80.0, currentValue = 70.0)!!, 0.001)
        assertEquals(0.0, progressFraction(startingValue = 60.0, targetValue = 80.0, currentValue = 60.0)!!, 0.001)
        assertEquals(1.0, progressFraction(startingValue = 60.0, targetValue = 80.0, currentValue = 80.0)!!, 0.001)
    }

    @Test
    fun progressFractionSelfCorrectsForLowerIsBetterGoals() {
        // e.g. a 5k time going from 1500s down to a 1200s target -- no
        // separate directionality flag needed, the formula self-corrects.
        assertEquals(0.5, progressFraction(startingValue = 1500.0, targetValue = 1200.0, currentValue = 1350.0)!!, 0.001)
    }

    @Test
    fun progressFractionClampsOvershoot() {
        assertEquals(1.0, progressFraction(startingValue = 60.0, targetValue = 80.0, currentValue = 95.0)!!, 0.001)
    }

    @Test
    fun progressFractionNullWhenGoalOrCurrentMissing() {
        assertNull(progressFraction(null, 80.0, 70.0))
        assertNull(progressFraction(60.0, null, 70.0))
        assertNull(progressFraction(60.0, 80.0, null))
        assertNull(progressFraction(60.0, 60.0, 60.0)) // no distance to measure progress across
    }
}
