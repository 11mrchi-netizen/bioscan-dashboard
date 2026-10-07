package com.bioscan.fieldterminal.domain.aging

import com.bioscan.fieldterminal.domain.Confidence
import com.bioscan.fieldterminal.domain.analysis.Provenance
import java.time.LocalDate
import java.time.OffsetDateTime
import kotlin.math.exp
import kotlin.math.ln

// DAV-223 (09A Aging Profile, Phase 1). Levine et al. 2018 ("An epigenetic
// biomarker of aging for lifespan and healthspan", Aging (Albany NY) 10(4))
// PhenoAge -- the published linear combination of 9 routine biomarkers plus
// chronological age, transformed through a Gompertz mortality-hazard model
// into an age-equivalent. Coefficients and the Gompertz gamma below are the
// paper's own published values (cross-checked against multiple independent
// public PhenoAge calculators during this milestone's research pass) --
// nothing here is re-derived or approximated.
//
// Inputs are accepted in the units this app's own lab_results actually store
// them in (see lab_results table / LabsRepository.kt) -- the formula's
// required units (albumin g/L, creatinine umol/L, glucose mmol/L, CRP mg/L)
// are converted internally so callers never have to think about unit algebra
// at the call site.
const val PHENOAGE_MODEL_VERSION = "levine-2018-v1"

data class PhenoAgeInputs(
    val albuminGDl: Double?,
    val creatinineMgDl: Double?,
    // "Fasting blood sugar" in this app's own lab_results marker naming.
    val glucoseMgDl: Double?,
    // hs-CRP substitutes for CRP -- the modern universal CRP assay; treated
    // as equivalent per standard practice, not a data gap.
    val crpMgDl: Double?,
    val lymphocytePercent: Double?,
    val mcvFl: Double?,
    val rdwPercent: Double?,
    val alkalinePhosphataseUL: Double?,
    val wbc10e3UL: Double?,
)

private const val GOMPERTZ_GAMMA = 0.0076927

fun computePhenoAge(
    inputs: PhenoAgeInputs,
    chronologicalAgeYears: Double,
    observedAt: LocalDate,
    provenance: Provenance,
    inputObservationIds: List<Long> = emptyList(),
): BiologicalAgeResult {
    val missing = buildList {
        if (inputs.albuminGDl == null) add("albumin")
        if (inputs.creatinineMgDl == null) add("creatinine")
        if (inputs.glucoseMgDl == null) add("glucose")
        if (inputs.crpMgDl == null) add("CRP")
        if (inputs.lymphocytePercent == null) add("lymphocyte %")
        if (inputs.mcvFl == null) add("MCV")
        if (inputs.rdwPercent == null) add("RDW")
        if (inputs.alkalinePhosphataseUL == null) add("alkaline phosphatase")
        if (inputs.wbc10e3UL == null) add("WBC")
    }
    if (missing.isNotEmpty()) {
        return unavailable(chronologicalAgeYears, observedAt, provenance, "Missing: ${missing.joinToString(", ")}")
    }

    val albuminGL = inputs.albuminGDl!! * 10.0
    val creatinineUmolL = inputs.creatinineMgDl!! * 88.4
    val glucoseMmolL = inputs.glucoseMgDl!! / 18.016
    val crpMgL = inputs.crpMgDl!! * 10.0

    val xb = -19.907 -
        0.0336 * albuminGL +
        0.0095 * creatinineUmolL +
        0.1953 * glucoseMmolL +
        0.0954 * ln(crpMgL) -
        0.0120 * inputs.lymphocytePercent!! +
        0.0268 * inputs.mcvFl!! +
        0.3306 * inputs.rdwPercent!! +
        0.00188 * inputs.alkalinePhosphataseUL!! +
        0.0554 * inputs.wbc10e3UL!! +
        0.0804 * chronologicalAgeYears

    val mortalityScore = 1 - exp(-exp(xb) * (exp(120 * GOMPERTZ_GAMMA) - 1) / GOMPERTZ_GAMMA)
    val phenoAge = 141.50225 + ln(-0.00553 * ln(1 - mortalityScore)) / 0.090165

    if (phenoAge.isNaN() || phenoAge.isInfinite()) {
        return unavailable(chronologicalAgeYears, observedAt, provenance, "Inputs produced a non-finite result (outside the model's documented domain)")
    }

    return BiologicalAgeResult(
        model = AgingModel.PHENOAGE,
        modelVersion = PHENOAGE_MODEL_VERSION,
        chronologicalAgeYears = chronologicalAgeYears,
        biologicalAge = phenoAge,
        ageAcceleration = phenoAge - chronologicalAgeYears,
        confidence = Confidence(9, 9),
        provenance = provenance,
        inputObservationIds = inputObservationIds,
        observedAt = observedAt,
        calculatedAt = OffsetDateTime.now(),
    )
}

private fun unavailable(chronologicalAgeYears: Double, observedAt: LocalDate, provenance: Provenance, reason: String) = BiologicalAgeResult(
    model = AgingModel.PHENOAGE,
    modelVersion = PHENOAGE_MODEL_VERSION,
    chronologicalAgeYears = chronologicalAgeYears,
    biologicalAge = null,
    ageAcceleration = null,
    confidence = Confidence(0, 9),
    provenance = provenance,
    observedAt = observedAt,
    calculatedAt = OffsetDateTime.now(),
    unavailableReason = reason,
)

// One real lab_draws row plus whichever lab_results rows (by marker_name ->
// value, and their row ids for provenance) belong to it -- same "join by
// draw_id in Kotlin, not a relational embed" convention
// AnalysisModels.kt/TrainingCyclesRepository.kt already follow.
data class LabDraw(val id: Long, val date: LocalDate, val markerValues: Map<String, Double>, val markerIds: Map<String, Long>)

val PHENOAGE_REQUIRED_MARKERS = setOf(
    "Albumin", "Creatinine", "Fasting blood sugar", "hs-CRP",
    "Lymphocytes %", "MCV", "RDW", "Alkaline phosphatase", "WBC",
)

fun LabDraw.toPhenoAgeInputs() = PhenoAgeInputs(
    albuminGDl = markerValues["Albumin"],
    creatinineMgDl = markerValues["Creatinine"],
    glucoseMgDl = markerValues["Fasting blood sugar"],
    crpMgDl = markerValues["hs-CRP"],
    lymphocytePercent = markerValues["Lymphocytes %"],
    mcvFl = markerValues["MCV"],
    rdwPercent = markerValues["RDW"],
    alkalinePhosphataseUL = markerValues["Alkaline phosphatase"],
    wbc10e3UL = markerValues["WBC"],
)
