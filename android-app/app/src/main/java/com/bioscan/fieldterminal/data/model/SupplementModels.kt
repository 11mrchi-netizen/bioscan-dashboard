package com.bioscan.fieldterminal.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SupplementRow(
    val name: String,
    val dose: String,
    @SerialName("time_of_day") val timeOfDay: String,
    val status: String,
    @SerialName("end_date") val endDate: String? = null,
)
