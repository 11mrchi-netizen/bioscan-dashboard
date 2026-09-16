package com.bioscan.fieldterminal.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

// Phase A4 (Analysis Layer). Evaluation Method Spec, Category 3 (Subjective
// self-report: energy/mood/stress/soreness): ordinal-safe throughout --
// median + IQR for level, Mann-Kendall for direction, a fixed minimum shift
// for change detection. Deliberately no mean/SD/z-score anywhere here: a
// 0-10 daily rating is ordinal (the distance from 6 to 7 isn't the same
// quantity as 2 to 3) with hard floor/ceiling effects, and this one function
// is called once per dimension -- energy, mood, stress, soreness are never
// summed into a Hooper Index or any other composite, per the spec's own
// explicit "keep the four items displayed separately" rule.
//
// Real ambiguity in the source spec, resolved and flagged rather than
// silently picked: the spec's own Gate section says "30 days before any
// baseline median," but its formula section names the comparison value
// `median_baseline_60d` (Category 1's own baseline-window naming, likely
// carried over rather than a deliberate 60-day instruction for this
// category). This implementation follows the Gate section -- the more
// concrete, operational text -- and uses a 30-day baseline window.
data class SubjectiveEvaluation(
    val state: EvalState,
    val confidence: Confidence,
    val median7d: Double?,
    val iqr7d: Double?,
    val medianBaseline30d: Double?,
    // +1/-1 only when Mann-Kendall clears p<0.05 over the trailing 14 days;
    // null renders no arrow at all, per the spec's own "otherwise render no
    // arrow" rule -- this is never gated together with the main state below,
    // since the spec treats the arrow as an independent decoration on top
    // of whatever the level/IQR check already decided.
    val trendDirection: Int?,
)

private const val BASELINE_WINDOW_DAYS = 30
private const val LEVEL_WINDOW_DAYS = 7
private const val MIN_LEVEL_READINGS = 5
private const val TREND_WINDOW_DAYS = 14
private const val MIN_TREND_READINGS = 10
private const val SHIFT_THRESHOLD = 2.0 // points on the 0-10 scale
private const val UNSTABLE_IQR_THRESHOLD = 4.0

fun evaluateSubjective(dailyValues: List<Pair<LocalDate, Double>>, asOf: LocalDate = LocalDate.now()): SubjectiveEvaluation {
    val past = dailyValues.filter { !it.first.isAfter(asOf) }
    if (past.isEmpty()) return SubjectiveEvaluation(EvalState.NoData, Confidence(0, BASELINE_WINDOW_DAYS), null, null, null, null)

    val earliest = past.minOf { it.first }
    if (ChronoUnit.DAYS.between(earliest, asOf) < BASELINE_WINDOW_DAYS - 1) {
        val daysOfHistory = (ChronoUnit.DAYS.between(earliest, asOf) + 1).toInt().coerceAtMost(BASELINE_WINDOW_DAYS)
        return SubjectiveEvaluation(EvalState.Building, Confidence(daysOfHistory, BASELINE_WINDOW_DAYS), null, null, null, null)
    }

    val window7 = windowEndingAt(past, asOf, LEVEL_WINDOW_DAYS)
    if (window7.size < MIN_LEVEL_READINGS) {
        return SubjectiveEvaluation(EvalState.Building, Confidence(window7.size, MIN_LEVEL_READINGS), null, null, null, null)
    }

    val window30 = windowEndingAt(past, asOf, BASELINE_WINDOW_DAYS)
    val median7d = median(window7.map { it.second })
    val iqr7d = interquartileRange(window7.map { it.second })
    val medianBaseline30d = median(window30.map { it.second })

    val state = when {
        iqr7d >= UNSTABLE_IQR_THRESHOLD -> EvalState.Unstable
        abs(median7d - medianBaseline30d) >= SHIFT_THRESHOLD -> if (median7d > medianBaseline30d) EvalState.ShiftUp else EvalState.ShiftDown
        else -> EvalState.Stable
    }

    val window14 = windowEndingAt(past, asOf, TREND_WINDOW_DAYS)
    val trendDirection = if (window14.size >= MIN_TREND_READINGS) {
        val mk = mannKendall(window14.sortedBy { it.first }.map { it.second })
        if (mk.significant) (if (mk.tau > 0) 1 else -1) else null
    } else {
        null
    }

    return SubjectiveEvaluation(state, Confidence(window7.size, MIN_LEVEL_READINGS), median7d, iqr7d, medianBaseline30d, trendDirection)
}
