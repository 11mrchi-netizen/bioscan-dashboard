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
)

@Serializable
data class ExistingHydrationRow(val ml: Int? = null)

@Serializable
data class NewHydrationRow(val date: String, val ml: Int)

@Serializable
data class NewEncounterRow(
    val date: String,
    @SerialName("encounter_type") val encounterType: String? = null,
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
