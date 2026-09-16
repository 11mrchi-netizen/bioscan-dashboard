package com.bioscan.fieldterminal.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.exp
import kotlin.math.ln

// Phase A2 (Analysis Layer). Evaluation Method Spec, Category 1 (HRV & RHR):
// 7-day rolling Ln rMSSD + Smallest Worthwhile Change band, with 7-day CV as
// a parallel instability signal (Plews/Laursen/Buchheit method). The same
// SWC-band structure is reused verbatim for untransformed RHR (per the
// spec's own "apply the identical structure to resting HR" instruction) and
// for Category 2's sleep-duration sub-metric -- see evaluateSwcStream().
//
// Real, unresolved caveat carried over from this project's own history (see
// ROADMAP.md's Phase G notes): whether `wearable_daily.hrv` is actually
// rMSSD or SDNN is still genuinely unconfirmed -- Health Connect sync hasn't
// run yet, so this can't be checked against a labeled record type. This
// function assumes rMSSD (per the spec's own chosen method) and log-
// transforms it; if that assumption turns out wrong once Health Connect data
// exists, only evaluateHrv()'s call site needs to change, not this file's
// shared machinery.
data class SwcEvaluation(
    val state: EvalState,
    val confidence: Confidence,
    val baseline7d: Double?,
    val mean60d: Double?,
    val swcPct: Double?,
    val cv7d: Double?,
)

private const val BASELINE_WINDOW_DAYS = 60
private const val ROLLING_WINDOW_DAYS = 7
private const val MIN_READINGS_PER_WINDOW = 4

// Generic over any daily-value stream needing the SWC treatment -- takes
// values already in whatever space they should be compared in (log-space
// for HRV, raw for RHR and sleep duration).
fun evaluateSwcStream(dailyValues: List<Pair<LocalDate, Double>>, asOf: LocalDate = LocalDate.now()): SwcEvaluation {
    val past = dailyValues.filter { !it.first.isAfter(asOf) }
    if (past.isEmpty()) return SwcEvaluation(EvalState.NoData, Confidence(0, BASELINE_WINDOW_DAYS), null, null, null, null)

    val earliest = past.minOf { it.first }
    val daysOfHistory = (ChronoUnit.DAYS.between(earliest, asOf) + 1).toInt().coerceAtMost(BASELINE_WINDOW_DAYS)
    if (ChronoUnit.DAYS.between(earliest, asOf) < BASELINE_WINDOW_DAYS - 1) {
        // Gate: "BUILDING until 60 days of readings exist (SWC needs a
        // stable CV estimate)" -- gated on calendar span reaching back 60
        // days, not on a fixed reading count, so real gaps ("minimal
        // irregularity") don't block the gate the way a strict count would.
        return SwcEvaluation(EvalState.Building, Confidence(daysOfHistory, BASELINE_WINDOW_DAYS), null, null, null, null)
    }

    val window7 = windowEndingAt(past, asOf, ROLLING_WINDOW_DAYS)
    if (window7.size < MIN_READINGS_PER_WINDOW) {
        return SwcEvaluation(EvalState.Building, Confidence(window7.size, MIN_READINGS_PER_WINDOW), null, null, null, null)
    }

    val window60 = windowEndingAt(past, asOf, BASELINE_WINDOW_DAYS)
    val baseline7d = mean(window7.map { it.second })
    val mean60d = mean(window60.map { it.second })
    val sd60d = populationStdDev(window60.map { it.second })
    val cvBaseline = if (mean60d != 0.0) sd60d / mean60d * 100 else 0.0
    val swcPct = 0.5 * cvBaseline
    val swcAbs = mean60d * swcPct / 100
    val bandLow = mean60d - swcAbs
    val bandHigh = mean60d + swcAbs

    val cv7dToday = cv7dAt(past, asOf)
    val historicalCv7d = (0 until BASELINE_WINDOW_DAYS).mapNotNull { offset -> cv7dAt(past, asOf.minusDays(offset.toLong())) }
    val isUnstable = cv7dToday != null && historicalCv7d.isNotEmpty() &&
        median(historicalCv7d).let { it > 0 && cv7dToday > 1.5 * it }

    val state = when {
        isUnstable -> EvalState.Unstable
        baseline7d > bandHigh -> EvalState.ShiftUp
        baseline7d < bandLow -> EvalState.ShiftDown
        else -> EvalState.Stable
    }

    return SwcEvaluation(state, Confidence(window7.size, MIN_READINGS_PER_WINDOW), baseline7d, mean60d, swcPct, cv7dToday)
}

private fun windowEndingAt(values: List<Pair<LocalDate, Double>>, end: LocalDate, days: Int): List<Pair<LocalDate, Double>> {
    val start = end.minusDays((days - 1).toLong())
    return values.filter { !it.first.isBefore(start) && !it.first.isAfter(end) }
}

private fun cv7dAt(values: List<Pair<LocalDate, Double>>, date: LocalDate): Double? {
    val window = windowEndingAt(values, date, ROLLING_WINDOW_DAYS)
    if (window.size < MIN_READINGS_PER_WINDOW) return null
    val vals = window.map { it.second }
    val m = mean(vals)
    if (m == 0.0) return null
    return populationStdDev(vals) / m * 100
}

// Log-transformed per the spec ("log-transform first, always") -- ln(rMSSD).
fun evaluateHrv(hrvDaily: List<Pair<LocalDate, Double>>, asOf: LocalDate = LocalDate.now()): SwcEvaluation =
    evaluateSwcStream(hrvDaily.filter { it.second > 0 }.map { it.first to ln(it.second) }, asOf)

// Untransformed, per the spec's own "apply the identical structure to
// resting HR (untransformed -- RHR does not need the log)" instruction.
fun evaluateRhr(rhrDaily: List<Pair<LocalDate, Double>>, asOf: LocalDate = LocalDate.now()): SwcEvaluation =
    evaluateSwcStream(rhrDaily, asOf)

// HRV's baseline7d/mean60d are in ln-space (see evaluateHrv) -- callers
// exponentiate back to real ms for display. RHR needs no such conversion.
fun expValue(value: Double?): Double? = value?.let { exp(it) }
