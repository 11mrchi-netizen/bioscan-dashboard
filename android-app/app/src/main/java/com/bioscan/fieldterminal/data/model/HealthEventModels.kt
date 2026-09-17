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
data class IllnessRow(
    val id: Long,
    val name: String,
    val symptoms: String? = null,
    val status: String,
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String? = null,
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
