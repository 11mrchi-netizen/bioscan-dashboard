package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class TrainingReadinessTest {

    private fun recovery(state: EvalState) = DynamicRecoveryResult(state, emptyList(), Confidence(5, 5))

    @Test
    fun strongRecoveryReadsShiftUpAcrossEveryTier() {
        val result = computeTrainingReadiness(recovery(EvalState.ShiftUp))

        TrainingDemandTier.entries.forEach { tier ->
            assertEquals("expected ShiftUp for $tier", EvalState.ShiftUp, result.demandRelative.getValue(tier))
        }
    }

    @Test
    fun lowRecoveryStillSupportsEasyButFlagsHighLoadTiers() {
        val result = computeTrainingReadiness(recovery(EvalState.ShiftDown))

        assertEquals(EvalState.ShiftUp, result.demandRelative.getValue(TrainingDemandTier.EASY_AEROBIC))
        assertEquals(EvalState.Stable, result.demandRelative.getValue(TrainingDemandTier.THRESHOLD))
        assertEquals(EvalState.ShiftDown, result.demandRelative.getValue(TrainingDemandTier.LONG_HIGH_LOAD))
        assertEquals(EvalState.ShiftDown, result.demandRelative.getValue(TrainingDemandTier.STRENGTH))
    }

    @Test
    fun stableRecoveryIsMatchedNotExtraForHighDemandTiers() {
        val result = computeTrainingReadiness(recovery(EvalState.Stable))

        assertEquals(EvalState.ShiftUp, result.demandRelative.getValue(TrainingDemandTier.EASY_AEROBIC))
        assertEquals(EvalState.Stable, result.demandRelative.getValue(TrainingDemandTier.LONG_HIGH_LOAD))
    }

    @Test
    fun contradictoryRecoveryGivesNoCleanReadinessClaim() {
        val result = computeTrainingReadiness(recovery(EvalState.Unstable))

        TrainingDemandTier.entries.forEach { tier ->
            assertEquals(EvalState.Building, result.demandRelative.getValue(tier))
        }
    }
}
