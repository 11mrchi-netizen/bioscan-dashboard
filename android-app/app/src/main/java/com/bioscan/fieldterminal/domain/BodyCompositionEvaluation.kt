package com.bioscan.fieldterminal.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.pow

// Phase A2 (Analysis Layer). Evaluation Method Spec, Category 5 (Body
// composition): two different treatments for two different noise profiles.
// Weight -- signal is real, noise is water -- gets a time-aware EMA so a
// skipped day doesn't distort the trend. Body fat % (BIA) -- noise can
// exceed the signal over short windows -- gets gated hard on a Least
// Significant Change threshold, comparing only readings >=30 days apart.

private const val WEIGHT_MIN_MEASUREMENTS = 10
private const val WEIGHT_MIN_SPAN_DAYS = 14
private const val WEIGHT_STABLE_THRESHOLD_KG_PER_WEEK = 0.15

data class WeightEvaluation(val state: EvalState, val confidence: Confidence, val emaToday: Double?, val rateKgPerWeek: Double?)

// alpha per the spec's cadence-based defaults; auto-picked from how often
// this person actually weighs in over the trailing 30 days, rather than
// asked as a setting -- see chooseWeightAlpha().
fun chooseWeightAlpha(measurementsPerWeek: Double): Double = when {
    measurementsPerWeek >= 6.5 -> 0.10 // daily
    measurementsPerWeek >= 3.0 -> 0.20 // 3-4x/week
    else -> 0.25 // ~2x/week or less
}

fun evaluateWeightTrend(weightDaily: List<Pair<LocalDate, Double>>, asOf: LocalDate = LocalDate.now()): WeightEvaluation {
    val sorted = weightDaily.filter { !it.first.isAfter(asOf) }.sortedBy { it.first }
    if (sorted.isEmpty()) return WeightEvaluation(EvalState.NoData, Confidence(0, WEIGHT_MIN_MEASUREMENTS), null, null)

    val spanDays = ChronoUnit.DAYS.between(sorted.first().first, sorted.last().first)
    if (sorted.size < WEIGHT_MIN_MEASUREMENTS || spanDays < WEIGHT_MIN_SPAN_DAYS) {
        return WeightEvaluation(EvalState.Building, Confidence(sorted.size, WEIGHT_MIN_MEASUREMENTS), null, null)
    }

    val last30 = sorted.filter { ChronoUnit.DAYS.between(it.first, asOf) < 30 }
    val measurementsPerWeek = if (last30.size >= 2) {
        val last30SpanDays = ChronoUnit.DAYS.between(last30.first().first, last30.last().first).coerceAtLeast(1)
        last30.size.toDouble() / last30SpanDays * 7.0
    } else {
        2.0 // conservative default if too few recent points to estimate cadence
    }
    val alpha = chooseWeightAlpha(measurementsPerWeek)

    var ema: Double? = null
    var lastDate: LocalDate? = null
    val emaSeries = mutableListOf<Pair<LocalDate, Double>>()
    for ((date, weight) in sorted) {
        ema = if (ema == null) {
            weight
        } else {
            val dt = ChronoUnit.DAYS.between(lastDate, date).coerceAtLeast(1)
            val alphaEff = 1 - (1 - alpha).pow(dt.toDouble())
            alphaEff * weight + (1 - alphaEff) * ema
        }
        lastDate = date
        emaSeries += date to ema
    }

    val emaToday = emaSeries.last().second
    // EMA as of ~14 days ago: the most recent computed value at or before
    // that date -- the EMA persists at its last real value until the next
    // measurement, consistent with the smoothing model tolerating gaps.
    val emaTwoWeeksAgo = emaSeries.lastOrNull { !it.first.isAfter(asOf.minusDays(14)) }?.second
        ?: return WeightEvaluation(EvalState.Building, Confidence(sorted.size, WEIGHT_MIN_MEASUREMENTS), emaToday, null)

    val rateKgPerWeek = (emaToday - emaTwoWeeksAgo) / 2.0
    val state = when {
        abs(rateKgPerWeek) < WEIGHT_STABLE_THRESHOLD_KG_PER_WEEK -> EvalState.Stable
        rateKgPerWeek > 0 -> EvalState.ShiftUp
        else -> EvalState.ShiftDown
    }

    return WeightEvaluation(state, Confidence(sorted.size, WEIGHT_MIN_MEASUREMENTS), emaToday, rateKgPerWeek)
}

private const val BODY_FAT_MIN_INTERVAL_DAYS = 30
private const val BODY_FAT_DEFAULT_LSC = 1.5 // percentage points, consumer BIA default per the spec

data class BodyFatEvaluation(val state: EvalState, val confidence: Confidence, val latest: Double?, val previous: Double?, val delta: Double?)

// Only ever compares two readings >=30 days apart, and only calls a change
// when it exceeds LSC -- everything inside LSC renders STABLE regardless of
// what the raw number did, per the spec's own hard gate.
fun evaluateBodyFat(bodyFatDaily: List<Pair<LocalDate, Double>>, asOf: LocalDate = LocalDate.now(), lsc: Double = BODY_FAT_DEFAULT_LSC): BodyFatEvaluation {
    // Confidence here means "a validly-spaced comparison pair found," not
    // raw reading count -- a real on-device check caught the count-based
    // version displaying a confusing "3/2" when 3 real readings existed but
    // none were >=30 days apart, which reads like a broken fraction. 0/1 or
    // 1/1 reads clearly: whether one qualifying pair exists yet, since one
    // is all that's ever needed.
    val sorted = bodyFatDaily.filter { !it.first.isAfter(asOf) }.sortedBy { it.first }
    if (sorted.isEmpty()) return BodyFatEvaluation(EvalState.NoData, Confidence(0, 1), null, null, null)

    val latest = sorted.last()
    val prior = sorted.dropLast(1).lastOrNull { ChronoUnit.DAYS.between(it.first, latest.first) >= BODY_FAT_MIN_INTERVAL_DAYS }
        ?: return BodyFatEvaluation(EvalState.Building, Confidence(0, 1), latest.second, null, null)

    val delta = latest.second - prior.second
    val state = if (abs(delta) <= lsc) EvalState.Stable else if (delta > 0) EvalState.ShiftUp else EvalState.ShiftDown

    return BodyFatEvaluation(state, Confidence(1, 1), latest.second, prior.second, delta)
}
