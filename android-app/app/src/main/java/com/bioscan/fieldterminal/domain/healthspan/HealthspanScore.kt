package com.bioscan.fieldterminal.domain.healthspan

import com.bioscan.fieldterminal.domain.Confidence
import com.bioscan.fieldterminal.domain.EvalState
import com.bioscan.fieldterminal.domain.SwcEvaluation
import kotlin.math.abs
import kotlin.math.exp

// User tab > Healthspan. One transparent composite over seven existing, already
// real evaluations -- never a replacement or a second formula for any of them,
// and never an opaque number: every axis, its weight, its contribution to the
// headline and its remaining headroom are returned, and an axis that cannot be
// scored says why instead of being imputed (design/ANALYTICAL_PRESENTATION_CONTRACT.md,
// 2026-10 amendment "One transparent composite on the User tab").
//
// This is a lifestyle-and-function index, not a clinical measure. The axis
// mappings below are documented opinions (like SleepIndex's fixed weights), kept
// in this one file so they are easy to inspect and tune.
const val HEALTHSPAN_MODEL_VERSION = "v1"

enum class HealthspanAxis(val label: String, val short: String) {
    SLEEP("Sleep", "SLEEP"),
    MOVEMENT("Movement", "MOVE"),
    CARDIO("Cardio fitness", "CARDIO"),
    RECOVERY("Recovery", "RECOV"),
    STRENGTH("Strength", "STRENGTH"),
    BODY_COMPOSITION("Body composition", "BODY"),
    NUTRITION("Nutrition", "FUEL"),
}

// Equal weights on purpose: no invented clinical weighting. One constant list so
// a future, evidence-based weighting is a one-line change.
val HEALTHSPAN_WEIGHTS: Map<HealthspanAxis, Double> = HealthspanAxis.entries.associateWith { 1.0 }

private const val MIN_AXES_FOR_HEADLINE = 3

// What a caller (repository) hands in: either a 0-100 score, or the reason there
// isn't one. Confidence is the underlying evaluator's own.
data class HealthspanAxisInput(
    val axis: HealthspanAxis,
    val score: Double?,
    val confidence: Confidence,
    val reason: String? = null,
) {
    companion object {
        fun unavailable(axis: HealthspanAxis, reason: String) =
            HealthspanAxisInput(axis, null, Confidence(0, 1), reason)
    }
}

data class HealthspanAxisResult(
    val axis: HealthspanAxis,
    val score: Double?,
    val weight: Double,
    // Points of the headline this axis supplies (sum over axes == headline).
    val contributionPts: Double?,
    // Points the headline would gain if this axis were at 100.
    val headroomPts: Double?,
    val confidence: Confidence,
    val reason: String?,
)

data class HealthspanResult(
    val score: Double?,
    val state: EvalState,
    // axes resolved / axes declared.
    val confidence: Confidence,
    val axes: List<HealthspanAxisResult>,
    val modelVersion: String = HEALTHSPAN_MODEL_VERSION,
)

// Renormalised over only the axes that resolved -- missingness already lowers
// confidence; it must not also drag the score toward zero (same rule as
// SleepIndex / MovementIndex).
fun computeHealthspan(inputs: List<HealthspanAxisInput>): HealthspanResult {
    val byAxis = inputs.associateBy { it.axis }
    val resolved = HealthspanAxis.entries.mapNotNull { a -> byAxis[a]?.takeIf { it.score != null } }
    val totalWeight = resolved.sumOf { HEALTHSPAN_WEIGHTS.getValue(it.axis) }
    val hasHeadline = resolved.size >= MIN_AXES_FOR_HEADLINE && totalWeight > 0

    val axes = HealthspanAxis.entries.map { axis ->
        val input = byAxis[axis]
        val weight = HEALTHSPAN_WEIGHTS.getValue(axis)
        val score = input?.score?.coerceIn(0.0, 100.0)
        val share = if (score != null && totalWeight > 0) weight / totalWeight else null
        HealthspanAxisResult(
            axis = axis,
            score = score,
            weight = weight,
            contributionPts = if (hasHeadline && share != null && score != null) share * score else null,
            headroomPts = if (hasHeadline && share != null && score != null) share * (100.0 - score) else null,
            confidence = input?.confidence ?: Confidence(0, 1),
            reason = if (score == null) input?.reason ?: "Not loaded yet" else null,
        )
    }

    val headline = if (hasHeadline) axes.sumOf { it.contributionPts ?: 0.0 } else null
    return HealthspanResult(
        score = headline,
        state = if (hasHeadline) EvalState.Stable else if (resolved.isEmpty()) EvalState.NoData else EvalState.Building,
        confidence = Confidence(resolved.size, HealthspanAxis.entries.size),
        axes = axes,
    )
}

