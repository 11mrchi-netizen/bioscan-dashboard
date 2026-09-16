package com.bioscan.fieldterminal.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LogMealRow(
    val id: Long,
    @SerialName("logged_at") val loggedAt: String,
    val description: String? = null,
    val calories: Double? = null,
    @SerialName("protein_g") val proteinG: Double? = null,
    @SerialName("carbs_g") val carbsG: Double? = null,
    @SerialName("fat_g") val fatG: Double? = null,
)

// Phase G3: replaces LogRunRow -- `exercise_sessions` covers any activity
// type, always with a real start_time (even migrated pre-Health-Connect
// rows carry a nominal-but-real timestamp), so this needs no separate
// nominal-time handling in domain/Log.kt the way the old date-only `runs`
// table did.
@Serializable
data class LogExerciseRow(
    val id: Long,
    val type: String,
    @SerialName("start_time") val startTime: String,
    @SerialName("distance_km") val distanceKm: Double? = null,
    @SerialName("duration_min") val durationMin: Double? = null,
    @SerialName("avg_hr") val avgHr: Double? = null,
)

@Serializable
data class LogSleepRow(
    val id: Long,
    val date: String,
    val hours: Double? = null,
    val score: Int? = null,
)

@Serializable
data class LogArousalRow(
    val id: Long,
    val date: String,
    @SerialName("morning_erection_quality") val morningErectionQuality: Int? = null,
    @SerialName("arousal_level") val arousalLevel: Int? = null,
)

@Serializable
data class LogStoolRow(
    val id: Long,
    @SerialName("occurred_at") val occurredAt: String,
    @SerialName("bristol_type") val bristolType: Int,
    val discomfort: Int? = null,
)

@Serializable
data class LogEncounterRow(
    val id: Long,
    val date: String,
    val status: String,
    @SerialName("encounter_type") val encounterType: String? = null,
    val notes: String? = null,
    @SerialName("calendar_event_title") val calendarEventTitle: String? = null,
    @SerialName("person_id") val personId: Long? = null,
)

@Serializable
data class LogNoteRow(
    val id: Long,
    @SerialName("occurred_at") val occurredAt: String,
    val text: String,
)

@Serializable
data class LogHydrationRow(
    val id: Long,
    val date: String,
    val ml: Int? = null,
)

@Serializable
data class LogWellbeingRow(
    val id: Long,
    val date: String,
    val energy: Int? = null,
    val mood: Int? = null,
    val stress: Int? = null,
    val soreness: Int? = null,
)

@Serializable
data class LogOstrcRow(
    val id: Long,
    @SerialName("check_date") val checkDate: String,
    @SerialName("body_area") val bodyArea: String,
    val q1: Int,
    val q2: Int,
    val q3: Int,
    val q4: Int,
    val notes: String? = null,
)

@Serializable
data class LogSupplementTakenRow(
    val id: Long,
    @SerialName("supplement_name") val supplementName: String,
    @SerialName("taken_at") val takenAt: String,
)
