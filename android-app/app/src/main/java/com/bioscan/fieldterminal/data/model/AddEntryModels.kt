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
// EncounterForm still only collects date/encounter_type/notes, so those two
// fields stay null on every write that goes through it.
@Serializable
data class NewEncounterRow(
    val date: String,
    @SerialName("encounter_type") val encounterType: String? = null,
    val notes: String? = null,
    @SerialName("person_id") val personId: Long? = null,
    @SerialName("calendar_event_title") val calendarEventTitle: String? = null,
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
