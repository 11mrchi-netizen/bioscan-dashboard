package com.bioscan.fieldterminal.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

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
    // Analysis review A2/B7: the band that decides the state, in the evaluation's own space (ln for HRV; see
    // toExpSpace()). A shift needs the 7-day mean outside BOTH the smallest worthwhile change (0.5 SD) and the noise of
    // a weekly mean (1.96 standard errors, widened for day-to-day carry-over). Drawing this band, not mean x swcPct, is
    // what keeps the chart and the pill in agreement.
    val bandLow: Double? = null,
    val bandHigh: Double? = null,
)

private const val BASELINE_WINDOW_DAYS = 60
private const val ROLLING_WINDOW_DAYS = 7
private const val MIN_READINGS_PER_WINDOW = 4

// Analysis review B7. The old rule (7-day mean outside the 60-day mean +/- 0.5 SD of DAILY values) left Stable on about
// 1 day in 5 with no real change, and on 1 in 3 or more with realistic day-to-day carry-over, because a 7-day mean
// already wanders by ~0.4 SD by chance. Simulated on stationary AR(1) data (rho 0 to 0.5) the rule below leaves Stable on
// 4-8% of days and still flags a 1-SD drop over the last week 37-67% of the time and a regime change every time
// (HrvRhrEvaluationTest). Carry-over is estimated from residuals around a +/-7-reading running median, as the series-break
// engine does, so a level shift inside the window doesn't masquerade as autocorrelation.
private const val SHIFT_Z = 1.96
private const val CARRY_OVER_MEDIAN_HALF_WIDTH = 7
private const val MAX_CARRY_OVER = 0.8

// Generic over any daily-value stream needing the SWC treatment -- takes
// values already in whatever space they should be compared in (log-space
// for HRV, raw for RHR and sleep duration).
// Analysis Layer 3 / R1: `windowStart` (when given) drops every reading before it, so a baseline restarts after a
// CONFIRMED series break (a device/source/classifier change, see domain/SeriesBreakStarts.kt) instead of mixing two
// regimes. The existing 60-day span gate below then reports Building until a new baseline exists. Null = unchanged.
fun evaluateSwcStream(
    dailyValues: List<Pair<LocalDate, Double>>,
    asOf: LocalDate = LocalDate.now(),
    windowStart: LocalDate? = null,
): SwcEvaluation {
    val past = dailyValues.filter { !it.first.isAfter(asOf) && (windowStart == null || !it.first.isBefore(windowStart)) }
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
    val values60 = window60.sortedBy { it.first }.map { it.second }
    val baseline7d = mean(window7.map { it.second })
    val mean60d = mean(values60)
    val sd60d = populationStdDev(values60)
    val cvBaseline = if (mean60d != 0.0) sd60d / mean60d * 100 else 0.0
    val swcPct = 0.5 * cvBaseline
    val swcAbs = 0.5 * sd60d
    val half = maxOf(swcAbs, SHIFT_Z * weeklyMeanStandardError(values60, window7.size, sd60d))
    val bandLow = mean60d - half
    val bandHigh = mean60d + half

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

    return SwcEvaluation(state, Confidence(window7.size, MIN_READINGS_PER_WINDOW), baseline7d, mean60d, swcPct, cv7dToday, bandLow, bandHigh)
}

// Standard error of (7-day mean - 60-day mean) when nothing has changed. The 60-day window contains the 7 days, so the
// difference is (nRest / n60) x (7-day mean - mean of the other readings); carry-over widens both terms by
// (1 + rho) / (1 - rho).
internal fun weeklyMeanStandardError(values60: List<Double>, n7: Int, sd: Double): Double {
    val n60 = values60.size
    val nRest = n60 - n7
    if (n7 <= 0 || nRest <= 0 || sd <= 0.0) return 0.0
    val rho = carryOver(values60)
    val inflation = (1 + rho) / (1 - rho)
    return nRest.toDouble() / n60 * sd * sqrt(inflation * (1.0 / n7 + 1.0 / nRest))
}

