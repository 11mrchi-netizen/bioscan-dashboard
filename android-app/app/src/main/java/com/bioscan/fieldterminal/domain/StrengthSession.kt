package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.data.model.ExerciseLibraryRow
import com.bioscan.fieldterminal.data.model.StrengthExerciseDto

// DAV-293: a session-level aggregate assembled purely from StrengthLoad.kt's
// existing per-set/per-exercise math -- no new formulas here, just the shape
// SessionDetailScreen's StrengthCard and the future User page both need
// without each recomputing the same numbers from raw DTOs separately.
data class ResolvedStrengthExercise(
    val exercise: StrengthExerciseDto,
    // Null when the logged name didn't resolve against exercise_library -- a
    // real data gap (see StrengthLoad.kt's resolveExercise), never guessed.
    val library: ExerciseLibraryRow?,
    val volumeLoad: Double,
    val bestEstimatedOneRepMax: Double?,
)

data class StrengthSession(
    val exercises: List<ResolvedStrengthExercise>,
    val totalVolumeLoad: Double,
    val bestEstimatedOneRepMaxOverall: Double?,
    val averageRpe: Double?,
    val averageRir: Double?,
    val regionalLoad: Map<BodyRegion, Double>,
    val movementPatternLoad: Map<MovementPattern, Double>,
) {
    // Lets a caller show e.g. "3 of 4 exercises matched" rather than
    // silently dropping the unresolved one's regional/pattern contribution.
    val resolvedExerciseCount: Int get() = exercises.count { it.library != null }
}

fun buildStrengthSession(exercises: List<StrengthExerciseDto>, library: List<ExerciseLibraryRow>): StrengthSession {
    val resolved = exercises.map { exercise ->
        ResolvedStrengthExercise(
            exercise = exercise,
            library = resolveExercise(exercise.name, library),
            volumeLoad = exerciseVolumeLoad(exercise),
            bestEstimatedOneRepMax = bestEstimatedOneRepMax(exercise),
        )
    }
    return StrengthSession(
        exercises = resolved,
        totalVolumeLoad = sessionVolumeLoad(exercises),
        bestEstimatedOneRepMaxOverall = resolved.mapNotNull { it.bestEstimatedOneRepMax }.maxOrNull(),
        averageRpe = sessionAverageRpe(exercises),
        averageRir = sessionAverageRir(exercises),
        regionalLoad = sessionRegionalLoad(exercises, library),
        movementPatternLoad = movementPatternLoad(exercises, library),
    )
}
