package com.bioscan.fieldterminal.domain.analysis

import com.bioscan.fieldterminal.domain.Confidence
import com.bioscan.fieldterminal.domain.EvalState
import java.time.LocalDate

// DAV-67 (docs/analysis-layer-2/06-output-contract.md), v1.0.0. Shared shapes
// every Analysis Layer 2 domain publishes through -- DAV-180 (nutrition) is
// the first real implementation. Only DailyStateObject and the shared types
// it needs are ported here; SessionLoadVector/RollingStateSeries/
// PerformanceAnchor belong to the tickets that first need them (DAV-56/59/61).

data class Provenance(val origin: String, val algorithm: String?, val algorithmVersion: String?)

enum class CompletenessTier { MINIMAL, PARTIAL, FULL }

// DAV-66 (docs/analysis-layer-2/05-confidence-propagation.md). present/ideal
// name real input fields or data sources (e.g. "meal_items", "hydration_daily"),
// never abstract categories -- that doc's own stated rule.
data class InputCompleteness(val present: Set<String>, val ideal: Set<String>) {
    val tier: CompletenessTier get() = when {
        present == ideal && ideal.isNotEmpty() -> CompletenessTier.FULL
        present.size <= 1 -> CompletenessTier.MINIMAL
        else -> CompletenessTier.PARTIAL
    }
}

data class ObservationRef(val table: String, val id: String)

enum class StateDimension {
    ENERGY_INTAKE, PROTEIN_INTAKE, CARB_INTAKE, FAT_INTAKE, FIBER_INTAKE,
    SUGAR_INTAKE, SODIUM_INTAKE, FLUID_INTAKE, CAFFEINE_INTAKE, EFFECTIVE_HYDRATION,
    // DAV-144 (docs/trail-intelligence/03-trail-metric-registry.md). Session-
    // level trail dimensions -- per-climb/per-descent detail stays in the
    // domain/trail/*.kt outputs themselves, not flattened into this generic
    // contract (DAV-144's own "no trail-specific logic in generic UI/state
    // consumers" only applies to these session-summary scalars).
    MOUNTAIN_INDEX, KM_EFFORT, ELEVATION_GAIN_M, ELEVATION_LOSS_M, AVERAGE_VAM,
    CLIMB_CONSISTENCY, DESCENT_CONSISTENCY, UPHILL_EFFICIENCY, DOWNHILL_EFFICIENCY,
    UPHILL_RUN_PERCENT, VAM_DEGRADATION, PACE_DEGRADATION, HR_DECOUPLING,
}

data class DimensionState(
    val value: Double?,
    val evalState: EvalState,
    val confidence: Confidence,
    val breadth: InputCompleteness,
    val provenance: Provenance,
    val contributingObservations: List<ObservationRef>,
)

data class DailyStateObject(
    val date: LocalDate,
    val dimensions: Map<StateDimension, DimensionState>,
)

// DAV-144. A lightweight, per-session sibling of DailyStateObject -- not
// DAV-67's full SessionLoadVector (that shape's own LoadDimension taxonomy
// and competingModels apparatus belong to DAV-56, which isn't built yet).
// Trail metrics need a per-session container today; when DAV-56 lands,
// these dimensions become real candidates to feed its MECHANICAL/
// CARDIOVASCULAR/etc. load calculation, per DAV-144's own text -- not
// something to pre-build here.
data class SessionStateObject(
    val sessionId: Long,
    val date: LocalDate,
    val dimensions: Map<StateDimension, DimensionState>,
)

// DAV-192 (docs/analysis-layer-2/19-comparison-layer-contract.md), v1.0.0.
// The stable shape every comparison/ranking calculation (milestone 10A)
// publishes through -- reuses Provenance/Confidence/InputCompleteness
// unchanged, per doc 06's own "every shape carries provenance and
// confidence as named fields" rule, rather than inventing a parallel set.
// Metric identity is a plain String key into doc 02's own canonical metric
// registry (e.g. "hrv", "resting_heart_rate") -- that doc is already
// DAV-53's canonical-name authority; this contract extends it (via
// MetricDirectionality, DAV-195) rather than forking a second enum.
enum class ComparisonType { POPULATION, COHORT, PERSONAL_HISTORY, CONTEXTUAL, EFFORT_EFFICIENCY }

enum class Directionality { HIGHER_BETTER, LOWER_BETTER, OPTIMAL_RANGE, TARGET_VALUE, NON_DIRECTIONAL }

// Explicit non-OK states per DAV-192/199's own requirement: an invalid or
// insufficient comparison must say so, never silently omit or fabricate a
// number. NO_REFERENCE: nothing to compare against at all (e.g. no
// benchmark row yet). INSUFFICIENT_DATA: a reference exists in principle but
// this account/observation doesn't clear its data-density gate.
// NON_COMPARABLE: a reference exists but DAV-198's eligibility rules reject
// this observation. STALE_REFERENCE: the reference itself is out of its
// validity period.
enum class ComparisonState { OK, NO_REFERENCE, INSUFFICIENT_DATA, LOW_CONFIDENCE, NON_COMPARABLE, STALE_REFERENCE }

data class ComparisonResult(
    val metric: String,
    val comparisonType: ComparisonType,
    val state: ComparisonState,
    val rawValue: Double?,
    val normalizedValue: Double?,
    val referenceValue: Double?,
    val delta: Double?,
    val standardizedDelta: Double?,
    val percentile: Double?,
    val rank: Int?,
    val rankDenominator: Int?,
    val directionality: Directionality,
    val referenceIdentity: String,
    val referenceVersion: String,
    val confidence: Confidence,
    val breadth: InputCompleteness,
    val provenance: Provenance,
    // DAV-196 follow-up, found while sourcing the first real population
    // artifact (Tudor-Locke & Bassett's steps/day categories): not every
    // legitimate reference is percentile-shaped. A named category
    // (e.g. "somewhat_active") is a real, full-confidence comparison result,
    // just not a percentile one -- forcing it through `percentile` would
    // either fabricate a number the source doesn't support, or (the
    // original design here) get demoted to LOW_CONFIDENCE, which
    // mischaracterizes a perfectly good categorical reference as deficient.
    // Null whenever the reference is percentile-based or doesn't apply.
    val bandLabel: String? = null,
)
