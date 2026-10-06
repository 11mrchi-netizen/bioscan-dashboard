package com.bioscan.fieldterminal.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class InjuryRow(
    val id: Long,
    val part: String,
    val type: String,
    val status: String,
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String? = null,
    val notes: String? = null,
)

@Serializable
data class MedicationEntry(val name: String, val dose: String, val frequency: String)

@Serializable
data class IllnessRow(
    val id: Long,
    val name: String,
    val symptoms: String? = null,
    val status: String,
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String? = null,
    @SerialName("doctor_seen") val doctorSeen: Boolean = false,
    val medications: List<MedicationEntry> = emptyList(),
)

@Serializable
data class NewIllnessRow(
    val name: String,
    val symptoms: String? = null,
    val status: String,
    @SerialName("start_date") val startDate: String,
    @SerialName("doctor_seen") val doctorSeen: Boolean,
    val medications: List<MedicationEntry>,
    val notes: String? = null,
)

// DAV-88
@Serializable
data class NewInjuryRow(
    val part: String,
    val type: String,
    val severity: Int,
    val status: String,
    @SerialName("start_date") val startDate: String,
    val notes: String? = null,
)
