package com.bioscan.fieldterminal.domain.levels

import com.bioscan.fieldterminal.domain.LiftSeries
import com.bioscan.fieldterminal.domain.StrengthLevel
import com.bioscan.fieldterminal.domain.exerciseSlug
import com.bioscan.fieldterminal.domain.standardKey
import com.bioscan.fieldterminal.domain.strengthThresholds
import java.time.LocalDate
import java.time.temporal.ChronoUnit

// Per-lift position on the strength ladder for the User tab's Strength page. The standards themselves (bodyweight multiples,
// weighted pull-up added load, name aliases) live in one place, domain/StrengthStandards.kt, which the Healthspan strength
// factor also uses; this file only turns a threshold row into a ladder position and a "next tier" target. They are lifter
// conventions, not population percentiles and not adjusted for sex or age.
enum class StandardKind { BODYWEIGHT_MULTIPLE, ADDED_LOAD_KG }

data class LiftTierResult(
    val name: String,
    val kind: StandardKind,
    val tier: StrengthLevel,
    // Position on the whole ladder, 0..1: 0 = nothing, 0.25 = Novice, 0.5 = Intermediate, 0.75 = Advanced, 1.0 = Elite, linear
    // between thresholds. Shared by the ladder bar and the Strength level.
    val ladderFraction: Double,
    val e1rmKg: Double,
    // [novice, intermediate, advanced, elite] in kg for this person.
    val thresholdsKg: List<Double>,
    val nextTier: StrengthLevel?,
    val nextKg: Double?,
)

fun ladderFraction(thresholdsKg: List<Double>, e1rmKg: Double): Double {
    val points = listOf(0.0 to 0.0) + thresholdsKg.mapIndexed { i, kg -> kg to 0.25 * (i + 1) }
    if (e1rmKg <= 0.0) return 0.0
    if (e1rmKg >= thresholdsKg.last()) return 1.0
    for (i in 0 until points.size - 1) {
        val (v0, f0) = points[i]
        val (v1, f1) = points[i + 1]
        if (e1rmKg <= v1) return f0 + (e1rmKg - v0) / (v1 - v0) * (f1 - f0)
    }
    return 1.0
}

// `name` is the exercise as logged ("Standing Military Press"); it is resolved through the shared slug/alias rules. Null when the
// lift has no standard, or needs a body weight that is missing (never an assumed one).
fun liftTier(name: String, e1rmKg: Double, bodyWeightKg: Double?): LiftTierResult? {
    val slug = standardKey(exerciseSlug(name))
    val thresholds = strengthThresholds(slug, bodyWeightKg)?.toList() ?: return null
    // A standard that resolves without a body weight is an absolute added-load one (weighted pull-up).
    val kind = if (strengthThresholds(slug, null) != null) StandardKind.ADDED_LOAD_KG else StandardKind.BODYWEIGHT_MULTIPLE
    val idx = thresholds.indexOfLast { e1rmKg >= it }
    val tier = if (idx < 0) StrengthLevel.UNTRAINED else StrengthLevel.entries[idx + 1]
    val next = StrengthLevel.entries.getOrNull(idx + 2)
    val nextKg = thresholds.getOrNull(idx + 1)
    return LiftTierResult(name, kind, tier, ladderFraction(thresholds, e1rmKg), e1rmKg, thresholds, next, nextKg)
}

// Best e1RM per lift over the last `windowDays` (about six months, the same window as the Healthspan strength factor), rated
// against the standards. Lifts with no standard, or needing a missing body weight, are simply not rated.
fun ratedLifts(
    series: List<LiftSeries>,
    bodyWeightKg: Double?,
    asOf: LocalDate,
    windowDays: Long = 183,
): List<LiftTierResult> = series.mapNotNull { s ->
    val best = s.points
        .filter { !it.first.isAfter(asOf) && ChronoUnit.DAYS.between(it.first, asOf) < windowDays }
        .maxOfOrNull { it.second } ?: return@mapNotNull null
    liftTier(s.name, best, bodyWeightKg)
}
