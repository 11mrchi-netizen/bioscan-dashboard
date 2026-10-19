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
// v2 (app restructure, fix 4): the state weighs its contributors instead of needing them to agree. v1 called ABOVE only
// when no contributor pointed down and BELOW only when none pointed up, so any disagreement read MIXED SIGNALS, and
// during training (load almost always points down) a clearly good morning never read anything else.
const val DYNAMIC_RECOVERY_MODEL_VERSION = "v2"

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
    // v2: the weighted lean behind the state (null while building).
    val balance: RecoveryBalance? = null,
)

// The weighted vote behind the state: each share is of the total weight, net = up - down.
data class RecoveryBalance(val up: Double, val down: Double, val votes: Double) {
    val net: Double get() = up - down
}

// v2 weights. HRV, resting heart rate, the Sleep Index and training load are one vote each; the subjective answers
// (energy, stress, soreness, morning erection, arousal) are one vote together, their mean, so five answers from one
// questionnaire do not outvote the measurements; Zepp physiological stress is one vote.
private val SUBJECTIVE_DIMENSIONS = setOf("energy", "subjective_stress", "soreness", "morning_wood", "arousal")

// ABOVE / BELOW once the weighted lean reaches a quarter of the votes (one vote in four, net of the others).
const val RECOVERY_LEAN = 0.25

// MIXED SIGNALS only when each side carries at least a third of the weight and neither wins by the lean above.
const val RECOVERY_SPLIT_SHARE = 1.0 / 3.0

fun recoveryBalance(contributors: List<RecoveryContributor>): RecoveryBalance? {
    val votes = buildList {
        contributors.filter { it.dimension !in SUBJECTIVE_DIMENSIONS }.forEach { c -> c.direction?.let { add(it.toDouble()) } }
        contributors.filter { it.dimension in SUBJECTIVE_DIMENSIONS }.mapNotNull { it.direction }
            .takeIf { it.isNotEmpty() }?.let { add(it.average()) }
    }
    if (votes.isEmpty()) return null
    val n = votes.size.toDouble()
    return RecoveryBalance(up = votes.sumOf { maxOf(it, 0.0) } / n, down = votes.sumOf { maxOf(-it, 0.0) } / n, votes = n)
}

fun recoveryStateOf(balance: RecoveryBalance?): EvalState = when {
    balance == null -> EvalState.Building
    balance.up >= RECOVERY_SPLIT_SHARE && balance.down >= RECOVERY_SPLIT_SHARE && kotlin.math.abs(balance.net) < RECOVERY_LEAN -> EvalState.Unstable
    balance.net >= RECOVERY_LEAN -> EvalState.ShiftUp
    balance.net <= -RECOVERY_LEAN -> EvalState.ShiftDown
    else -> EvalState.Stable
}

private const val MIN_CONTRIBUTORS_FOR_STATE = 2

// Shared by HRV and RHR: a raw statistical ShiftUp/ShiftDown only becomes a
// recovery-positive/negative direction once the metric's own directionality
// is applied (RHR is LOWER_BETTER, so a ShiftUp there is bad for recovery,
// the opposite of HRV's ShiftUp) -- reuses METRIC_DIRECTIONALITY rather than
// hand-coding the flip per caller.
internal fun directionFromShift(state: EvalState, directionality: Directionality): Int? = when (state) {
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
    // Arousal fold-in: morning-wood/arousal are new, optional wellbeing_daily
    // contributors -- treated the same polarity as energy (not inverted like
    // stress/soreness). No real literature backs this app's own direction
    // choice here; it's a plain stated assumption, not a hidden claim.
    morningWoodEval: SubjectiveEvaluation? = null,
    arousalEval: SubjectiveEvaluation? = null,
): DynamicRecoveryResult {
    val contributors = mutableListOf<RecoveryContributor>()

    // Analysis review phase G: the Sleep Index's own cut-offs (SleepIndex.kt), not a second set: a week of sleep as
    // expected is neutral here, one below expected holds recovery back.
    if (sleepIndex.score != null) {
        contributors += RecoveryContributor("sleep", sleepIndexDirection(sleepIndex.score), sleepIndex.score, sleepIndex.confidence, Provenance("sleep_index", "computeSleepIndex", SLEEP_INDEX_MODEL_VERSION))
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
    if (morningWoodEval != null && morningWoodEval.confidence.met) {
        contributors += RecoveryContributor("morning_wood", morningWoodEval.trendDirection, morningWoodEval.median7d, morningWoodEval.confidence, Provenance("wellbeing_daily", "evaluateSubjective", null))
    }
    if (arousalEval != null && arousalEval.confidence.met) {
        contributors += RecoveryContributor("arousal", arousalEval.trendDirection, arousalEval.median7d, arousalEval.confidence, Provenance("wellbeing_daily", "evaluateSubjective", null))
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

    val balance = recoveryBalance(contributors)
    return DynamicRecoveryResult(recoveryStateOf(balance), contributors, Confidence(contributors.size, MIN_CONTRIBUTORS_FOR_STATE), balance = balance)
}
