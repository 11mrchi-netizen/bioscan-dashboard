package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.domain.training.GEAR_TAGS
import com.bioscan.fieldterminal.domain.training.GearRule
import com.bioscan.fieldterminal.domain.training.movementKey
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The person's gear rules ("straps on deadlifts from 140 kg"), table gear_rules (migration 20261127004134_gear_rules.sql). One row per
// lift (movementKey) and gear tag; the web writes the same rows. A new set pre-fills from them (domain/training/Gear.kt).
@Serializable
private data class GearRuleRow(
    @SerialName("movement_key") val movementKey: String,
    val gear: String,
    @SerialName("from_kg") val fromKg: Double? = null,
)

class GearRulesRepository(private val supabase: SupabaseClient) {

    suspend fun load(): List<GearRule> =
        supabase.postgrest.from("gear_rules")
            .select(columns = Columns.list("movement_key,gear,from_kg")) { order("movement_key", Order.ASCENDING) }
            .decodeList<GearRuleRow>().map { GearRule(it.movementKey, it.gear, it.fromKg) }

    /** Sets the rule for a lift and gear: new, or the threshold changed. [fromKg] null: at any load. */
    suspend fun save(exercise: String, gear: String, fromKg: Double?) {
        require(exercise.isNotBlank()) { "Name the lift." }
        require(gear in GEAR_TAGS) { "Pick a piece of gear." }
        require(fromKg == null || fromKg >= 0) { "The weight is a number of kg, 0 or more." }
        supabase.postgrest.from("gear_rules").upsert(GearRuleRow(movementKey(exercise), gear, fromKg)) { onConflict = "user_id,movement_key,gear" }
    }

    suspend fun delete(movementKey: String, gear: String) {
        supabase.postgrest.from("gear_rules").delete { filter { eq("movement_key", movementKey); eq("gear", gear) } }
    }
}
