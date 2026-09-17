package com.bioscan.fieldterminal.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Phase G3: replaces the run-only RunRow -- `exercise_sessions` covers any
// activity type, so Training's math (sumDistanceKmSince, longestRunKm,
// averagePaceMinPerKmSince in domain/Training.kt) now operates on whichever
// subset of these TrainingRepository filters to (running only, since
// DAV-79's re-check). `source` is needed to pair up a manual row and its
// Health-Connect-synced duplicate of the same real run -- see
// TrainingRepository's dedupeRunSessions().
@Serializable
data class ExerciseSessionRow(
    val type: String,
    @SerialName("start_time") val startTime: String,
    @SerialName("duration_min") val durationMin: Double? = null,
    @SerialName("distance_km") val distanceKm: Double? = null,
    @SerialName("avg_hr") val avgHr: Double? = null,
    val source: String? = null,
)

@Serializable
data class Vo2MaxRow(
    val date: String,
    val vo2max: Double? = null,
)
