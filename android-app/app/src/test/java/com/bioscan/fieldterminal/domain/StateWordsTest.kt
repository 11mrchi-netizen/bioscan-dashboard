package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.domain.analysis.Directionality
import org.junit.Assert.assertEquals
import org.junit.Test

// Analysis review A1: each kind of evaluation maps to its own state and word.
class StateWordsTest {
    @Test
    fun aRiseInHrvIsFavorableAndARiseInRestingHeartRateIsNot() {
        assertEquals(StateWord(MetricState.Optimal, "ABOVE BASELINE"), baselineStateWord(EvalState.ShiftUp, Directionality.HIGHER_BETTER))
        assertEquals(StateWord(MetricState.Warning, "BELOW BASELINE"), baselineStateWord(EvalState.ShiftDown, Directionality.HIGHER_BETTER))
        assertEquals(StateWord(MetricState.Warning, "ABOVE BASELINE"), baselineStateWord(EvalState.ShiftUp, Directionality.LOWER_BETTER))
        assertEquals(StateWord(MetricState.Optimal, "BELOW BASELINE"), baselineStateWord(EvalState.ShiftDown, Directionality.LOWER_BETTER))
    }

    @Test
    fun steadyIsNeutralNotOptimal() {
        assertEquals(StateWord(MetricState.Neutral, "STEADY"), baselineStateWord(EvalState.Stable, Directionality.HIGHER_BETTER))
        assertEquals(MetricState.Building, baselineStateWord(EvalState.Building, Directionality.HIGHER_BETTER).state)
        assertEquals(MetricState.Unavailable, baselineStateWord(EvalState.NoData, Directionality.HIGHER_BETTER).state)
    }

    @Test
    fun sleepDurationWarnsOnlyWhenTheWeekLeavesItsHealthyRange() {
        val od = Directionality.OPTIMAL_RANGE
        assertEquals(MetricState.Warning, baselineStateWord(EvalState.ShiftDown, od, SLEEP_HOURS_RANGE, 6.2).state)
        assertEquals(MetricState.Neutral, baselineStateWord(EvalState.ShiftDown, od, SLEEP_HOURS_RANGE, 7.4).state)
        assertEquals(MetricState.Neutral, baselineStateWord(EvalState.ShiftUp, od, SLEEP_HOURS_RANGE, 8.1).state)
        assertEquals(MetricState.Warning, baselineStateWord(EvalState.ShiftUp, od, SLEEP_HOURS_RANGE, 9.6).state)
        // no known range: described, never judged
        assertEquals(MetricState.Neutral, baselineStateWord(EvalState.ShiftDown, Directionality.NON_DIRECTIONAL).state)
    }

    @Test
    fun recoveryAndLoadWords() {
        assertEquals(StateWord(MetricState.Neutral, "MIXED SIGNALS"), recoveryStateWord(EvalState.Unstable))
        assertEquals(StateWord(MetricState.Optimal, "ABOVE EXPECTED"), recoveryStateWord(EvalState.ShiftUp))
        assertEquals(StateWord(MetricState.Warning, "BELOW EXPECTED"), recoveryStateWord(EvalState.ShiftDown))
        assertEquals(StateWord(MetricState.Neutral, "AS EXPECTED"), recoveryStateWord(EvalState.Stable))
        assertEquals(StateWord(MetricState.Neutral, "LOADED"), trainingLoadStateWord(EvalState.ShiftDown, "Loaded"))
        assertEquals(StateWord(MetricState.Warning, "HEAVILY LOADED"), trainingLoadStateWord(EvalState.Unstable))
        assertEquals(StateWord(MetricState.Neutral, "RISING"), trendStateWord(EvalState.ShiftUp))
        assertEquals(StateWord(MetricState.Neutral, "TRACKED"), gateStateWord(EvalState.Stable))
    }
}
