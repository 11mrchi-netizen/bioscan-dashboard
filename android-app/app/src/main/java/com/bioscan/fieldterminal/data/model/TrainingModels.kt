package com.bioscan.fieldterminal.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RunRow(
    val date: String,
    @SerialName("distance_km") val distanceKm: Double? = null,
    @SerialName("pace_min_per_km") val paceMinPerKm: Double? = null,
)

@Serializable
data class Vo2MaxRow(
    val date: String,
    val vo2max: Double? = null,
)
