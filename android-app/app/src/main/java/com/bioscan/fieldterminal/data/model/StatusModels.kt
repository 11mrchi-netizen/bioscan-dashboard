package com.bioscan.fieldterminal.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Row shapes match the real Supabase schema exactly (same project the web
// dashboard/index.html reads) -- only the columns Step 5 actually needs are
// selected in the queries that decode these, not full-table shapes.

@Serializable
data class WearableDailyRow(
    val date: String, // ISO date, e.g. "2026-09-15"
    val rhr: Double? = null,
    val hrv: Double? = null,
)

@Serializable
data class SleepDailyRow(
    val date: String,
    val hours: Double? = null,
)

@Serializable
data class HealthEventRow(
    val status: String,
    @SerialName("end_date") val endDate: String? = null,
)
