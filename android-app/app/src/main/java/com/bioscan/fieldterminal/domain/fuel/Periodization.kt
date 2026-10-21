package com.bioscan.fieldterminal.domain.fuel

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// Closing the loop (docs/fuel-planning/README.md, phase 8): how well the logged intake follows the training, how the
// codes were kept, and what the races teach about the gut.
//
// Carbohydrate Periodization Index (Rothschild et al. 2022, preprint, with Morton): the correlation between daily
// carbohydrate (g/kg) and daily training load, times the range of daily carbohydrate, over its monotony (mean / SD,
// Foster's monotony applied to intake). It rises when carbohydrate goes up and down with the training, spans a real range
// and is not the same every day. Only fully logged days count; 14 of the last 28 are needed.

data class DayIntakeLoad(val date: LocalDate, val carbGPerKg: Double, val load: Double)

data class PeriodizationIndex(val index: Double, val correlation: Double, val rangeGPerKg: Double, val monotony: Double, val days: Int)

const val CPI_MIN_DAYS = 14

fun carbPeriodizationIndex(days: List<DayIntakeLoad>): PeriodizationIndex? {
    if (days.size < CPI_MIN_DAYS) return null
    val c = days.map { it.carbGPerKg }
    val l = days.map { it.load }
    val r = pearson(c, l) ?: return null
    val mean = c.average()
    val sd = sqrt(c.sumOf { (it - mean) * (it - mean) } / (c.size - 1))
    if (sd <= 0) return PeriodizationIndex(0.0, r, 0.0, Double.POSITIVE_INFINITY, days.size)
    val monotony = mean / sd
    val range = c.max() - c.min()
    return PeriodizationIndex(r * range / monotony, r, range, monotony, days.size)
}

private fun pearson(x: List<Double>, y: List<Double>): Double? {
    val mx = x.average()
    val my = y.average()
    var sxy = 0.0
    var sxx = 0.0
    var syy = 0.0
    for (i in x.indices) {
        sxy += (x[i] - mx) * (y[i] - my)
        sxx += (x[i] - mx) * (x[i] - mx)
        syy += (y[i] - my) * (y[i] - my)
    }
    if (sxx <= 0 || syy <= 0) return null
    return sxy / sqrt(sxx * syy)
}

/** Plain words for an index: how closely carbohydrate followed the training. */
fun periodizationWords(p: PeriodizationIndex): String = when {
    p.correlation >= 0.5 -> "Your carbohydrate rises and falls with your training."
    p.correlation >= 0.2 -> "Your carbohydrate follows your training a little."
    p.correlation > -0.2 -> "Your carbohydrate barely changes with your training: about the same on hard and easy days."
    else -> "Your carbohydrate tends to be lower on harder days: the opposite of fuelling for the work."
}

// ---- How the codes were kept --------------------------------------------------------------------------------------

data class CodedMealOutcome(val code: CarbCode, val adherence: MealAdherence)

data class CodeAdherence(val code: CarbCode, val logged: Int, val within: Int, val under: Int, val over: Int) {
    val withinShare: Double? get() = if (logged == 0) null else within.toDouble() / logged
}

/** Per code, how the logged meals compared with their range (meals not logged are left out). */
fun codeAdherence(outcomes: List<CodedMealOutcome>): List<CodeAdherence> = CarbCode.entries.map { code ->
    val mine = outcomes.filter { it.code == code && it.adherence != MealAdherence.NOT_LOGGED }
    CodeAdherence(code, mine.size, mine.count { it.adherence == MealAdherence.WITHIN }, mine.count { it.adherence == MealAdherence.UNDER }, mine.count { it.adherence == MealAdherence.OVER })
}

// ---- What a race teaches the gut ----------------------------------------------------------------------------------

/** One reviewed race or long session: the carbohydrate actually taken per hour and how the gut coped (0 none - 10 worst). */
data class FuelReviewOutcome(val date: LocalDate, val carbsGPerHour: Double, val giScore: Int, val energyScore: Int?)

data class ToleranceSuggestion(val newGPerHour: Double, val reason: String)

/**
 * A suggestion for the tolerated rate after a review, never applied on its own: a session at the ceiling (within 5 g/h)
 * with little gut trouble (GI 2 or less) suggests training 10 g/h more (90 at most, Jeukendrup 2014); real trouble
 * (GI 6 or more) suggests 10 g/h less (30 at least) and practising in training. Null when the review says neither.
 */
fun suggestTolerance(latest: FuelReviewOutcome, currentGPerHour: Double): ToleranceSuggestion? = when {
    latest.giScore <= 2 && latest.carbsGPerHour >= currentGPerHour - 5 && currentGPerHour < MAX_G_PER_H -> {
        val next = min(MAX_G_PER_H, currentGPerHour + 10)
        ToleranceSuggestion(next, "${latest.carbsGPerHour.roundToInt()} g an hour with a settled gut: try ${next.roundToInt()} in your next long training sessions before racing on it.")
    }
    latest.giScore >= 6 -> {
        val next = max(30.0, currentGPerHour - 10)
        ToleranceSuggestion(next, "Gut trouble (${latest.giScore}/10) at ${latest.carbsGPerHour.roundToInt()} g an hour: plan ${next.roundToInt()} for now and build back up in training.")
    }
    else -> null
}

/** The carbohydrate rate a reviewed session actually reached, g/h. */
fun reviewedRate(carbsG: Double, durationMin: Double): Double = if (durationMin <= 0) 0.0 else carbsG / (durationMin / 60)

/** A finished race's time against the plan: positive when slower, as a share. */
fun durationMiss(plannedMin: Double, actualMin: Double): Double = if (plannedMin <= 0) 0.0 else (actualMin - plannedMin) / plannedMin

@Suppress("unused")
private fun clampShare(v: Double) = max(-1.0, min(1.0, v)).let { if (abs(it) < 1e-9) 0.0 else it }
