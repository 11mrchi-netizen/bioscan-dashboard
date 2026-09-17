package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.data.model.ExerciseLibraryRow

// DAV-65 (Analysis Layer 2, milestone 2). Maps exercise_library's real
// 876-entry anatomical data onto the DAV-67 output contract's
// `SessionLoadVector.regional: Map<BodyRegion, Double>`. Two real findings
// from the account's actual exercise_library data shaped this file (see
// docs/analysis-layer-2 -- Phase A's method of querying real data before
// designing applies here too):
//
// 1. primary_muscles/secondary_muscles already use one clean, consistent
//    17-term vocabulary across all 876 rows (confirmed via
//    `select distinct unnest(...)` against both columns) -- no name
//    normalization was actually needed, unlike the ticket's own framing
//    assumed might be required.
// 2. `force`/`mechanic` only give push/pull/static and compound/isolation --
//    far coarser than the ticket's example taxonomy (squat/hinge/carry/
//    rotation/locomotion). Getting that finer taxonomy means inferring
//    pattern from the exercise *name* (real free-text, e.g. "Zercher
//    Squats", "Rickshaw Carry", "Hang Clean - Below the Knees") -- real
//    heuristic guesswork, confirmed with the user before building it (see
//    classifyMovementPattern below), unlike everything else in this file
//    which reads real, measured exercise_library fields directly.

enum class BodyRegion {
    Quadriceps, Shoulders, Abdominals, Chest, Hamstrings, Triceps, Biceps, Lats,
    MiddleBack, Calves, LowerBack, Forearms, Glutes, Traps, Adductors, Abductors, Neck;

    companion object {
        // Exact string match against exercise_library's own real vocabulary
        // (verified exhaustive against live data, both columns) -- returns
        // null rather than guessing if a future library update ever adds an
        // 18th term, per DAV-66's "distinguish heuristic inference from
        // measured data": an unrecognized region is a gap to notice, not
        // something to silently fold into the nearest guess.
        fun fromMuscleName(raw: String): BodyRegion? = when (raw.lowercase()) {
            "quadriceps" -> Quadriceps
            "shoulders" -> Shoulders
            "abdominals" -> Abdominals
            "chest" -> Chest
            "hamstrings" -> Hamstrings
            "triceps" -> Triceps
            "biceps" -> Biceps
            "lats" -> Lats
            "middle back" -> MiddleBack
            "calves" -> Calves
            "lower back" -> LowerBack
            "forearms" -> Forearms
            "glutes" -> Glutes
            "traps" -> Traps
            "adductors" -> Adductors
            "abductors" -> Abductors
            "neck" -> Neck
            else -> null
        }
    }
}

enum class MovementPattern { Squat, Hinge, Lunge, Push, Pull, Carry, Rotation, Locomotion, Isolation, Unclassified }

// v1 default, deliberately simple and easy to revise (per DAV-65's own
// acceptance criteria) rather than derived from any specific study -- a
// secondary mover contributes real but partial load relative to the muscle
// actually driving the movement.
const val PRIMARY_MUSCLE_WEIGHT = 1.0
const val SECONDARY_MUSCLE_WEIGHT = 0.5

// Distributes one set's already-computed volume load (e.g. reps x weight_kg,
// from DAV-57's strength load model) across the regions it trained,
// weighted by primary/secondary role and normalized so the vector sums back
// to setVolumeLoad exactly -- this is `inferred` per DAV-53/66 (a heuristic
// anatomical split of a real measured number), never presented as if the
// per-region figures were independently measured.
fun regionalLoadVector(exercise: ExerciseLibraryRow, setVolumeLoad: Double): Map<BodyRegion, Double> {
    val weighted = mutableMapOf<BodyRegion, Double>()
    exercise.primaryMuscles.mapNotNull { BodyRegion.fromMuscleName(it) }
        .forEach { weighted[it] = (weighted[it] ?: 0.0) + PRIMARY_MUSCLE_WEIGHT }
    exercise.secondaryMuscles.mapNotNull { BodyRegion.fromMuscleName(it) }
        .forEach { weighted[it] = (weighted[it] ?: 0.0) + SECONDARY_MUSCLE_WEIGHT }

    val totalWeight = weighted.values.sum()
    if (totalWeight <= 0.0) return emptyMap()
    return weighted.mapValues { (_, weight) -> setVolumeLoad * weight / totalWeight }
}

