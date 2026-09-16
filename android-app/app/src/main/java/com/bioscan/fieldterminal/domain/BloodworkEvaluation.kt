package com.bioscan.fieldterminal.domain

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.sqrt

// Phase A4 (Analysis Layer). Evaluation Method Spec, Category 9
// (Bloodwork): with roughly one draw a year, the only comparison this
// category is entitled to make is a two-point delta against a real,
// marker-specific noise threshold (Reference Change Value) -- never a
// rolling window, never a slope, never a trend line (see AnalysisScreen's
// own display -- Phase A5 will render this as a dot plot, not a line
// chart, per the spec's explicit instruction not to imply a continuity
// that annual data doesn't have).
enum class BloodworkTrendState { Stable, ShiftUp, ShiftDown }

data class BloodworkMarkerEvaluation(
    val markerName: String,
    val unit: String?,
    val refLow: Double?,
    val refHigh: Double?,
    val latestValue: Double,
    val latestDrawDate: LocalDate,
    val previousValue: Double?,
    val delta: Double?, // raw absolute delta, for display only
    val deltaPercent: Double?, // the value RCV is actually compared against -- RCV is a %CV-derived threshold, not an absolute unit
    val rcv: Double?, // a percentage; null when this marker has no BIOLOGICAL_VARIATION citation
    // null covers two real, distinct reasons, both surfaced by the UI
    // rather than collapsed into one generic "can't tell" state: only one
    // draw ever (previousValue == null), or a real delta exists but no
    // noise threshold to judge it against (rcv == null).
    val state: BloodworkTrendState?,
    val indexOfIndividuality: Double?, // null alongside rcv
)

// The spec's own shortcut: RCV = sqrt(2) * 1.96 * sqrt(CVA^2 + CVI^2),
// using the standard desirable-analytical-performance default
// CVA = 0.5*CVI when a real analytical CV isn't available (it usually
// isn't) -- which collapses to RCV ~= 3.1 * CVI.
private const val RCV_MULTIPLIER = 3.1

// draws: one (draw_date, value) pair per real lab_results row for this one
// marker, across every real draw the account has -- caller groups by
// marker_name first (see data/AnalysisRepository.kt).
fun evaluateBloodworkMarker(
    markerName: String,
    draws: List<Pair<LocalDate, Double>>,
    unit: String?,
    refLow: Double?,
    refHigh: Double?,
): BloodworkMarkerEvaluation {
    val sorted = draws.sortedBy { it.first }
    val latest = sorted.last()
    val previous = sorted.getOrNull(sorted.size - 2)

    val bv = BIOLOGICAL_VARIATION[markerName]
    val rcv = bv?.let { RCV_MULTIPLIER * it.cvi }
    val previousValue = previous?.second
    val delta = previousValue?.let { latest.second - it }
    // RCV is derived from a coefficient of variation, so it's a percentage
    // -- comparing it against the raw absolute delta would silently compare
    // two different units (a real bug caught during this phase's own
    // real-data verification: Hematocrit's raw delta is 3.2, which looks
    // tiny next to an RCV of ~8.4, but the correct comparison is against
    // the *percent* change, which is a real, genuine 8.44% -- just over
    // Hematocrit's own 8.37% RCV).
    val deltaPercent = if (delta != null && previousValue != null && previousValue != 0.0) delta / previousValue * 100 else null

    val state = if (deltaPercent != null && rcv != null) {
        when {
            abs(deltaPercent) > rcv -> if (deltaPercent > 0) BloodworkTrendState.ShiftUp else BloodworkTrendState.ShiftDown
            else -> BloodworkTrendState.Stable
        }
    } else {
        null
    }

    // Index of Individuality -- II < 0.6 means the population reference
    // range is of limited use for this marker on this person specifically;
    // read it against your own prior value instead. Only computable where
    // a CVI (and its paired CVG) exists.
    val indexOfIndividuality = bv?.let {
        val cva = 0.5 * it.cvi
        sqrt(cva * cva + it.cvi * it.cvi) / it.cvg
    }

    return BloodworkMarkerEvaluation(
        markerName = markerName,
        unit = unit,
        refLow = refLow,
        refHigh = refHigh,
        latestValue = latest.second,
        latestDrawDate = latest.first,
        previousValue = previousValue,
        delta = delta,
        deltaPercent = deltaPercent,
        rcv = rcv,
        state = state,
        indexOfIndividuality = indexOfIndividuality,
    )
}
