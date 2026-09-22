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
