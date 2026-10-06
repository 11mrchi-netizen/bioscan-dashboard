package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class HydrationIntelligenceTest {

    @Test
    fun keepsMeasuredEffectiveAndInferredDistinct() {
        val result = computeHydrationIntelligence(
            measuredIntakeMl = 1500.0,
            effectiveHydrationMl = 1200.0,
            exerciseDurationMinutesToday = 60.0,
        )

        assertEquals(1500.0, result.measuredIntakeMl!!, 0.01)
        assertEquals(1200.0, result.effectiveHydrationMl!!, 0.01)
        assertEquals(500.0, result.inferredDemandMl!!, 0.01) // 1h at 500ml/h
        assertFalse(result.environmentalContextAvailable)
        assertEquals(3, result.confidence.have)
    }

    @Test
    fun noExerciseMeansNoInferredDemandNotZeroFabricated() {
        val result = computeHydrationIntelligence(1500.0, 1200.0, 0.0)

        assertNull(result.inferredDemandMl)
        assertEquals(2, result.confidence.have)
    }

    @Test
    fun environmentalContextIsAlwaysUnavailable() {
        val result = computeHydrationIntelligence(1000.0, 1000.0, 30.0)
        assertFalse(result.environmentalContextAvailable)
    }
}
