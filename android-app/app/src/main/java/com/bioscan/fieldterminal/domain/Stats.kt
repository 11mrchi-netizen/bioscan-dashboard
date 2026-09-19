package com.bioscan.fieldterminal.domain

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

// Phase A2 (Analysis Layer). This project's own domain layer had no generic
// mean/SD/median helper before this -- domain/Readiness.kt's
// computeHrvReadinessSeries() inlines its own mean/variance math rather than
// calling a shared one. These three are deliberately the first, kept
// minimal (population SD, matching that existing function's own convention
// of dividing by n rather than n-1, for consistency with it) rather than a
// general statistics library.
fun mean(values: List<Double>): Double = values.sum() / values.size

fun populationStdDev(values: List<Double>): Double {
    val m = mean(values)
    val variance = values.sumOf { (it - m) * (it - m) } / values.size
    return sqrt(variance)
}

fun median(values: List<Double>): Double {
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid]
}

// Tukey's hinges: split at the median, IQR = median(upper half) -
// median(lower half). One of several legitimate IQR conventions -- picked
// for being the simplest to state and verify by hand, matching this file's
// own "kept minimal" precedent rather than a general statistics library.
fun interquartileRange(values: List<Double>): Double {
    val sorted = values.sorted()
    val n = sorted.size
    val mid = n / 2
    val lowerHalf = sorted.subList(0, mid)
    val upperHalf = if (n % 2 == 0) sorted.subList(mid, n) else sorted.subList(mid + 1, n)
    return median(upperHalf) - median(lowerHalf)
}

// Phase A3's shared "last N calendar days ending at a given date" window,
// promoted here from what was originally a private helper duplicated only
// in domain/HrvRhrEvaluation.kt -- Category 3's median/IQR/Mann-Kendall
// windows need the identical logic, so this is now the one copy.
fun windowEndingAt(values: List<Pair<LocalDate, Double>>, end: LocalDate, days: Int): List<Pair<LocalDate, Double>> {
    val start = end.minusDays((days - 1).toLong())
    return values.filter { !it.first.isBefore(start) && !it.first.isAfter(end) }
}

// Phase A3 (Category 3). Mann-Kendall trend test -- chosen by the spec
// specifically because it's non-parametric, tolerates the ties an 0-10
// ordinal scale is full of (hence the tie-corrected variance below, not the
// simpler untied formula), and needs no imputation for missing days.
// tau here is the simpler tau-a (no tie adjustment in the denominator) --
// the spec only asks for tau/p to gate whether a direction arrow renders,
// not a fully tie-corrected correlation coefficient, so the simpler form is
// enough for what it's actually used for.
data class MannKendallResult(val tau: Double, val pValue: Double, val significant: Boolean)

fun mannKendall(orderedValues: List<Double>): MannKendallResult {
    val n = orderedValues.size
    var s = 0
    for (i in 0 until n - 1) {
        for (j in i + 1 until n) {
            val diff = orderedValues[j] - orderedValues[i]
            s += if (diff > 0) 1 else if (diff < 0) -1 else 0
        }
    }

    val tieCorrection = orderedValues.groupingBy { it }.eachCount().values
        .sumOf { tp -> tp.toDouble() * (tp - 1) * (2 * tp + 5) }
    val variance = (n.toDouble() * (n - 1) * (2 * n + 5) - tieCorrection) / 18.0

    val z = when {
        variance <= 0 -> 0.0
        s > 0 -> (s - 1) / sqrt(variance)
        s < 0 -> (s + 1) / sqrt(variance)
        else -> 0.0
    }
    val pValue = 2 * (1 - standardNormalCdf(abs(z)))
    val tau = s / (0.5 * n * (n - 1))

    return MannKendallResult(tau, pValue, pValue < 0.05)
}

// DAV-59: the CTL/ATL dual-EWMA walk from TrainingLoadEvaluation.kt,
// generalized so any DAV-56 load dimension can reuse it with its own tau
// pair instead of duplicating the loop -- same promotion pattern as
// windowEndingAt() above. A missing day is a real 0 to walk through (no
// session that day really is zero load for a session-derived dimension),
// not an invented measurement. Call twice (asOf and asOf.minusDays(1)) for
// TSB-style "yesterday vs today" comparisons -- cheap to re-walk, no
// special-casing needed inside the loop.
fun dualEwma(dailyValues: Map<LocalDate, Double>, earliest: LocalDate, asOf: LocalDate, fatigueTauDays: Double, adaptationTauDays: Double): Pair<Double, Double> {
    val alphaFatigue = 2.0 / (fatigueTauDays + 1.0)
    val alphaAdaptation = 2.0 / (adaptationTauDays + 1.0)
    var fatigue = 0.0
    var adaptation = 0.0
    var date = earliest
    while (!date.isAfter(asOf)) {
        val value = dailyValues[date] ?: 0.0
        fatigue += alphaFatigue * (value - fatigue)
        adaptation += alphaAdaptation * (value - adaptation)
        date = date.plusDays(1)
    }
    return adaptation to fatigue
}

// Abramowitz & Stegun 7.1.26 -- a standard, widely-used erf approximation
// (accurate to ~1.5e-7), used here only to turn Mann-Kendall's z-score into
// a p-value without pulling in a full statistics library for one function.
private fun standardNormalCdf(z: Double): Double {
    val absZ = abs(z)
    val t = 1.0 / (1.0 + 0.3275911 * absZ)
    val poly = t * (0.254829592 + t * (-0.284496736 + t * (1.421413741 + t * (-1.453152027 + t * 1.061405429))))
    val erf = 1.0 - poly * exp(-absZ * absZ)
    val cdf = 0.5 * (1.0 + erf)
    return if (z >= 0) cdf else 1.0 - cdf
}
