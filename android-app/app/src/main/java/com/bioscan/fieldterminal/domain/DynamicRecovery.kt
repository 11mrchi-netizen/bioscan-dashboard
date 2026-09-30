package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.domain.analysis.Directionality
import com.bioscan.fieldterminal.domain.analysis.Provenance
import com.bioscan.fieldterminal.domain.comparison.METRIC_DIRECTIONALITY
import com.bioscan.fieldterminal.domain.stress.STRESS_RHYTHM_MODEL_VERSION
import com.bioscan.fieldterminal.domain.stress.StressRhythmResult

// 09.2 Dynamic Recovery (DAV-238-241). A longitudinal state model, not a
// single opaque readiness score -- every contributor keeps its own
// direction/magnitude/confidence/provenance, never blended into one number
// before display (DAV-238's own rule). Sleep comes from Sleep Index (09.1),
// HRV/RHR from the established personal-baseline machinery
// (HrvRhrEvaluation.kt), training load from evaluateTrainingLoad() -- none
// of these are recomputed here. Zepp physiological stress (05.1) is an
// OPTIONAL contributor, folded in only when a real StressRhythmResult
// exists, per DAV-238's own "add Zepp stress only when its source is
// validated" instruction.
const val DYNAMIC_RECOVERY_MODEL_VERSION = "v1"

data class RecoveryContributor(
    val dimension: String,
    // -1 = pulling recovery down, 0 = neutral, +1 = pulling it up, null = no
    // directional claim (not enough data, or the underlying state doesn't
    // support one yet).
    val direction: Int?,
    val magnitude: Double?,
    val confidence: Confidence,
    val provenance: Provenance,
)

data class DynamicRecoveryResult(
    val state: EvalState,
    val contributors: List<RecoveryContributor>,
    val confidence: Confidence,
    val modelVersion: String = DYNAMIC_RECOVERY_MODEL_VERSION,
)

private const val MIN_CONTRIBUTORS_FOR_STATE = 2

// Shared by HRV and RHR: a raw statistical ShiftUp/ShiftDown only becomes a
// recovery-positive/negative direction once the metric's own directionality
// is applied (RHR is LOWER_BETTER, so a ShiftUp there is bad for recovery,
// the opposite of HRV's ShiftUp) -- reuses METRIC_DIRECTIONALITY rather than
// hand-coding the flip per caller.
private fun directionFromShift(state: EvalState, directionality: Directionality): Int? = when (state) {
    EvalState.ShiftUp -> if (directionality == Directionality.LOWER_BETTER) -1 else 1
    EvalState.ShiftDown -> if (directionality == Directionality.LOWER_BETTER) 1 else -1
    EvalState.Stable -> 0
    else -> null // NoData, Building, Unstable -- no directional claim without more certainty
}

// TrainingLoadEvaluation.kt's own Unstable means "heavily loaded" (a real,
// more severe version of ShiftDown), a domain-specific repurposing already
// baked into that evaluation's own state -- not the generic "elevated
// dispersion" meaning Unstable carries for HRV/RHR, so this gets its own
// mapping rather than reusing directionFromShift().
private fun trainingLoadDirection(state: EvalState): Int? = when (state) {
    EvalState.ShiftUp -> 1 // freshened / detrained
    EvalState.Stable -> 0
    EvalState.ShiftDown -> -1 // loaded
    EvalState.Unstable -> -1 // heavily loaded
    EvalState.Building, EvalState.NoData -> null
}

