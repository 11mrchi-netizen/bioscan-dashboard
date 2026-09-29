package com.bioscan.fieldterminal.domain.aging

import com.bioscan.fieldterminal.domain.Confidence
import com.bioscan.fieldterminal.domain.analysis.Provenance
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.Period

// DAV-221 (09A Aging Profile, Phase 1). Canonical shape every aging model
// resolves to -- PhenoAge and functional Cardio Age today, KDM/homeostatic
// dysregulation/DNAm/proteomic/metabolomic/organ-system clocks later without
// changing this contract (per DAV-221's own "downstream layers can consume
// without knowing model internals" criterion). Reuses this app's existing
// Confidence (domain/EvalState.kt) and Provenance
// (domain/analysis/AnalysisLayer2Contract.kt) rather than inventing parallel
// versions of either.
//
// Four concepts stay distinct per DAV-221/227 -- never conflated:
//  - biologicalAge: a model-specific age-equivalent estimate.
//  - ageAcceleration: biologicalAge - chronologicalAgeYears, model-specific.
//  - agingPace: a rate/velocity measure (e.g. DunedinPACE-style); most models,
//    including both of Phase 1's, don't define one -- null, never fabricated
//    from an age subtraction (DAV-227 explicitly forbids that shortcut).
// Never a composite across models -- each AgingModel produces its own result,
// presented in parallel (DAV-230).
enum class AgingModel(val label: String) {
    PHENOAGE("PhenoAge"),
    FUNCTIONAL_CARDIO("Cardio Age"),
    // Room left here for KDM, HOMEOSTATIC_DYSREGULATION, and molecular clocks
    // (DAV-224/225/228) -- no code in this package depends on this enum being
    // closed to just the two above.
}

data class BiologicalAgeResult(
    val model: AgingModel,
    val modelVersion: String,
    val chronologicalAgeYears: Double,
    val biologicalAge: Double?,
    val ageAcceleration: Double?,
    val agingPace: Double? = null,
    val confidence: Confidence,
    val provenance: Provenance,
    // Which lab_results/wearable rows this result was computed from, so it's
    // reproducible and auditable (DAV-221's "input snapshot / observation
    // IDs" requirement) without re-deriving the query that found them.
    val inputObservationIds: List<Long> = emptyList(),
    // The date of the underlying observation (e.g. the lab draw, or the
    // VO2max reading) -- kept distinct from calculatedAt (DAV-227's own
    // "keep input dates distinct from calculation dates" requirement) so a
    // history view plots by when the sample was actually taken, not by when
    // the app happened to run the formula.
    val observedAt: LocalDate,
    val calculatedAt: OffsetDateTime,
    // Set (and biologicalAge/ageAcceleration left null) when a required input
    // is missing -- never a guessed or imputed result (DAV-223's own
    // "reject or return an explicit unavailable state" requirement).
    val unavailableReason: String? = null,
) {
    val isAvailable: Boolean get() = biologicalAge != null
}

// Whole-years-elapsed age -- the convention every published biological-age
// formula (PhenoAge included) expects, not a fractional age.
fun chronologicalAgeYears(dateOfBirth: LocalDate, asOf: LocalDate = LocalDate.now()): Int =
    Period.between(dateOfBirth, asOf).years
