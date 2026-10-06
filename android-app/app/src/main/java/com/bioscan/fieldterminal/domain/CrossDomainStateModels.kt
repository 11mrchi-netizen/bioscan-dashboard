package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.domain.analysis.InputCompleteness
import java.time.Instant

// DAV-174. Four separate cross-domain state dimensions, each implementing
// StateOutputContract. No composite score -- each dimension is its own
// signal with its own confidence gate. UI renders them independently.
//
// Factory functions take existing per-domain evaluations as inputs so no
// math is duplicated here. Model v1 uses TSB as the primary signal for
// fatigue/fitness/performance; HRV cross-validation deferred until
// ATL/HRV correlation has enough data to be meaningful.
const val CROSS_DOMAIN_MODEL_VERSION = "v1"

// Recovery: can I perform well today?
// Wraps DynamicRecoveryResult; use .toRecoveryState() below.
data class RecoveryState(
    override val state: EvalState,
    override val confidence: Confidence,
    override val modelVersion: String = CROSS_DOMAIN_MODEL_VERSION,
    override val inputCoverage: InputCompleteness,
    override val contributingDimensions: List<String>,
    override val calculatedAt: Instant,
    val contributors: List<RecoveryContributor>,
) : StateOutputContract

// Fatigue: how much acute fatigue do I carry?
// Primary signal: inverted TSB (negative TSB = high fatigue).
// ponytail: v1 TSB-only; add HRV cross-validation when coverage improves.
data class FatigueState(
    override val state: EvalState,
    override val confidence: Confidence,
    override val modelVersion: String = CROSS_DOMAIN_MODEL_VERSION,
    override val inputCoverage: InputCompleteness,
    override val contributingDimensions: List<String>,
    override val calculatedAt: Instant,
    val atl: Double?,
    val tsb: Double?,
) : StateOutputContract

// Fitness/Capacity: how much chronic adaptation have I built?
// Primary signal: CTL (42d EWMA). Building until 42d gate; Stable once met.
// ponytail: v1 doesn't derive ShiftUp/ShiftDown -- needs CTL trend series.
//   Add when trainingLoadSeries() is wired into the call site.
data class FitnessCapacityState(
    override val state: EvalState,
    override val confidence: Confidence,
    override val modelVersion: String = CROSS_DOMAIN_MODEL_VERSION,
    override val inputCoverage: InputCompleteness,
    override val contributingDimensions: List<String>,
    override val calculatedAt: Instant,
    val ctl: Double?,
) : StateOutputContract

// Performance Trend: am I in form, peaking, or fatigued?
// Primary signal: TSB band (CTL_yesterday - ATL_yesterday).
data class PerformanceTrendState(
    override val state: EvalState,
    override val confidence: Confidence,
    override val modelVersion: String = CROSS_DOMAIN_MODEL_VERSION,
    override val inputCoverage: InputCompleteness,
    override val contributingDimensions: List<String>,
    override val calculatedAt: Instant,
    val tsb: Double?,
    val tsbBand: String?,
) : StateOutputContract

// --- Factory functions ---

private val RECOVERY_IDEAL = setOf(
    "sleep", "hrv", "resting_heart_rate", "training_load",
    "energy", "subjective_stress", "soreness",
)

fun DynamicRecoveryResult.toRecoveryState(now: Instant = Instant.now()): RecoveryState {
    val dimensionNames = contributors.map { it.dimension }
    return RecoveryState(
        state = state,
        confidence = confidence,
        inputCoverage = InputCompleteness(dimensionNames.toSet(), RECOVERY_IDEAL),
        contributingDimensions = dimensionNames,
        calculatedAt = now,
        contributors = contributors,
    )
}

fun computeFatigueState(
    loadEval: TrainingLoadEvaluation,
    // Optional: included in coverage tracking when confident, not yet used to
    // modify state in v1 -- see ponytail comment on FatigueState above.
    hrvEval: SwcEvaluation? = null,
    now: Instant = Instant.now(),
): FatigueState {
    val ideal = setOf("training_load", "hrv")
    val present = mutableSetOf<String>()
    if (loadEval.confidence.met && loadEval.tsb != null) present += "training_load"
    if (hrvEval != null && hrvEval.confidence.met) present += "hrv"

    if (!loadEval.confidence.met || loadEval.tsb == null) {
        return FatigueState(
            state = loadEval.state,
            confidence = loadEval.confidence,
            inputCoverage = InputCompleteness(present, ideal),
            contributingDimensions = emptyList(),
            calculatedAt = now,
            atl = loadEval.atl,
            tsb = loadEval.tsb,
        )
    }

    val fatigueState = when {
        loadEval.tsb < TSB_HEAVILY_LOADED_BELOW -> EvalState.Unstable
        loadEval.tsb < TSB_LOADED_BELOW -> EvalState.ShiftUp
        loadEval.tsb <= TSB_FRESHENED_FROM -> EvalState.Stable
        else -> EvalState.ShiftDown
    }

    return FatigueState(
        state = fatigueState,
        confidence = loadEval.confidence,
        inputCoverage = InputCompleteness(present, ideal),
        contributingDimensions = present.toList(),
        calculatedAt = now,
        atl = loadEval.atl,
        tsb = loadEval.tsb,
    )
}

fun computeFitnessCapacityState(
    loadEval: TrainingLoadEvaluation,
    now: Instant = Instant.now(),
): FitnessCapacityState {
    val ideal = setOf("training_load")
    val present = if (loadEval.confidence.met && loadEval.ctl != null) ideal else emptySet()
    return FitnessCapacityState(
        state = if (loadEval.confidence.met) EvalState.Stable else loadEval.state,
        confidence = loadEval.confidence,
        inputCoverage = InputCompleteness(present, ideal),
        contributingDimensions = present.toList(),
        calculatedAt = now,
        ctl = loadEval.ctl,
    )
}

fun computePerformanceTrendState(
    loadEval: TrainingLoadEvaluation,
    now: Instant = Instant.now(),
): PerformanceTrendState {
    val ideal = setOf("training_load")
    val present = if (loadEval.confidence.met) ideal else emptySet()
    return PerformanceTrendState(
        state = loadEval.state,
        confidence = loadEval.confidence,
        inputCoverage = InputCompleteness(present, ideal),
        contributingDimensions = present.toList(),
        calculatedAt = now,
        tsb = loadEval.tsb,
        tsbBand = loadEval.tsbBand,
    )
}
