package com.bioscan.fieldterminal.domain.aging

import com.bioscan.fieldterminal.domain.Confidence
import com.bioscan.fieldterminal.domain.analysis.Provenance
import java.time.LocalDate
import java.time.OffsetDateTime

// DAV-226 (09A Aging Profile, Phase 1) "Cardio Age" extension: a functional
// (not clinical/molecular) age-equivalent from measured VO2max, using the
// FRIEND registry / Cooper Institute Aerobics Center Longitudinal Study
// 50th-percentile-by-age-decade norms as tabulated in ACSM's Guidelines for
// Exercise Testing and Prescription (11th ed., Table 4.7) -- cross-checked
// against two independent published transcriptions of the same table during
// this milestone's research pass. Age-decade midpoints stand in for each
// bracket (e.g. 24.5 for "20-29"); linear interpolation between them turns
// the banded table into a continuous age-equivalent rather than snapping to
// a category, since DAV-226 asks for an age, not a band.
const val CARDIO_AGE_MODEL_VERSION = "friend-acsm11-v1"

// (ageMidpointYears, medianVo2MaxMlKgMin), youngest-to-oldest.
private val VO2MAX_MEDIAN_MEN = listOf(24.5 to 48.0, 34.5 to 42.4, 44.5 to 37.8, 54.5 to 32.6, 64.5 to 28.2, 74.5 to 24.4)
private val VO2MAX_MEDIAN_WOMEN = listOf(24.5 to 37.6, 34.5 to 30.2, 44.5 to 26.7, 54.5 to 23.4, 64.5 to 20.0, 74.5 to 18.3)

// Inverts the (age -> median VO2max) table via linear interpolation: given a
// measured VO2max, finds the age whose tabulated median VO2max matches it.
// Both axes are monotonic (VO2max falls with age), so this is a well-defined
// piecewise-linear inversion. Clamped, not null, outside the table's own
// measured range -- same convention PopulationComparison.kt's
// interpolatePercentile() already commits to ("a value at or below the
// lowest published point reads as that lowest percentile, not below it").
// A VO2max fitter than the youngest bracket's median (48.0 ml/kg/min for
// men, 20-29) is common for trained runners/cyclists in their 20s-30s, not
// just elite outliers -- returning null there made the model unavailable
// for a large share of active users, not a rare edge case. Clamping to the
// youngest tabulated age is an honest floor ("at least this young"), not
// extrapolation past what the reference population measured.
private fun interpolateAgeForVo2Max(vo2max: Double, table: List<Pair<Double, Double>>): Double {
    val youngest = table.first()
    val oldest = table.last()
    if (vo2max >= youngest.second) return youngest.first
    if (vo2max <= oldest.second) return oldest.first

    for (i in 0 until table.size - 1) {
        val (ageA, vo2A) = table[i]
        val (ageB, vo2B) = table[i + 1]
        if (vo2max <= vo2A && vo2max >= vo2B) {
            val fraction = (vo2A - vo2max) / (vo2A - vo2B)
            return ageA + fraction * (ageB - ageA)
        }
    }
    error("unreachable: $vo2max is within [oldest.second, youngest.second] but matched no table segment")
}

fun cardioFunctionalAge(vo2max: Double, sex: String?, chronologicalAgeYears: Double, observedAt: LocalDate, provenance: Provenance): BiologicalAgeResult {
    val table = when (sex) {
        "male" -> VO2MAX_MEDIAN_MEN
        "female" -> VO2MAX_MEDIAN_WOMEN
        else -> null
    }
    if (table == null) {
        return unavailable(chronologicalAgeYears, observedAt, provenance, "Sex not set -- the reference table is sex-specific (Settings > Profile)")
    }

    val cardioAge = interpolateAgeForVo2Max(vo2max, table)

    return BiologicalAgeResult(
        model = AgingModel.FUNCTIONAL_CARDIO,
        modelVersion = CARDIO_AGE_MODEL_VERSION,
        chronologicalAgeYears = chronologicalAgeYears,
        biologicalAge = cardioAge,
        ageAcceleration = cardioAge - chronologicalAgeYears,
        confidence = Confidence(1, 1),
        provenance = provenance,
        observedAt = observedAt,
        calculatedAt = OffsetDateTime.now(),
    )
}

private fun unavailable(chronologicalAgeYears: Double, observedAt: LocalDate, provenance: Provenance, reason: String) = BiologicalAgeResult(
    model = AgingModel.FUNCTIONAL_CARDIO,
    modelVersion = CARDIO_AGE_MODEL_VERSION,
    chronologicalAgeYears = chronologicalAgeYears,
    biologicalAge = null,
    ageAcceleration = null,
    confidence = Confidence(0, 1),
    provenance = provenance,
    observedAt = observedAt,
    calculatedAt = OffsetDateTime.now(),
    unavailableReason = reason,
)
