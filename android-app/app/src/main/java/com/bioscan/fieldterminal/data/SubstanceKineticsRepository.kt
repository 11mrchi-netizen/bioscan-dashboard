package com.bioscan.fieldterminal.data

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// The person's own kinetics for a regimen item (table substance_kinetics): the half-life, peak time and amount per serving that
// no FDA label gave (a peptide, a hormone, a product with no ingredient row). They win over the label's half-life in TODAY's
// IN THE BODY curves (data/SubstanceRepository.kt).

@Serializable
data class KineticsRow(
    @SerialName("supplement_id") val supplementId: Long,
    @SerialName("half_life_hours") val halfLifeHours: Double,
    @SerialName("tmax_hours") val tmaxHours: Double? = null,
    @SerialName("amount_per_serving") val amountPerServing: Double? = null,
    @SerialName("amount_unit") val amountUnit: String? = null,
    val note: String? = null,
)

class SubstanceKineticsRepository(private val supabase: SupabaseClient) {
    suspend fun load(supplementId: Long): KineticsRow? =
        supabase.postgrest.from("substance_kinetics")
            .select(Columns.list("supplement_id,half_life_hours,tmax_hours,amount_per_serving,amount_unit,note")) { filter { eq("supplement_id", supplementId) } }
            .decodeList<KineticsRow>().firstOrNull()

    suspend fun save(row: KineticsRow) {
        supabase.postgrest.from("substance_kinetics").upsert(buildJsonObject {
            put("supplement_id", row.supplementId)
            put("half_life_hours", row.halfLifeHours)
            put("tmax_hours", row.tmaxHours)
            put("amount_per_serving", row.amountPerServing)
            put("amount_unit", row.amountUnit)
            put("note", row.note?.take(500))
        }) { onConflict = "supplement_id" }
    }

    suspend fun remove(supplementId: Long) {
        supabase.postgrest.from("substance_kinetics").delete { filter { eq("supplement_id", supplementId) } }
    }
}
