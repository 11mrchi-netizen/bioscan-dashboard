package com.bioscan.fieldterminal.domain.levels

import com.bioscan.fieldterminal.domain.training.movementKey

// Strength "app reference" standards, extracted from the private table that used to live in
// UserProfileScreen.kt. They are bodyweight multiples (or, for weighted pull-ups, added load in
// kg) for each tier threshold. They are NOT sourced to a published table and NOT sex-adjusted --
// the UI labels them "app reference". Lifts are resolved through movementKey() so spelling
// variants ("Standing Military Press", "Chest Press") reach the right row.
enum class StrengthTier(val label: String) { UNTRAINED("Untrained"), NOVICE("Novice"), INTERMEDIATE("Intermediate"), ADVANCED("Advanced"), ELITE("Elite") }

enum class StandardKind { BODYWEIGHT_MULTIPLE, ADDED_LOAD_KG }

data class LiftStandard(
    val name: String,
    val kind: StandardKind,
    val novice: Double,
    val intermediate: Double,
    val advanced: Double,
    val elite: Double,
)

private val STANDARDS = listOf(
    LiftStandard("Deadlift", StandardKind.BODYWEIGHT_MULTIPLE, 1.0, 1.5, 2.0, 2.5),
    LiftStandard("Squat", StandardKind.BODYWEIGHT_MULTIPLE, 0.75, 1.25, 1.75, 2.25),
    LiftStandard("Front Squat", StandardKind.BODYWEIGHT_MULTIPLE, 0.6, 1.0, 1.4, 1.8),
    LiftStandard("Bench Press", StandardKind.BODYWEIGHT_MULTIPLE, 0.5, 0.75, 1.25, 1.5),
    LiftStandard("Incline Bench Press", StandardKind.BODYWEIGHT_MULTIPLE, 0.45, 0.7, 1.1, 1.4),
    LiftStandard("Overhead Press", StandardKind.BODYWEIGHT_MULTIPLE, 0.35, 0.55, 0.8, 1.0),
    LiftStandard("Clean and Press", StandardKind.BODYWEIGHT_MULTIPLE, 0.5, 0.7, 0.9, 1.2),
    LiftStandard("Barbell Row", StandardKind.BODYWEIGHT_MULTIPLE, 0.5, 0.75, 1.0, 1.3),
    LiftStandard("Weighted Pull-up", StandardKind.ADDED_LOAD_KG, 10.0, 25.0, 45.0, 70.0),
)

private val BY_KEY: Map<String, LiftStandard> = STANDARDS.associateBy { movementKey(it.name) }

fun liftStandardFor(exerciseName: String): LiftStandard? = BY_KEY[movementKey(exerciseName)]

data class LiftTierResult(
    val standard: LiftStandard,
    val tier: StrengthTier,
    // Position on the whole ladder, 0..1: 0 = nothing, 0.25 = Novice, 0.5 = Intermediate,
    // 0.75 = Advanced, 1.0 = Elite, linear between the thresholds. Shared by the ladder bar,
    // the skill level and the Healthspan strength axis, so they cannot disagree.
    val ladderFraction: Double,
    val value: Double,
    val nextTier: StrengthTier?,
    val nextThreshold: Double?,
)

fun ladderFraction(standard: LiftStandard, value: Double): Double {
    val points = listOf(0.0 to 0.0, standard.novice to 0.25, standard.intermediate to 0.5, standard.advanced to 0.75, standard.elite to 1.0)
    if (value <= 0.0) return 0.0
    if (value >= standard.elite) return 1.0
    for (i in 0 until points.size - 1) {
        val (v0, f0) = points[i]
        val (v1, f1) = points[i + 1]
        if (value <= v1) return f0 + (value - v0) / (v1 - v0) * (f1 - f0)
    }
    return 1.0
}

// `e1rmKg` is the estimated one-rep max for the lift; `bodyWeightKg` is required for the
// bodyweight-multiple lifts (null -> no tier, never an assumed weight).
fun liftTier(exerciseName: String, e1rmKg: Double, bodyWeightKg: Double?): LiftTierResult? {
    val standard = liftStandardFor(exerciseName) ?: return null
    val value = when (standard.kind) {
        StandardKind.BODYWEIGHT_MULTIPLE -> {
            if (bodyWeightKg == null || bodyWeightKg <= 0) return null
            e1rmKg / bodyWeightKg
        }
        StandardKind.ADDED_LOAD_KG -> e1rmKg
    }
    val tier = when {
        value >= standard.elite -> StrengthTier.ELITE
        value >= standard.advanced -> StrengthTier.ADVANCED
        value >= standard.intermediate -> StrengthTier.INTERMEDIATE
        value >= standard.novice -> StrengthTier.NOVICE
        else -> StrengthTier.UNTRAINED
    }
    val next = when (tier) {
        StrengthTier.UNTRAINED -> StrengthTier.NOVICE to standard.novice
        StrengthTier.NOVICE -> StrengthTier.INTERMEDIATE to standard.intermediate
        StrengthTier.INTERMEDIATE -> StrengthTier.ADVANCED to standard.advanced
        StrengthTier.ADVANCED -> StrengthTier.ELITE to standard.elite
        StrengthTier.ELITE -> null
    }
    return LiftTierResult(standard, tier, ladderFraction(standard, value), value, next?.first, next?.second)
}

// Best e1RM per lift over the last `windowDays` (default a year: current strength, not a lifetime best from
// an old training phase), rated against the standards. Lifts with no standard, or needing a body weight that
// is missing, are simply not rated.
fun ratedLifts(
    series: List<com.bioscan.fieldterminal.domain.LiftSeries>,
    bodyWeightKg: Double?,
    asOf: java.time.LocalDate,
    windowDays: Long = 365,
): List<LiftTierResult> = series.mapNotNull { s ->
    val best = s.points
        .filter { !it.first.isAfter(asOf) && java.time.temporal.ChronoUnit.DAYS.between(it.first, asOf) < windowDays }
        .maxOfOrNull { it.second } ?: return@mapNotNull null
    liftTier(s.name, best, bodyWeightKg)
}
