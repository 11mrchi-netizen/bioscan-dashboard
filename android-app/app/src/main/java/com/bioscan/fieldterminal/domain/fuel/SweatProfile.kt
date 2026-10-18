package com.bioscan.fieldterminal.domain.fuel

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

// How much the person sweats (docs/fuel-planning/README.md, phase 4). A sweat test is a weigh-in before and after a
// session, the fluid drunk and the urine passed (Sawka 2007, ACSM fluid replacement): sweat lost = weight lost + fluid in
// − urine. Sweat rate varies two- to threefold between people and rises with heat and effort, so a plan should come
// from the person's own tests; without any, a prior is used and shown as a wide estimate.
//
// The prior (running; other sports x0.8): 0.5 L/h easy, 0.8 moderate, 1.0 hard, long or race, at 20 °C and 70 kg,
// scaled by body weight, +3 % per °C above 20 and −2 % per °C below (floor 0.3 L/h). Tests move the whole curve by the
// ratio of what was measured to what the prior predicted for those conditions (the median ratio), and narrow the range:
// ±40 % with no test, ±25 % with one or two, ±15 % with three or more.

data class SweatTest(
    val date: LocalDate,
    val sessionType: String,
    val intensity: Intensity,
    val durationMin: Double,
    val weightBeforeKg: Double,
    val weightAfterKg: Double,
    val fluidInMl: Double,
    val urineMl: Double = 0.0,
    val tempC: Double? = null,
    val humidityPct: Double? = null,
)

/** Sweat rate, ml per hour; null when the numbers are impossible (more than 4 L/h or under 0.1). */
fun sweatRateMlPerHour(t: SweatTest): Double? {
    if (t.durationMin <= 0) return null
    val lossMl = (t.weightBeforeKg - t.weightAfterKg) * 1000 + t.fluidInMl - t.urineMl
    val rate = lossMl / (t.durationMin / 60)
    return rate.takeIf { it in 100.0..4000.0 }
}

/** Body mass lost in the test, as a share of the starting weight (above 2 % performance starts to suffer). */
fun bodyMassLossPct(t: SweatTest): Double = (t.weightBeforeKg - t.weightAfterKg) / t.weightBeforeKg * 100

private fun priorMlPerHour(intensity: Intensity, type: String, tempC: Double, weightKg: Double): Double {
    val base = when (intensity) {
        Intensity.EASY -> 500.0
        Intensity.MODERATE -> 800.0
        else -> 1000.0
    } * (if (type == "run" || type == "hike") 1.0 else 0.8) * (weightKg / 70.0)
    val tempFactor = if (tempC >= 20) 1 + 0.03 * (tempC - 20) else max(0.6, 1 - 0.02 * (20 - tempC))
    return max(300.0, base * tempFactor)
}

data class SweatProfile(val tests: List<SweatTest>, val ratio: Double, val testCount: Int) {
    val spread: Double get() = when {
        testCount >= 3 -> 0.15
        testCount >= 1 -> 0.25
        else -> 0.40
    }
}

data class SweatEstimate(val mlPerHour: Double, val lowMlPerHour: Double, val highMlPerHour: Double, val basis: String)

const val DEFAULT_TEST_TEMP_C = 18.0

/** The person's sweat profile from their tests (each read against the prior for its own conditions). */
fun sweatProfile(tests: List<SweatTest>, weightKg: Double): SweatProfile {
    val ratios = tests.mapNotNull { t ->
        val measured = sweatRateMlPerHour(t) ?: return@mapNotNull null
        measured / priorMlPerHour(t.intensity, t.sessionType, t.tempC ?: DEFAULT_TEST_TEMP_C, t.weightBeforeKg)
    }.sorted()
    val ratio = if (ratios.isEmpty()) 1.0 else ratios[ratios.size / 2].coerceIn(0.4, 2.5)
    return SweatProfile(tests, ratio, ratios.size)
}

fun predictSweatRate(profile: SweatProfile, intensity: Intensity, type: String, tempC: Double, weightKg: Double): SweatEstimate {
    val mid = priorMlPerHour(intensity, type, tempC, weightKg) * profile.ratio
    val basis = if (profile.testCount == 0) {
        "an estimate from body weight, effort and temperature (no sweat test yet)"
    } else {
        "your ${profile.testCount} sweat test${if (profile.testCount == 1) "" else "s"}, adjusted to ${tempC.roundToInt()} °C"
    }
    return SweatEstimate(mid, mid * (1 - profile.spread), mid * (1 + profile.spread), basis)
}

// ---- Fluid and sodium during a session ---------------------------------------------------------------------------

/**
 * Drinking: replace most of the sweat but never more than it (drinking beyond sweat losses is how exercise-associated
 * hyponatraemia happens). The target replaces enough that the loss stays under 2 % of body weight over the session, at
 * most 80 % of the sweat rate; below about 60 minutes of moderate work drinking to thirst is enough.
 */
data class FluidPlan(val mlPerHourLow: Double, val mlPerHourHigh: Double, val sodiumMgPerL: Double, val note: String) {
    val sodiumMgPerHour: Double get() = (mlPerHourLow + mlPerHourHigh) / 2 / 1000 * sodiumMgPerL
}

const val MAX_LOSS_SHARE = 0.02
const val MAX_REPLACEMENT_SHARE = 0.8

fun fluidPlan(sweat: SweatEstimate, durationMin: Double, weightKg: Double, heat: HeatBand, saltySweater: Boolean): FluidPlan {
    val hours = durationMin / 60
    val sodium = when {
        saltySweater -> 1000.0
        heat == HeatBand.HOT || heat == HeatBand.VERY_HOT -> 700.0
        else -> 500.0
    }
    if (durationMin < 60 && !heat.raisesFluid) return FluidPlan(0.0, min(500.0, sweat.mlPerHour * 0.5), 0.0, "Under an hour: drink to thirst.")
    // Enough to keep the loss within 2 % of body weight, never more than 80 % of the sweat.
    val allowedLossMlPerH = MAX_LOSS_SHARE * weightKg * 1000 / max(hours, 1.0)
    val needLow = max(0.0, sweat.lowMlPerHour - allowedLossMlPerH)
    val needMid = max(0.0, sweat.mlPerHour - allowedLossMlPerH)
    val cap = sweat.lowMlPerHour * MAX_REPLACEMENT_SHARE
    val high = min(cap, max(needMid, 400.0))
    val low = min(high, max(needLow, 300.0))
    val note = "About ${((low + high) / 2).roundToInt()} ml an hour against a sweat rate of ${sweat.mlPerHour.roundToInt()} (${sweat.basis}). " +
        "Never more than you sweat: over-drinking dilutes blood sodium."
    return FluidPlan(low, high, sodium, note)
}

/** Whether a sweat test is worth trusting as entered (plausible numbers, a long enough session). */
fun sweatTestProblems(t: SweatTest): List<String> {
    val problems = mutableListOf<String>()
    if (t.durationMin < 30) problems += "At least 30 minutes, so the scale's error is small against the loss."
    if (t.weightBeforeKg !in 30.0..200.0 || t.weightAfterKg !in 30.0..200.0) problems += "Check the weights."
    if (abs(t.weightBeforeKg - t.weightAfterKg) > 0.08 * t.weightBeforeKg) problems += "More than 8 % of body weight changed: check the weights."
    if (t.fluidInMl < 0 || t.urineMl < 0) problems += "Fluid and urine cannot be negative."
    if (problems.isEmpty() && sweatRateMlPerHour(t) == null) problems += "That works out under 0.1 or over 4 litres an hour: check the numbers."
    return problems
}
