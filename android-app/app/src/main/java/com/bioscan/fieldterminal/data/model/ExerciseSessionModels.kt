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
// ui/screens/AddEntrySheet.kt's ExerciseDetailsForm. rpe/notes/details are
// editable; every other field is Health-Connect-sourced and read-only in
// this app (per the 2026-09-15 direction that removed manual exercise
// logging entirely).
@Serializable
data class FullExerciseSessionRow(
    val id: Long,
    val type: String,
    val rpe: Int? = null,
    val notes: String? = null,
    val details: ExerciseSessionDetails = ExerciseSessionDetails(),
)

@Serializable
data class ExerciseDetailsUpdateRow(
    val rpe: Int? = null,
    val notes: String? = null,
    val details: ExerciseSessionDetails,
)

// Phase B follow-up. The type-specific keys exercise_sessions.details can
// hold, per the Phase B migration's own CHECK constraints -- route_type/
// run_type only meaningful (and only DB-constrained) when type='run',
// exercises only meaningful when type='strength'. All nullable: a session
// of one type simply carries null for the other type's fields, which is
// harmless to write back untouched.
@Serializable
data class ExerciseSessionDetails(
    @SerialName("route_type") val routeType: String? = null,
    @SerialName("run_type") val runType: String? = null,
    val exercises: List<StrengthExerciseDto>? = null,
)

@Serializable
data class StrengthExerciseDto(
    val name: String,
    val sets: List<StrengthSetDto>,
)

@Serializable
data class StrengthSetDto(
    val reps: Int,
    @SerialName("weight_kg") val weightKg: Double,
    val rpe: Int? = null,
    val rir: Int? = null,
    @SerialName("percent_1rm") val percentOneRm: Double? = null,
)

// Phase G4: the full row for SessionDetailScreen's summary card, plus
// health_connect_record_id/end_time -- neither of which the edit/log-feed
// rows above need, but the detail screen does (end_time to bound the
// on-demand Health Connect read; health_connect_record_id, null for
// migrated pre-Health-Connect rows, to know whether a time-series read is
// even possible for this session).
@Serializable
data class ExerciseSessionDetailRow(
    val id: Long,
    val type: String,
    @SerialName("start_time") val startTime: String,
    @SerialName("end_time") val endTime: String,
    @SerialName("duration_min") val durationMin: Double? = null,
    @SerialName("distance_km") val distanceKm: Double? = null,
    @SerialName("calories_active") val caloriesActive: Double? = null,
    @SerialName("calories_total") val caloriesTotal: Double? = null,
    @SerialName("avg_hr") val avgHr: Double? = null,
    @SerialName("max_hr") val maxHr: Double? = null,
    @SerialName("elevation_gain_m") val elevationGainM: Double? = null,
    @SerialName("avg_power_w") val avgPowerW: Double? = null,
    @SerialName("avg_speed_kmh") val avgSpeedKmh: Double? = null,
    val rpe: Int? = null,
    val notes: String? = null,
    @SerialName("health_connect_record_id") val healthConnectRecordId: String? = null,
    // DAV-144. Only route_type is read (gates the TRAIL card).
    val details: ExerciseSessionDetails = ExerciseSessionDetails(),
)
