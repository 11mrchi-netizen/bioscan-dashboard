package com.bioscan.fieldterminal.domain

import java.time.LocalDate

// Phase A4 (Analysis Layer). Evaluation Method Spec, Category 8 (OSTRC-H2
// half): weekly per-body-area injury/overuse scoring. Deliberately computes
// only the trajectory gate and latest score here -- no injury risk score is
// computed anywhere in this category (Bahr's screening critique +
// Bittencourt/Meeuwisse's complex-systems model, per the spec's own
// reasoning: single-factor screening doesn't predict injury). The spec's
// "load context panel" (OSTRC + TSB + days-without-rest + soreness, shown
// adjacent, never arithmetically combined) is a presentation-layer
// concern -- those other three numbers already exist as their own
// evaluations (Categories 6, 7, 3); this function only ever returns this
// one category's own numbers.
data class OstrcEvaluation(
    val bodyArea: String,
    val latestSeverityScore: Int?,
    val latestCheckDate: LocalDate?,
    val confidence: Confidence,
)

private const val GATE_WEEKS = 3

// entries: one (check_date, severity_score) pair per real weekly check-in
// for a single body area -- caller groups by body_area first (see
// data/AnalysisRepository.kt).
fun evaluateOstrc(bodyArea: String, entries: List<Pair<LocalDate, Int>>): OstrcEvaluation {
    if (entries.isEmpty()) return OstrcEvaluation(bodyArea, null, null, Confidence(0, GATE_WEEKS))

    val sorted = entries.sortedByDescending { it.first }
    val latest = sorted.first()

    // Consecutive weekly cadence walking backward from the latest entry's
    // own week -- not from "today" -- since OSTRC gets filled in whenever
    // the person does it, not necessarily calendar-aligned to today; gating
    // on weeks-since-today would wrongly punish someone who checked in
    // yesterday just because today hasn't rolled into a new week.
    var consecutiveWeeks = 0
    var weekEnd = latest.first
    while (true) {
        val weekStart = weekEnd.minusDays(6)
        val hasEntry = sorted.any { !it.first.isBefore(weekStart) && !it.first.isAfter(weekEnd) }
        if (!hasEntry) break
        consecutiveWeeks++
        weekEnd = weekStart.minusDays(1)
    }

    return OstrcEvaluation(
        bodyArea = bodyArea,
        latestSeverityScore = latest.second,
        latestCheckDate = latest.first,
        confidence = Confidence(consecutiveWeeks.coerceAtMost(GATE_WEEKS), GATE_WEEKS),
    )
}
