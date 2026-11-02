package com.bioscan.fieldterminal.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonTransformingSerializer
import kotlinx.serialization.json.intOrNull

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
// Zepp records the rest periods of a template workout as laps with exercise code 0. They are not exercises ("Exercise 0"),
// so they are dropped when the stored list is read -- this also cleans up workouts imported before the import skipped them.
private const val REST_EXERCISE_CODE = 0

object RestFilteredExercisesSerializer : JsonTransformingSerializer<List<StrengthExerciseDto>>(ListSerializer(StrengthExerciseDto.serializer())) {
    override fun transformDeserialize(element: JsonElement): JsonElement {
        if (element !is JsonArray) return element
        return JsonArray(element.filterNot { e ->
            ((e as? JsonObject)?.get("exercise_code") as? JsonPrimitive)?.intOrNull == REST_EXERCISE_CODE
        })
    }
}

@Serializable
data class ExerciseSessionDetails(
    @SerialName("route_type") val routeType: String? = null,
    @SerialName("run_type") val runType: String? = null,
    @Serializable(with = RestFilteredExercisesSerializer::class) val exercises: List<StrengthExerciseDto>? = null,
    // Where `exercises` came from: "zepp" (written by the Zepp import, follows later corrections made in Zepp) or "app"
    // (changed in the edit form, never overwritten by a sync). Null on rows from before the marker; see
    // domain/StrengthImport.kt and supabase/functions/zepp-extract/strengthImport.ts.
    @SerialName("exercises_source") val exercisesSource: String? = null,
    // Fingerprint of the Zepp version the exercises are based on; Zepp's current one differing from it is what makes
    // the session offer "Zepp has a different version".
    @SerialName("zepp_fingerprint") val zeppFingerprint: String? = null,
)

@Serializable
data class StrengthExerciseDto(
    val name: String,
    @SerialName("exercise_code") val exerciseCode: Int? = null,
    val sets: List<StrengthSetDto>,
)

@Serializable
data class StrengthSetDto(
    val reps: Int,
    @SerialName("weight_kg") val weightKg: Double,
    val rpe: Int? = null,
    val rir: Int? = null,
    @SerialName("percent_1rm") val percentOneRm: Double? = null,
    // Zepp-sourced fields — null for manual imports
    @SerialName("duration_sec") val durationSec: Int? = null,
    @SerialName("avg_hr") val avgHr: Int? = null,
    @SerialName("detected_reps") val detectedReps: Int? = null,
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
