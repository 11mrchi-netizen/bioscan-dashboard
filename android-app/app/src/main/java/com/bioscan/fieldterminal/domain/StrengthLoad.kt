package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.data.model.ExerciseLibraryRow
import com.bioscan.fieldterminal.data.model.StrengthExerciseDto
import com.bioscan.fieldterminal.data.model.StrengthSetDto

// DAV-57 (Analysis Layer 2, milestone 2). Strength-specific load
// calculations from DAV-54's structured `details.exercises[].sets[]` shape.
// Velocity-loss metrics (also in scope per the ticket) are skipped entirely,
// not stubbed -- DAV-54's own schema doc excluded velocity outright since no
// ingestion path exists or is planned for it; a function that could only
// ever return null isn't worth having.

// Exercise identity is resolved against exercise_library by exact
// case-insensitive name match -- not the fuzzy match_exercise_library() RPC
// (a UI autocomplete helper per that file's own header comment, not meant
// for batch resolution) and not exercise_id (nothing writes it yet, per
// DAV-54's doc). This account's one real structured session logs names that
// already match exercise_library verbatim ("Front Barbell Squat", "Weighted
// Pull Ups", "Standing Military Press"), so exact match is the honest
// default; a name with no exact match resolves to no regional/
// movement-pattern data rather than a guessed fuzzy one.
fun resolveExercise(name: String, library: List<ExerciseLibraryRow>): ExerciseLibraryRow? =
    library.find { it.name.equals(name, ignoreCase = true) }

// Standard tonnage -- reps x load. The one universally-agreed strength
// volume metric; every other output here builds on it.
fun volumeLoad(set: StrengthSetDto): Double = set.reps * set.weightKg

fun exerciseVolumeLoad(exercise: StrengthExerciseDto): Double = exercise.sets.sumOf { volumeLoad(it) }

fun sessionVolumeLoad(exercises: List<StrengthExerciseDto>): Double = exercises.sumOf { exerciseVolumeLoad(it) }

// Epley formula (1RM = weight x (1 + reps/30)) -- the most widely used
// rep-max estimate and simplest to inspect/version. Gated to reps<=12:
// every rep-max formula's error grows sharply past that range (a "1RM"
// estimated from a 20-rep set is not a real estimate of anything), so this
// returns null rather than a number nobody should trust past that point.
private const val MAX_REPS_FOR_ONE_RM_ESTIMATE = 12
fun estimatedOneRepMax(set: StrengthSetDto): Double? =
    if (set.reps in 1..MAX_REPS_FOR_ONE_RM_ESTIMATE) set.weightKg * (1 + set.reps / 30.0) else null

// The best (highest) estimate across a logged exercise's sets -- how
// "today's estimated 1RM" is conventionally reported, not an average.
fun bestEstimatedOneRepMax(exercise: StrengthExerciseDto): Double? =
    exercise.sets.mapNotNull { estimatedOneRepMax(it) }.maxOrNull()

// Relative intensity as %1RM. A logged percent_1rm is the user's own
// recorded value (raw, per DAV-53) and always wins over a modeled Epley
// estimate computed from the exercise's own best set -- never blended.
fun relativeIntensityPercent(set: StrengthSetDto, exercise: StrengthExerciseDto): Double? {
    set.percentOneRm?.let { return it }
    val oneRm = bestEstimatedOneRepMax(exercise) ?: return null
    if (oneRm <= 0) return null
    return set.weightKg / oneRm * 100.0
}

// Session-level RPE/RIR -- mean of whatever was actually logged, per
// DAV-66's "sparse RPE: do not impute as if observed": a set with no RPE
// contributes nothing, not a filled-in average, and the average itself is
// null (not 0) when nothing in the session logged one.
fun sessionAverageRpe(exercises: List<StrengthExerciseDto>): Double? =
    exercises.flatMap { it.sets }.mapNotNull { it.rpe }.let { if (it.isEmpty()) null else it.average() }

fun sessionAverageRir(exercises: List<StrengthExerciseDto>): Double? =
    exercises.flatMap { it.sets }.mapNotNull { it.rir }.let { if (it.isEmpty()) null else it.average() }

// Movement-pattern load: each resolved exercise's volume load attributed to
// its DAV-65 movement pattern. An unresolved exercise name contributes
// nothing here -- a real data-availability gap, distinct from
// `MovementPattern.Unclassified` (which is a real classification outcome
// for an exercise that *did* resolve).
fun movementPatternLoad(exercises: List<StrengthExerciseDto>, library: List<ExerciseLibraryRow>): Map<MovementPattern, Double> {
    val totals = mutableMapOf<MovementPattern, Double>()
    for (exercise in exercises) {
        val resolved = resolveExercise(exercise.name, library) ?: continue
        val pattern = classifyMovementPattern(resolved)
        totals[pattern] = (totals[pattern] ?: 0.0) + exerciseVolumeLoad(exercise)
    }
    return totals
}

// Regional (anatomical) load: DAV-65's regionalLoadVector() applied per
// exercise and merged across the whole session.
fun sessionRegionalLoad(exercises: List<StrengthExerciseDto>, library: List<ExerciseLibraryRow>): Map<BodyRegion, Double> {
    val totals = mutableMapOf<BodyRegion, Double>()
    for (exercise in exercises) {
        val resolved = resolveExercise(exercise.name, library) ?: continue
        regionalLoadVector(resolved, exerciseVolumeLoad(exercise)).forEach { (region, load) ->
            totals[region] = (totals[region] ?: 0.0) + load
        }
    }
    return totals
}
