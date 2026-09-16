package com.bioscan.fieldterminal.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit

// Phase A4 (Analysis Layer). Evaluation Method Spec, Category 8 (digestive/
// Bristol half): a rolling 14-day distribution, not a daily value -- a
// single BSFS reading is nearly meaningless per the spec; the pattern
// across days is the signal (Lewis & Heaton 1997: change in stool form
// correlates with change in whole-gut transit time at r=-0.65). This
// category was never given the spec's six-state vocabulary (no
// NO_DATA/BUILDING/STABLE/...) -- it gets its own pattern labels instead,
// same precedent as Category 7's flags/cadence labels.
//
// Non-medical guard, per the spec's own explicit instruction: these labels
// are descriptive only, never phrased as a condition. See BristolCard in
// ui/screens/status/AnalysisScreen.kt for the on-screen disclaimer.
enum class BristolPattern { PredominantlyFirm, PredominantlyLoose, Mixed, Typical }

data class BristolEvaluation(
    val confidence: Confidence,
    val pctHard: Double?,
    val pctNormal: Double?,
    val pctLoose: Double?,
    val pattern: BristolPattern?,
)

private const val WINDOW_DAYS = 14
private const val MIN_ENTRIES = 8

// entries: one (date, bristol_type 1-7) pair per real logged stool_log row.
// A rolling window of entries, not entries-per-distinct-day -- multiple
// same-day entries all count, matching the spec's own "logged entries"
// wording rather than a day-count.
fun evaluateBristol(entries: List<Pair<LocalDate, Int>>, asOf: LocalDate = LocalDate.now()): BristolEvaluation {
    val windowed = entries.filter { !it.first.isAfter(asOf) && ChronoUnit.DAYS.between(it.first, asOf) < WINDOW_DAYS }
    val n = windowed.size
    if (n < MIN_ENTRIES) return BristolEvaluation(Confidence(n, MIN_ENTRIES), null, null, null, null)

    val pctHard = windowed.count { it.second in 1..2 }.toDouble() / n * 100
    val pctNormal = windowed.count { it.second in 3..5 }.toDouble() / n * 100
    val pctLoose = windowed.count { it.second in 6..7 }.toDouble() / n * 100

    // 25% Rome-IV-style cut-point, used purely descriptively -- see the
    // spec's own "not diagnostic criteria" framing, not a clinical threshold.
    val pattern = when {
        pctHard >= 25 && pctLoose < 25 -> BristolPattern.PredominantlyFirm
        pctLoose >= 25 && pctHard < 25 -> BristolPattern.PredominantlyLoose
        pctHard >= 25 && pctLoose >= 25 -> BristolPattern.Mixed
        else -> BristolPattern.Typical
    }

    return BristolEvaluation(Confidence(n, MIN_ENTRIES), pctHard, pctNormal, pctLoose, pattern)
}
