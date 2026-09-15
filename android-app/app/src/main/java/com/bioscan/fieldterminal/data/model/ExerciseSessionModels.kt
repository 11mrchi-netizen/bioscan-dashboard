package com.bioscan.fieldterminal.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Phase G3. Unlike HealthConnectSyncModels.kt's daily upserts, one combined
// row is safe here: the conflict key (user_id, health_connect_record_id) is
// stable per session, and every re-sync of the same session re-reads the
// same underlying Health Connect data (see HealthConnectExerciseSyncRepository's
// header comment) -- no cross-source null-overwrite risk.
@Serializable
data class NewExerciseSessionRow(
    val type: String,
    @SerialName("start_time") val startTime: String,
    @SerialName("end_time") val endTime: String,
    @SerialName("duration_min") val durationMin: Double,
    @SerialName("distance_km") val distanceKm: Double? = null,
    @SerialName("calories_active") val caloriesActive: Double? = null,
    @SerialName("calories_total") val caloriesTotal: Double? = null,
    @SerialName("avg_hr") val avgHr: Double? = null,
    @SerialName("max_hr") val maxHr: Double? = null,
    @SerialName("elevation_gain_m") val elevationGainM: Double? = null,
    @SerialName("avg_power_w") val avgPowerW: Double? = null,
    @SerialName("avg_speed_kmh") val avgSpeedKmh: Double? = null,
    @SerialName("health_connect_record_id") val healthConnectRecordId: String,
    val source: String = "health_connect",
)

// Fetched when editing a Log entry's "extra details" -- see
// ui/screens/AddEntrySheet.kt's ExerciseDetailsForm. Only rpe/notes are
// editable; every other field is Health-Connect-sourced and read-only in
// this app (per the 2026-09-15 direction that removed manual exercise
// logging entirely).
@Serializable
data class FullExerciseSessionRow(
    val id: Long,
    val type: String,
    val rpe: Int? = null,
    val notes: String? = null,
)

@Serializable
data class ExerciseDetailsUpdateRow(
    val rpe: Int? = null,
    val notes: String? = null,
)
