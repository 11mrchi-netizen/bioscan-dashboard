package com.bioscan.fieldterminal.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class NewMealRow(
    @SerialName("logged_at") val loggedAt: String,
    val description: String,
    val calories: Double? = null,
    @SerialName("protein_g") val proteinG: Double? = null,
    @SerialName("carbs_g") val carbsG: Double? = null,
    @SerialName("fat_g") val fatG: Double? = null,
    @SerialName("fiber_g") val fiberG: Double? = null,
    @SerialName("sugar_g") val sugarG: Double? = null,
    @SerialName("sodium_mg") val sodiumMg: Double? = null,
)

@Serializable
data class ExistingHydrationRow(val ml: Int? = null)

@Serializable
data class NewHydrationRow(val date: String, val ml: Int)

// personId/calendarEventTitle (Phase M3) are only ever set by the Map tab's
// "LOG ENCOUNTER" action (see ui/screens/MapScreen.kt) -- the Log tab's own
// EncounterForm collects the rest (occurredAt onward, DAV-158/159) but never
// these two, so they stay null on every Log-tab write.
@Serializable
data class NewEncounterRow(
    val date: String,
    @SerialName("occurred_at") val occurredAt: String? = null,
    @SerialName("encounter_type") val encounterType: String? = null,
    @SerialName("location_type") val locationType: String? = null,
    @SerialName("duration_min") val durationMin: Int? = null,
    val activities: List<String>? = null,
    @SerialName("my_rating") val myRating: Int? = null,
    val notes: String? = null,
    @SerialName("person_id") val personId: Long? = null,
    @SerialName("calendar_event_title") val calendarEventTitle: String? = null,
)

// DAV-158/159. Update-only counterpart to NewEncounterRow that deliberately
// omits personId/calendarEventTitle -- a real bug found live: reusing
// NewEncounterRow (with those two defaulted to null) as the UPDATE payload
// silently nulled out an already-linked encounter's person_id/calendar_event_title
// on every edit, since Postgrest writes every field the DTO carries. This
// type structurally can't touch those two columns.
@Serializable
data class EncounterEditRow(
    val date: String,
    @SerialName("occurred_at") val occurredAt: String? = null,
    @SerialName("encounter_type") val encounterType: String? = null,
    @SerialName("location_type") val locationType: String? = null,
    @SerialName("duration_min") val durationMin: Int? = null,
    val activities: List<String>? = null,
    @SerialName("my_rating") val myRating: Int? = null,
    val notes: String? = null,
)

@Serializable
data class NewStoolRow(
    @SerialName("occurred_at") val occurredAt: String,
    @SerialName("bristol_type") val bristolType: Int,
    val discomfort: Int? = null,
)

@Serializable
data class NewArousalRow(
    val date: String,
    @SerialName("morning_erection_quality") val morningErectionQuality: Int,
    @SerialName("arousal_level") val arousalLevel: Int,
)

// DAV-91
@Serializable
data class NewMasturbationRow(
    @SerialName("occurred_at") val occurredAt: String,
    @SerialName("watched_porn") val watchedPorn: Boolean,
    @SerialName("load_size") val loadSize: Int? = null,
    @SerialName("orgasm_intensity") val orgasmIntensity: Int? = null,
    val notes: String? = null,
)

@Serializable
data class NewNoteRow(
    @SerialName("occurred_at") val occurredAt: String,
    val text: String,
)

@Serializable
data class NewWellbeingRow(
    val date: String,
    val energy: Int? = null,
    val mood: Int? = null,
    val stress: Int? = null,
    val soreness: Int? = null,
)

@Serializable
data class NewSupplementLogRow(
    @SerialName("supplement_id") val supplementId: Long,
    @SerialName("supplement_name") val supplementName: String,
    @SerialName("taken_at") val takenAt: String,
)

// Phase A4 (Category 8). severity_score is a stored generated column
// (q1+q2+q3+q4, see Phase A1's migration) -- never written by the client.
@Serializable
data class NewOstrcRow(
    @SerialName("check_date") val checkDate: String,
    @SerialName("body_area") val bodyArea: String,
    val q1: Int,
    val q2: Int,
    val q3: Int,
    val q4: Int,
    val notes: String? = null,
)
