package com.bioscan.fieldterminal.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class LabDrawRow(
    val id: Long,
    @SerialName("draw_date") val drawDate: String,
    @SerialName("lab_name") val labName: String? = null,
)

@Serializable
data class LabResultRow(
    @SerialName("draw_id") val drawId: Long,
    @SerialName("marker_name") val markerName: String,
    val value: Double? = null,
    @SerialName("value_text") val valueText: String? = null,
    val unit: String? = null,
    @SerialName("ref_low") val refLow: Double? = null,
    @SerialName("ref_high") val refHigh: Double? = null,
    val flag: String? = null,
)
