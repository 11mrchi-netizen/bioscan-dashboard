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
    // Regimen model (migration 20261115_regimen_model.sql): read through domain/Regimen.kt's spec(), which falls back to
    // the columns above for rows (or cached copies) that do not carry these.
    @SerialName("start_date") val startDate: String? = null,
    val kind: String? = null,
    val form: String? = null,
    val route: String? = null,
    @SerialName("dose_amount") val doseAmount: Double? = null,
    @SerialName("dose_unit") val doseUnit: String? = null,
    val slots: List<String> = emptyList(),
    val schedule: com.bioscan.fieldterminal.domain.ScheduleJson? = null,
    @SerialName("dose_steps") val doseSteps: com.bioscan.fieldterminal.domain.DoseStepsJson? = null,
    /** The prescriber's syringe units (U-100) for one dose, for injectables. */
    @SerialName("syringe_units") val syringeUnits: Double? = null,
)

/** Every column [SupplementRow] reads, for selects. */
const val SUPPLEMENT_ROW_COLUMNS =
    "id,name,dose,time_of_day,status,start_date,end_date,ai_note,every_n_days,product_id,kind,form,route,dose_amount,dose_unit,slots,schedule,dose_steps,syringe_units"

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
