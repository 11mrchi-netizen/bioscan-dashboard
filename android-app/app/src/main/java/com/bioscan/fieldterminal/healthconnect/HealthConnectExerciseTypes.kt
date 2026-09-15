package com.bioscan.fieldterminal.healthconnect

import androidx.health.connect.client.records.ExerciseSessionRecord

// Phase G3. Health Connect's ExerciseSessionRecord.exerciseType has 50+
// EXERCISE_TYPE_* constants and grows over time -- collapsed here into the
// small closed vocabulary exercise_sessions.type actually uses. Kept in the
// healthconnect package (not domain/) since it directly references Health
// Connect's own constants -- domain/ stays framework-free, same convention
// every other domain file in this app already follows. Unmapped/future
// types fall to "other" rather than crashing.
fun mapHealthConnectExerciseType(hcType: Int): String = when (hcType) {
    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING,
    ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL,
    -> "run"

    ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "walk"
    ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "hike"

    ExerciseSessionRecord.EXERCISE_TYPE_BIKING,
    ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY,
    -> "ride"

    ExerciseSessionRecord.EXERCISE_TYPE_SWIMMING_POOL -> "swim"

    ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING,
    ExerciseSessionRecord.EXERCISE_TYPE_WEIGHTLIFTING,
    ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS,
    ExerciseSessionRecord.EXERCISE_TYPE_BOOT_CAMP,
    -> "strength"

    ExerciseSessionRecord.EXERCISE_TYPE_YOGA,
    ExerciseSessionRecord.EXERCISE_TYPE_PILATES,
    ExerciseSessionRecord.EXERCISE_TYPE_STRETCHING,
    -> "yoga"

    else -> "other"
}
