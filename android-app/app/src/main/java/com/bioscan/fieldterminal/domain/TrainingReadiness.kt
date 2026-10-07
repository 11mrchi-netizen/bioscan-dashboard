package com.bioscan.fieldterminal.domain

// 09.4 Training Readiness (DAV-250-253). A consumer of Dynamic Recovery
// (09.2), never a competing training-load score -- this file never
// recomputes CTL/ATL/TSB or any recovery contributor, per DAV-250's own
// explicit rule. Expresses readiness as a MISMATCH between current recovery
// capacity and a specific training demand, not a single number that rewards
// a high recovery reading regardless of what's actually planned.
const val TRAINING_READINESS_MODEL_VERSION = "v1"

enum class TrainingDemandTier { EASY_AEROBIC, THRESHOLD, LONG_HIGH_LOAD, STRENGTH }

data class TrainingReadinessResult(
    // General recovery capacity, passed through from Dynamic Recovery
    // unchanged -- distinct from the activity-demand-specific states below.
    val generalState: EvalState,
    val demandRelative: Map<TrainingDemandTier, EvalState>,
    val confidence: Confidence,
    val modelVersion: String = TRAINING_READINESS_MODEL_VERSION,
)

// A documented rule table, not a fitted model -- no data volume exists yet
// to fit one (same gate DAV-63 already established elsewhere). Demand
// levels are ordinal, not physiological units: EASY_AEROBIC needs the least
// recovery headroom, LONG_HIGH_LOAD and STRENGTH need the most.
private val TIER_DEMAND_LEVEL = mapOf(
    TrainingDemandTier.EASY_AEROBIC to -1,
    TrainingDemandTier.THRESHOLD to 0,
    TrainingDemandTier.LONG_HIGH_LOAD to 1,
    TrainingDemandTier.STRENGTH to 1,
)

// Unstable maps to null (not a level) rather than some arbitrary middle
// value -- Dynamic Recovery's contributors genuinely disagreed, so there is
// no clean recovery-capacity number to compare a demand tier against.
private fun recoveryLevel(state: EvalState): Int? = when (state) {
    EvalState.ShiftUp -> 2
    EvalState.Stable -> 1
    EvalState.ShiftDown -> 0
    EvalState.Unstable, EvalState.Building, EvalState.NoData -> null
}

fun computeTrainingReadiness(recovery: DynamicRecoveryResult): TrainingReadinessResult {
    val level = recoveryLevel(recovery.state)
    val demandRelative = TrainingDemandTier.entries.associateWith { tier ->
        val demand = TIER_DEMAND_LEVEL.getValue(tier)
        when {
            level == null -> EvalState.Building
            level > demand -> EvalState.ShiftUp
            level == demand -> EvalState.Stable
            else -> EvalState.ShiftDown
        }
    }
    return TrainingReadinessResult(recovery.state, demandRelative, recovery.confidence)
}
