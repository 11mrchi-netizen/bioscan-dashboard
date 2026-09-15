package com.bioscan.fieldterminal.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class InjuryRow(
    val part: String,
    val type: String,
    val status: String,
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String? = null,
    val notes: String? = null,
)

@Serializable
data class IllnessRow(
    val name: String,
    val symptoms: String? = null,
    val status: String,
    @SerialName("start_date") val startDate: String,
    @SerialName("end_date") val endDate: String? = null,
)