// ---- Axis mappings (0-100). Documented opinions, centralised here. -----------

// Cardio fitness: Cardio Age acceleration (cardio age - chronological age).
// 50 at zero acceleration (VO2max at the age-and-sex median), +/-2.5 points per
// year, clamped. Negative acceleration (younger) scores higher.
private const val CARDIO_POINTS_PER_YEAR = 2.5
fun cardioAxisScore(ageAccelerationYears: Double): Double =
    (50.0 - CARDIO_POINTS_PER_YEAR * ageAccelerationYears).coerceIn(0.0, 100.0)

// Recovery: where the recent 7-day baseline sits against the person's own 60-day
// mean for HRV (higher is better) and resting HR (lower is better). Within-person
// only. Ratio 1.0 -> 50; a 20% shift in the favourable direction -> 100, in the
// unfavourable direction -> 0. HRV arrives in ln-space (evaluateHrv), RHR raw.
private const val RECOVERY_RATIO_SPAN = 0.20
fun recoveryAxisScore(hrv: SwcEvaluation?, rhr: SwcEvaluation?): Pair<Double?, Confidence> {
    val parts = mutableListOf<Double>()
    var have = 0
    if (hrv != null && hrv.confidence.met && hrv.baseline7d != null && hrv.mean60d != null) {
        val ratio = exp(hrv.baseline7d - hrv.mean60d)
        parts += ratioToScore(ratio - 1.0)
        have++
    }
    if (rhr != null && rhr.confidence.met && rhr.baseline7d != null && rhr.mean60d != null && rhr.baseline7d > 0) {
        val ratio = rhr.mean60d / rhr.baseline7d // inverted: a lower resting HR is the favourable direction
        parts += ratioToScore(ratio - 1.0)
        have++
    }
    return (if (parts.isEmpty()) null else parts.average()) to Confidence(have, 2)
}

private fun ratioToScore(deltaFromOne: Double): Double =
    (50.0 + 50.0 * (deltaFromOne / RECOVERY_RATIO_SPAN)).coerceIn(0.0, 100.0)

// Body composition: body-fat % against a documented healthy band. 100 inside the
// band, minus 5 points per percentage point outside it. Sex-specific because the
// reference bands are (ACE body-fat categories, fitness-to-average range); needs
// sex, otherwise unavailable rather than guessed.
data class BodyFatBand(val low: Double, val high: Double)
fun bodyFatBand(sex: String?): BodyFatBand? = when (sex) {
    "male" -> BodyFatBand(10.0, 24.0)
    "female" -> BodyFatBand(18.0, 31.0)
    else -> null
}

private const val BODY_FAT_POINTS_PER_PCT = 5.0
fun bodyCompositionAxisScore(bodyFatPct: Double, sex: String?): Double? {
    val band = bodyFatBand(sex) ?: return null
    val outside = when {
        bodyFatPct < band.low -> band.low - bodyFatPct
        bodyFatPct > band.high -> bodyFatPct - band.high
        else -> 0.0
    }
    return (100.0 - BODY_FAT_POINTS_PER_PCT * abs(outside)).coerceIn(0.0, 100.0)
}

// Nutrition: mean of the adherence percentages that exist (0-100 each): protein
// inside the AMDR band, calorie target, hydration range. Null when none exist.
fun nutritionAxisScore(adherencePercents: List<Double?>): Double? =
    adherencePercents.filterNotNull().takeIf { it.isNotEmpty() }?.average()?.coerceIn(0.0, 100.0)
