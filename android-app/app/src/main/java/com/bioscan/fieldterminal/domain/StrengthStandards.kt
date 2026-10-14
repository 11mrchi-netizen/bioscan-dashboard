package com.bioscan.fieldterminal.domain

import java.time.LocalDate

// Lifter strength standards: bodyweight multiples for an estimated 1RM, one row per lift. These are gym conventions, not
// population percentiles, and they are not adjusted for sex or age, so they describe where a lift sits among trained lifters,
// nothing more. One definition, used by the profile's tier bars and by the Healthspan strength factor.
enum class StrengthLevel(val label: String) {
    UNTRAINED("Untrained"), NOVICE("Novice"), INTERMEDIATE("Intermediate"), ADVANCED("Advanced"), ELITE("Elite"),
}

// bodyweight multiples: [novice, intermediate, advanced, elite]
private val BW_STANDARDS = mapOf(
    "barbell_deadlift" to doubleArrayOf(1.0, 1.5, 2.0, 2.5),
    "barbell_squat" to doubleArrayOf(0.75, 1.25, 1.75, 2.25),
    "front_barbell_squat" to doubleArrayOf(0.6, 1.0, 1.4, 1.8),
    "chest_press" to doubleArrayOf(0.5, 0.75, 1.25, 1.5),
    "inclined_chest_press_icp" to doubleArrayOf(0.45, 0.7, 1.1, 1.4),
    "military_press" to doubleArrayOf(0.35, 0.55, 0.8, 1.0),
    "clean_and_press" to doubleArrayOf(0.5, 0.7, 0.9, 1.2),
    "bent_over_barbell_row" to doubleArrayOf(0.5, 0.75, 1.0, 1.3),
)

// absolute added-weight thresholds for weighted pull-ups
private val ABS_STANDARDS = mapOf(
    "pull_up_weighted" to doubleArrayOf(10.0, 25.0, 45.0, 70.0),
    "weighted_pull_ups" to doubleArrayOf(10.0, 25.0, 45.0, 70.0),
)

// The same lift under another name in the exercise library or in older logs; one lift must count once.
private val ALIASES = mapOf(
    "standing_military_press" to "military_press",
    "standing_barbell_press" to "military_press",
    "overhead_press" to "military_press",
    "barbell_overhead_press" to "military_press",
    "barbell_front_squat" to "front_barbell_squat",
    "weighted_pull_up" to "pull_up_weighted",
)

fun standardKey(slug: String): String = ALIASES[slug] ?: slug

// [novice, intermediate, advanced, elite] in kg, or null when the lift has no standard (or needs a body weight and has none).
fun strengthThresholds(slug: String, bodyWeightKg: Double?): DoubleArray? {
    val key = standardKey(slug)
    return bodyWeightKg?.let { bw -> BW_STANDARDS[key]?.let { m -> DoubleArray(4) { m[it] * bw } } } ?: ABS_STANDARDS[key]
}

fun strengthLevelFor(slug: String, e1rmKg: Double, bodyWeightKg: Double?): StrengthLevel? {
    val thresholds = strengthThresholds(slug, bodyWeightKg) ?: return null
    val idx = thresholds.indexOfLast { e1rmKg >= it }
    return if (idx < 0) StrengthLevel.UNTRAINED else StrengthLevel.entries[idx + 1]
}

// Epley; a set of 30 or more reps would invert the formula, so it is taken as the weight itself.
fun estimatedOneRepMaxKg(weightKg: Double, reps: Int): Double = if (reps >= 30) weightKg else weightKg * (1.0 + reps / 30.0)

fun exerciseSlug(name: String): String = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trimEnd('_')

data class LiftSet(val name: String, val weightKg: Double, val reps: Int, val date: LocalDate)

data class LiftReading(val slug: String, val e1rmKg: Double, val date: LocalDate)

// Best estimated 1RM per lift across the given sets, with the date of that set.
fun bestLifts(sets: List<LiftSet>): List<LiftReading> =
    sets.filter { it.weightKg > 0 && it.reps > 0 }
        .groupBy { standardKey(exerciseSlug(it.name)) }
        .map { (slug, group) ->
            val best = group.maxBy { estimatedOneRepMaxKg(it.weightKg, it.reps) }
            LiftReading(slug, estimatedOneRepMaxKg(best.weightKg, best.reps), best.date)
        }
