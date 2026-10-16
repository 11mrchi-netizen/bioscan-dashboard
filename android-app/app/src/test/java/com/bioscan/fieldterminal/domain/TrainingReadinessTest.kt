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

    // Analysis review A1: contributors that disagree are mixed signals, not missing data. They read like a neutral
    // recovery (the pill says MIXED SIGNALS) instead of turning every row into BUILDING.
    @Test
    fun mixedRecoveryReadsLikeNeutralInsteadOfBuilding() {
        val mixed = computeTrainingReadiness(recovery(EvalState.Unstable))
        val neutral = computeTrainingReadiness(recovery(EvalState.Stable))

        assertEquals(neutral.demandRelative, mixed.demandRelative)
        TrainingDemandTier.entries.forEach { tier -> assert(mixed.demandRelative.getValue(tier) != EvalState.Building) }
    }

    @Test
    fun missingRecoveryIsStillBuilding() {
        val result = computeTrainingReadiness(recovery(EvalState.Building))
        TrainingDemandTier.entries.forEach { tier -> assertEquals(EvalState.Building, result.demandRelative.getValue(tier)) }
    }
}
