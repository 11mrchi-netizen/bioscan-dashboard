package com.bioscan.fieldterminal.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MealRow(
    @SerialName("logged_at") val loggedAt: String, // ISO timestamptz, UTC
    val calories: Double? = null,
    @SerialName("protein_g") val proteinG: Double? = null,
    @SerialName("fat_g") val fatG: Double? = null,
    @SerialName("carbs_g") val carbsG: Double? = null,
)

@Serializable
data class HydrationDailyRow(
    val date: String,
    val ml: Int? = null,
)