// Keyword classification, reviewed row-by-row by the user against all 876
// real exercise_library names (see the DAV-65 review spreadsheet). Checked
// top to bottom -- order matters for the overlap rule below, not for
// picking a "winner" between real ambiguity (see NAME_PATTERNS' own doc).
private val NAME_PATTERNS: List<Pair<MovementPattern, List<Regex>>> = listOf(
    MovementPattern.Hinge to listOf(
        "deadlift", "good morning", "hip thrust", "glute bridge", "hip raise",
        "hyperextension", "back extension", "\\bswings?\\b", "kettlebell windmill",
    ),
    MovementPattern.Squat to listOf("squat", "leg press", "hack squat"),
    MovementPattern.Lunge to listOf("lunge", "split squat", "step[\\s-]?up", "bulgarian"),
    MovementPattern.Carry to listOf(
        "\\bcarry\\b", "farmer", "yoke walk", "sandbag load", "keg load", "atlas stone",
        "rickshaw", "sled drag", "sled push", "tire flip", "prowler", "waiter'?s? walk",
    ),
    MovementPattern.Locomotion to listOf(
        "\\bsprint", "\\brun(ning)?\\b", "\\bjog", "\\bwalk(ing)?\\b", "\\bskip", "\\bhop\\b",
        "\\bjump\\b", "\\bbound\\b", "box jump", "shuffle", "cone hop", "crawl", "treadmill",
        "cycling", "\\bbike\\b", "elliptical", "rowing,", "stairmaster", "step mill", "skating",
    ),
    MovementPattern.Rotation to listOf("twist", "\\brotation\\b", "wood ?chop", "pallof", "side bend"),
    MovementPattern.Push to listOf(
        "press", "push[\\s-]?up", "\\bdip(s)?\\b", "bench", "\\bjerk\\b", "\\bflye?s?\\b", "push press",
    ),
    MovementPattern.Pull to listOf(
        "\\brow(s)?\\b", "pull[\\s-]?up", "chin[\\s-]?up", "pulldown", "pull down",
        "\\bclean\\b", "\\bsnatch\\b", "pull[\\s-]?through",
    ),
).map { (pattern, keywords) -> pattern to keywords.map { Regex(it, RegexOption.IGNORE_CASE) } }

// DAV-65's one heuristic, judgment-call piece -- confirmed with the user
// after reviewing all 876 real names. Two rules came directly out of that
// review, both applied here rather than left as a "pick the first match"
// guess:
// 1. A name matching more than one pattern's keywords (e.g. "Squat Jerk"
//    hits both Squat and Push) is real ambiguity, not a tiebreak -- it
//    resolves to Unclassified rather than silently picking a winner.
// 2. A keyword matching only because it's a substring of an
//    already-matched higher-priority keyword (e.g. "leg press" containing
//    "press") is one real signal, not two -- suppressed via span overlap,
//    not counted toward ambiguity.
private fun classifyByName(name: String): MovementPattern? {
    val spans = mutableListOf<IntRange>()
    val hits = mutableListOf<MovementPattern>()
    for ((pattern, keywords) in NAME_PATTERNS) {
        val match = keywords.firstNotNullOfOrNull { it.find(name) } ?: continue
        val range = match.range
        val overlapsEarlier = spans.any { it.first <= range.last && range.first <= it.last }
        if (!overlapsEarlier) {
            spans += range
            hits += pattern
        }
    }
    return when (hits.size) {
        0 -> null
        1 -> hits.first()
        else -> MovementPattern.Unclassified
    }
}

// Stretching is never a strength movement pattern -- confirmed with the
// user after reviewing the real data (many stretch names superficially
// match a keyword, e.g. "Frog Hops"/"Groiners", without being a real
// squat/hinge/etc. training pattern). Checked before name-matching, not
// after. Real force/mechanic fields are the fallback when name-matching
// finds nothing -- `isolation` mechanic reliably means "not a compound
// pattern this taxonomy tracks."
fun classifyMovementPattern(exercise: ExerciseLibraryRow): MovementPattern {
    if (exercise.category == "stretching") return MovementPattern.Unclassified
    classifyByName(exercise.name)?.let { return it }
    if (exercise.mechanic == "isolation") return MovementPattern.Isolation
    return MovementPattern.Unclassified
}