// Lag-1 autocorrelation of the residuals around a running median, small-sample bias corrected (r + (1 + 3r) / n, the
// same correction the server's experiment engine uses) and clipped to [0, 0.8].
internal fun carryOver(values: List<Double>): Double {
    val n = values.size
    if (n < 4) return 0.0
    val residuals = values.indices.map { i ->
        val lo = (i - CARRY_OVER_MEDIAN_HALF_WIDTH).coerceAtLeast(0)
        val hi = (i + CARRY_OVER_MEDIAN_HALF_WIDTH).coerceAtMost(n - 1)
        values[i] - median(values.subList(lo, hi + 1))
    }
    val m = mean(residuals)
    val centered = residuals.map { it - m }
    val denominator = centered.sumOf { it * it }
    if (denominator == 0.0) return 0.0
    val r1 = (1 until n).sumOf { centered[it] * centered[it - 1] } / denominator
    return (r1 + (1 + 3 * r1) / n).coerceIn(0.0, MAX_CARRY_OVER)
}

// HRV is evaluated on ln(ms) (evaluateHrv); everything shown to a person is in ms. The band is exponentiated end by end,
// so it stays the band that decided the state (it is no longer symmetric in ms, which is correct for a log-normal metric).
fun SwcEvaluation.toExpSpace(): SwcEvaluation = copy(
    baseline7d = expValue(baseline7d),
    mean60d = expValue(mean60d),
    bandLow = expValue(bandLow),
    bandHigh = expValue(bandHigh),
)

// windowEndingAt() moved to domain/Stats.kt (Phase A3) -- Category 3 needs
// the identical "last N calendar days ending here" logic, so this is no
// longer HRV-specific.
private fun cv7dAt(values: List<Pair<LocalDate, Double>>, date: LocalDate): Double? {
    val window = windowEndingAt(values, date, ROLLING_WINDOW_DAYS)
    if (window.size < MIN_READINGS_PER_WINDOW) return null
    val vals = window.map { it.second }
    val m = mean(vals)
    if (m == 0.0) return null
    return populationStdDev(vals) / m * 100
}

// Log-transformed per the spec ("log-transform first, always") -- ln(rMSSD).
fun evaluateHrv(hrvDaily: List<Pair<LocalDate, Double>>, asOf: LocalDate = LocalDate.now(), windowStart: LocalDate? = null): SwcEvaluation =
    evaluateSwcStream(hrvDaily.filter { it.second > 0 }.map { it.first to ln(it.second) }, asOf, windowStart)

// Untransformed, per the spec's own "apply the identical structure to
// resting HR (untransformed -- RHR does not need the log)" instruction.
fun evaluateRhr(rhrDaily: List<Pair<LocalDate, Double>>, asOf: LocalDate = LocalDate.now(), windowStart: LocalDate? = null): SwcEvaluation =
    evaluateSwcStream(rhrDaily, asOf, windowStart)

// Same SWC treatment as RHR (untransformed -- SpO2 is already a bounded
// percentage, no log-space reason to transform it).
fun evaluateSpo2(spo2Daily: List<Pair<LocalDate, Double>>, asOf: LocalDate = LocalDate.now()): SwcEvaluation =
    evaluateSwcStream(spo2Daily, asOf)

// HRV's baseline7d/mean60d are in ln-space (see evaluateHrv) -- callers
// exponentiate back to real ms for display. RHR needs no such conversion.
fun expValue(value: Double?): Double? = value?.let { exp(it) }

// DAV-285: HRV/RHR's *trend chart* window -- independent of
// evaluateSwcStream()'s own fixed 60-day baseline/gate logic above, which
// this doesn't touch. days = null means ALL (no lower bound).
enum class VitalsTimeframe(val label: String, val days: Long?) {
    Week("7D", 7),
    Month("1M", 30),
    SixMonths("6M", 180),
    Year("1Y", 365),
    All("ALL", null),
}

// At 7D, raw daily points render individually -- few enough to read as-is.
// Longer windows bucket into Monday-anchored weekly means so a year of daily
// noise doesn't overwhelm the line (same downsampling spirit as
// vo2MaxRollingAverage's day-aware smoothing, but a bucket average rather
// than a rolling one, since these windows are wide enough that "one point per
// day" stops being readable).
fun vitalsTrendSeries(points: List<Pair<LocalDate, Double>>, timeframe: VitalsTimeframe, asOf: LocalDate = LocalDate.now()): List<Pair<LocalDate, Double>> {
    val windowed = timeframe.days
        ?.let { d -> points.filter { ChronoUnit.DAYS.between(it.first, asOf) <= d } }
        ?: points
    if (timeframe == VitalsTimeframe.Week) return windowed.sortedBy { it.first }
    return windowed.groupBy { it.first.with(java.time.temporal.TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)) }
        .toSortedMap()
        .map { (weekStart, values) -> weekStart to values.map { it.second }.average() }
}
