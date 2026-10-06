package com.bioscan.fieldterminal.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SupplementRow(
    val id: Long,
    val name: String,
    val dose: String,
    @SerialName("time_of_day") val timeOfDay: String,
    val status: String,
    @SerialName("end_date") val endDate: String? = null,
    @SerialName("ai_note") val aiNote: String? = null,
    @SerialName("every_n_days") val everyNDays: Int? = null,
    // Supplement Intelligence Phase 1: links to a canonical supplement_products
    // row when one exists. Null for a roster item never given real ingredient
    // composition -- addSupplementsTaken()'s nutrient mapping falls back to
    // the pre-existing name-inference path for those, unchanged.
    @SerialName("product_id") val productId: Long? = null,
)

// DAV-81
@Serializable
data class NewSupplementRosterRow(
    val name: String,
    val dose: String,
    @SerialName("time_of_day") val timeOfDay: String,
    val status: String,
    @SerialName("start_date") val startDate: String,
    @SerialName("ai_note") val aiNote: String? = null,
    @SerialName("every_n_days") val everyNDays: Int? = null,
    @SerialName("product_id") val productId: Long? = null,
)

// Lightweight projection for computing last-taken dates per supplement in
// SupplementsForm — just the two fields needed, not the full log row.
@Serializable
data class SupplementLogDateRow(
    @SerialName("supplement_id") val supplementId: Long?,
    @SerialName("taken_at") val takenAt: String,
)
