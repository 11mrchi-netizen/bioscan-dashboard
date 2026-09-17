package com.bioscan.fieldterminal.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Fuzzy-matching parameters/results for the match_exercise_library() Postgres
// function (pg_trgm similarity, see the migration that created it) -- a
// read-side convenience over exercise_library (Phase C), never a hard
// foreign key: logged exercise names stay free text (see
// data/model/ExerciseSessionModels.kt's StrengthExerciseDto).
@Serializable
data class MatchExerciseLibraryParams(
    @SerialName("search_query") val searchQuery: String,
    @SerialName("match_limit") val matchLimit: Int = 5,
)

@Serializable
data class ExerciseLibraryMatch(
    val id: String,
    val name: String,
    val similarity: Float,
)

// DAV-65: the full row, for bulk anatomical-mapping work (regional load
// vectors, movement-pattern classification) rather than one-at-a-time
// autocomplete lookups -- see domain/RegionalLoad.kt.
@Serializable
data class ExerciseLibraryRow(
    val id: String,
    val name: String,
    val category: String? = null,
    val force: String? = null,
    val mechanic: String? = null,
    @SerialName("primary_muscles") val primaryMuscles: List<String> = emptyList(),
    @SerialName("secondary_muscles") val secondaryMuscles: List<String> = emptyList(),
)
