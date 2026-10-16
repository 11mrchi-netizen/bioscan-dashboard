package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.data.ZeppStrengthData
import com.bioscan.fieldterminal.data.model.ExerciseSessionDetails
import com.bioscan.fieldterminal.data.model.StrengthExerciseDto
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.decodeFromJsonElement

// The phone's half of the strength import rules (the server's half is supabase/functions/zepp-extract/strengthImport.ts).
// A workout's exercises either follow Zepp (written by the import; a later correction in the Zepp app replaces them on
// the next sync) or are the person's own (changed in the edit form; never overwritten by a sync). When the person's
// version and Zepp's differ, the session offers Zepp's version and the person chooses; RE-IMPORT FROM ZEPP does the same
// on demand, for corrections made after the automatic three-day re-sync window.

const val EXERCISES_FROM_ZEPP = "zepp"
const val EXERCISES_FROM_APP = "app"

enum class ExercisesSource { ZEPP, APP, NONE }

/** Where a session's stored exercise list came from (rows from before the marker: Zepp put a code on every exercise). */
fun exercisesSource(details: ExerciseSessionDetails): ExercisesSource {
    val exercises = details.exercises
    if (exercises.isNullOrEmpty()) return ExercisesSource.NONE
    return when (details.exercisesSource) {
        EXERCISES_FROM_ZEPP -> ExercisesSource.ZEPP
        EXERCISES_FROM_APP -> ExercisesSource.APP
        else -> if (exercises.all { it.exerciseCode != null }) ExercisesSource.ZEPP else ExercisesSource.APP
    }
}

private val lenientJson = Json { ignoreUnknownKeys = true }

/** Zepp's current version of the exercises, or null when there is none or it cannot be read. */
fun ZeppStrengthData.zeppExercises(): List<StrengthExerciseDto>? =
    exercises?.let { runCatching { lenientJson.decodeFromJsonElement<List<StrengthExerciseDto>>(it) }.getOrNull() }?.takeIf { it.isNotEmpty() }

enum class ZeppVersionState {
    /** No Zepp version of this workout's exercises is stored. */
    NO_ZEPP_VERSION,

    /** The stored exercises are Zepp's, or the person's edit is based on Zepp's current version, or they match. */
    IN_SYNC,

    /** The person changed the exercises and Zepp's version differs from the one their edit was based on. */
    ZEPP_DIFFERS,
}

// What a person can correct: the exercises in order (by name) with each set's reps and weight.
private fun content(exercises: List<StrengthExerciseDto>): List<Pair<String, List<Pair<Int, Double>>>> =
    exercises.map { e -> e.name.trim().lowercase() to e.sets.map { it.reps to it.weightKg } }

fun zeppVersionState(details: ExerciseSessionDetails, zepp: ZeppStrengthData?): ZeppVersionState {
    val zeppExercises = zepp?.zeppExercises()
    val fingerprint = zepp?.fingerprint
    if (zeppExercises == null || fingerprint == null) return ZeppVersionState.NO_ZEPP_VERSION
    if (exercisesSource(details) != ExercisesSource.APP) return ZeppVersionState.IN_SYNC
    if (details.zeppFingerprint == fingerprint) return ZeppVersionState.IN_SYNC
    if (content(details.exercises.orEmpty()) == content(zeppExercises)) return ZeppVersionState.IN_SYNC
    return ZeppVersionState.ZEPP_DIFFERS
}

/**
 * The details to save from the edit form. Changing the exercises makes them the person's own (no sync overwrites them
 * afterwards); saving with the exercises unchanged (only RPE or notes) keeps where they came from, so a Zepp list keeps
 * following Zepp.
 */
fun editedDetails(initial: ExerciseSessionDetails, routeType: String?, runType: String?, exercises: List<StrengthExerciseDto>?): ExerciseSessionDetails {
    val changed = exercises.orEmpty() != initial.exercises.orEmpty()
    return initial.copy(
        routeType = routeType,
        runType = runType,
        exercises = exercises,
        exercisesSource = when {
            changed && !exercises.isNullOrEmpty() -> EXERCISES_FROM_APP
            exercises.isNullOrEmpty() -> null
            else -> initial.exercisesSource
        },
    )
}

/** One line per exercise of a version, for the comparison card: "Bench Press · 3 sets". */
fun exerciseSummary(exercises: List<StrengthExerciseDto>): List<String> =
    exercises.map { e -> "${e.name} · ${e.sets.size} set${if (e.sets.size == 1) "" else "s"}" }
