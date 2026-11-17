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

