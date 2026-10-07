package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.domain.analysis.Directionality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrendInterpretationTest {

    private fun eval(state: EvalState, baseline7d: Double? = null, mean60d: Double? = null, cv7d: Double? = null) =
        SwcEvaluation(state, Confidence(7, 7), baseline7d, mean60d, swcPct = 5.0, cv7d = cv7d)

    @Test
    fun noDataReturnsNull() {
        assertNull(interpretSwcEvaluation("HRV", eval(EvalState.NoData), Directionality.HIGHER_BETTER))
    }

    @Test
    fun buildingNamesDaysOfHistory() {
        val result = interpretSwcEvaluation("HRV", eval(EvalState.Building), Directionality.HIGHER_BETTER)
        assertTrue(result!!.contains("7/7"))
    }

    @Test
    fun stableIsNeutral() {
        val result = interpretSwcEvaluation("HRV", eval(EvalState.Stable), Directionality.HIGHER_BETTER)
        assertEquals("HRV is steady, close to its 60-day baseline.", result)
    }

    @Test
    fun shiftUpOnHigherBetterReadsFavorable() {
        val result = interpretSwcEvaluation("HRV", eval(EvalState.ShiftUp, baseline7d = 70.0, mean60d = 60.0), Directionality.HIGHER_BETTER)
        assertEquals("HRV is 17% above its 60-day baseline — trending favorably.", result)
    }

    @Test
    fun shiftUpOnLowerBetterReadsAsWorthWatching() {
        // RHR rising is unfavorable even though the state is ShiftUp.
        val result = interpretSwcEvaluation("RESTING HEART RATE", eval(EvalState.ShiftUp, baseline7d = 66.0, mean60d = 60.0), Directionality.LOWER_BETTER)
        assertEquals("RESTING HEART RATE is 10% above its 60-day baseline — worth keeping an eye on.", result)
    }

    @Test
    fun shiftOnOptimalRangeHasNoFavorableClaim() {
        val result = interpretSwcEvaluation("SLEEP DURATION", eval(EvalState.ShiftDown, baseline7d = 6.0, mean60d = 7.5), Directionality.OPTIMAL_RANGE)
        assertEquals("SLEEP DURATION is 20% below its 60-day baseline.", result)
    }

    @Test
    fun unstableNamesTheCv() {
        val result = interpretSwcEvaluation("HRV", eval(EvalState.Unstable, cv7d = 42.0), Directionality.HIGHER_BETTER)
        assertTrue(result!!.contains("42%"))
    }
}
