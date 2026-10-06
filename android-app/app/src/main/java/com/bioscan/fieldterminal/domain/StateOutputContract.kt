package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.domain.analysis.InputCompleteness
import java.time.Instant

// DAV-178. Stable contract every cross-domain state model (DAV-174+) must
// implement. UI renders from these fields only -- no model math in screens.
// Existing per-stream types (SwcEvaluation, DynamicRecoveryResult, etc.)
// predate this contract and are grandfathered; new types conform from day one.
//
// missingness: no separate field -- inputCoverage.ideal - inputCoverage.present
// gives the gap set, and `missingness` extension below computes it on demand.
// direction/trend: encoded in `state` (ShiftUp/ShiftDown/Stable/Unstable) --
// a redundant top-level Direction field would duplicate EvalState semantics.
interface StateOutputContract {
    val state: EvalState
    val confidence: Confidence
    val modelVersion: String
    val inputCoverage: InputCompleteness
    val contributingDimensions: List<String>
    val calculatedAt: Instant
}

val StateOutputContract.missingness: Set<String> get() = inputCoverage.ideal - inputCoverage.present
