package com.bioscan.fieldterminal.domain

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.sqrt

// 09.6 Circadian Alignment (DAV-259-261). Deliberately a BEHAVIORAL
// REGULARITY PROXY, never labeled true circadian phase (DAV-259's own
// boundary) -- this app has no light/temperature/physiological phase-marker
// data, only clock-time behavior logs. Sleep timing reuses `evaluateSri()`
// directly (it already IS this concept, for sleep -- not a second
// algorithm); meal/exercise timing get the same stdev-of-minutes-since-
// midnight treatment, a documented simplification, not circular statistics
// -- this account's data doesn't cross midnight, a real, stated limitation,
// not a hidden one. Caffeine timing is explicitly omitted: zero real
// caffeine doses have ever been logged (checked via SQL), so there is
// nothing to score.
const val CIRCADIAN_ALIGNMENT_MODEL_VERSION = "v1"

enum class CircadianAlignmentComponent { SLEEP_TIMING, MEAL_TIMING, EXERCISE_TIMING }

data class CircadianAlignmentComponentScore(val score: Double, val weight: Double)

data class CircadianAlignmentResult(
    val score: Double?,
    val band: String?,
    val state: EvalState,
    val confidence: Confidence,
    val components: Map<CircadianAlignmentComponent, CircadianAlignmentComponentScore>,
    val modelVersion: String = CIRCADIAN_ALIGNMENT_MODEL_VERSION,
)

private val COMPONENT_WEIGHTS = mapOf(
    CircadianAlignmentComponent.SLEEP_TIMING to 0.5,
    CircadianAlignmentComponent.MEAL_TIMING to 0.25,
    CircadianAlignmentComponent.EXERCISE_TIMING to 0.25,
)
private const val MIN_COMPONENTS_FOR_INDEX = 1
private const val TIMING_WINDOW_DAYS = 30L
private const val MIN_OBSERVATIONS_FOR_TIMING = 5
// A behavior spread this wide (3h stdev) or more scores 0 -- a named
// threshold, not a fitted one; half that (90min) scores 50, linear between.
private const val MAX_REGULAR_STDDEV_MINUTES = 180.0

// stdev of clock-time-of-day (minutes since midnight) across real
// observations -- lower spread = more regular. Pure function so it's the
// same math for meal timing and exercise timing, not two near-duplicates.
fun timingRegularityScore(minutesSinceMidnight: List<Double>): Double? {
    if (minutesSinceMidnight.size < MIN_OBSERVATIONS_FOR_TIMING) return null
    val mean = minutesSinceMidnight.average()
    val variance = minutesSinceMidnight.sumOf { (it - mean) * (it - mean) } / minutesSinceMidnight.size
    val sd = sqrt(variance)
    return (1.0 - (sd / MAX_REGULAR_STDDEV_MINUTES).coerceIn(0.0, 1.0)) * 100.0
}

fun circadianAlignmentBand(score: Double): String = when {
    score >= 75.0 -> "Regular"
    score >= 50.0 -> "Somewhat irregular"
    else -> "Irregular"
}

private fun OffsetDateTime.minutesSinceMidnight(): Double = toLocalTime().toSecondOfDay() / 60.0

fun computeCircadianAlignment(
    sriEvaluation: SriEvaluation,
    mealTimestamps: List<OffsetDateTime>,
    exerciseTimestamps: List<OffsetDateTime>,
    asOf: LocalDate = LocalDate.now(),
): CircadianAlignmentResult {
    fun recentMinutes(timestamps: List<OffsetDateTime>): List<Double> =
        timestamps.filter { ChronoUnit.DAYS.between(it.toLocalDate(), asOf) in 0 until TIMING_WINDOW_DAYS }
            .map { it.minutesSinceMidnight() }

    val components = mutableMapOf<CircadianAlignmentComponent, CircadianAlignmentComponentScore>()

    if (sriEvaluation.confidence.met) {
        sriEvaluation.value?.let {
            components[CircadianAlignmentComponent.SLEEP_TIMING] = CircadianAlignmentComponentScore(it, COMPONENT_WEIGHTS.getValue(CircadianAlignmentComponent.SLEEP_TIMING))
        }
    }
    timingRegularityScore(recentMinutes(mealTimestamps))?.let {
        components[CircadianAlignmentComponent.MEAL_TIMING] = CircadianAlignmentComponentScore(it, COMPONENT_WEIGHTS.getValue(CircadianAlignmentComponent.MEAL_TIMING))
    }
    timingRegularityScore(recentMinutes(exerciseTimestamps))?.let {
        components[CircadianAlignmentComponent.EXERCISE_TIMING] = CircadianAlignmentComponentScore(it, COMPONENT_WEIGHTS.getValue(CircadianAlignmentComponent.EXERCISE_TIMING))
    }

    if (components.size < MIN_COMPONENTS_FOR_INDEX) {
        return CircadianAlignmentResult(null, null, EvalState.Building, Confidence(components.size, MIN_COMPONENTS_FOR_INDEX), components)
    }

    val totalWeight = components.values.sumOf { it.weight }
    val score = components.values.sumOf { it.score * it.weight } / totalWeight
    return CircadianAlignmentResult(score, circadianAlignmentBand(score), EvalState.Stable, Confidence(components.size, COMPONENT_WEIGHTS.size), components)
}