fun computeDynamicRecovery(
    sleepIndex: SleepIndexResult,
    hrvEval: SwcEvaluation,
    rhrEval: SwcEvaluation,
    trainingLoadEval: TrainingLoadEvaluation,
    energyEval: SubjectiveEvaluation,
    stressEval: SubjectiveEvaluation,
    sorenessEval: SubjectiveEvaluation,
    physiologicalStress: StressRhythmResult? = null,
): DynamicRecoveryResult {
    val contributors = mutableListOf<RecoveryContributor>()

    if (sleepIndex.score != null) {
        val direction = when {
            sleepIndex.score >= 70.0 -> 1
            sleepIndex.score < 50.0 -> -1
            else -> 0
        }
        contributors += RecoveryContributor("sleep", direction, sleepIndex.score, sleepIndex.confidence, Provenance("sleep_index", "computeSleepIndex", SLEEP_INDEX_MODEL_VERSION))
    }

    directionFromShift(hrvEval.state, METRIC_DIRECTIONALITY.getValue("hrv")).let { direction ->
        if (hrvEval.confidence.met) {
            contributors += RecoveryContributor("hrv", direction, hrvEval.baseline7d, hrvEval.confidence, Provenance("wearable_daily", "evaluateHrv", null))
        }
    }
    directionFromShift(rhrEval.state, METRIC_DIRECTIONALITY.getValue("resting_heart_rate")).let { direction ->
        if (rhrEval.confidence.met) {
            contributors += RecoveryContributor("resting_heart_rate", direction, rhrEval.baseline7d, rhrEval.confidence, Provenance("wearable_daily", "evaluateRhr", null))
        }
    }

    if (trainingLoadEval.confidence.met) {
        contributors += RecoveryContributor("training_load", trainingLoadDirection(trainingLoadEval.state), trainingLoadEval.tsb, trainingLoadEval.confidence, Provenance("exercise_sessions", "evaluateTrainingLoad", null))
    }

    // Subjective inputs stay explicitly subjective -- each its own
    // evaluateSubjective() call, never blended into one "mood" number
    // (SubjectiveEvaluation's own established convention). Stress/soreness
    // are inverted: a rising trend there pulls recovery down, unlike energy.
    if (energyEval.confidence.met) {
        contributors += RecoveryContributor("energy", energyEval.trendDirection, energyEval.median7d, energyEval.confidence, Provenance("wellbeing_daily", "evaluateSubjective", null))
    }
    if (stressEval.confidence.met) {
        contributors += RecoveryContributor("subjective_stress", stressEval.trendDirection?.let { -it }, stressEval.median7d, stressEval.confidence, Provenance("wellbeing_daily", "evaluateSubjective", null))
    }
    if (sorenessEval.confidence.met) {
        contributors += RecoveryContributor("soreness", sorenessEval.trendDirection?.let { -it }, sorenessEval.median7d, sorenessEval.confidence, Provenance("wellbeing_daily", "evaluateSubjective", null))
    }

    // Optional: only added when a real Stress Rhythm result exists (DAV-238's
    // own "add Zepp stress only when its source is validated" instruction).
    physiologicalStress?.let { stress ->
        if (stress.confidence.met && stress.deviationFromPersonalPattern != null) {
            val direction = if (stress.deviationFromPersonalPattern > 0) -1 else if (stress.deviationFromPersonalPattern < 0) 1 else 0
            contributors += RecoveryContributor("physiological_stress", direction, stress.deviationFromPersonalPattern, stress.confidence, Provenance("zepp", "analyzeStressDay", STRESS_RHYTHM_MODEL_VERSION))
        }
    }

    if (contributors.size < MIN_CONTRIBUTORS_FOR_STATE) {
        return DynamicRecoveryResult(EvalState.Building, contributors, Confidence(contributors.size, MIN_CONTRIBUTORS_FOR_STATE))
    }

    val directional = contributors.mapNotNull { it.direction }
    val state = when {
        directional.isEmpty() -> EvalState.Building
        directional.sumOf { it } > 0 && directional.count { it < 0 } == 0 -> EvalState.ShiftUp
        directional.sumOf { it } < 0 && directional.count { it > 0 } == 0 -> EvalState.ShiftDown
        directional.count { it != 0 } >= 2 && directional.any { it > 0 } && directional.any { it < 0 } -> EvalState.Unstable
        else -> EvalState.Stable
    }
    return DynamicRecoveryResult(state, contributors, Confidence(contributors.size, MIN_CONTRIBUTORS_FOR_STATE))
}
