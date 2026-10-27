package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.domain.fuel.FuelBlock
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant

// The editable pre-race fuel schedule (supabase/migrations/20261106_fuel_schedule_debrief.sql; docs/race-fuel-rework/plan.md).
// One row per (kind, ref): 'event' keyed on the athlete event id, 'planned' on a planned session id, 'session' on a synced
// activity id.

@Serializable
data class FuelScheduleRow(
    val id: Long? = null,
    val kind: String,
    @SerialName("ref_key") val refKey: String,
    @SerialName("duration_min") val durationMin: Int,
    val blocks: List<FuelBlock>,
    @SerialName("seeded_from") val seededFrom: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null,
)

/** A supplement product read as fuel: what one serving brings, where its ingredients name the nutrient (null = not listed). */
data class SupplementFuelOption(val id: Long, val name: String, val servingForm: String?, val carbsG: Double?, val sodiumMg: Double?, val caffeineMg: Double?)

@Serializable
private data class SuppProductRow(val id: Long, val name: String, @SerialName("serving_form") val servingForm: String? = null)

@Serializable
private data class SuppLinkRow(
    @SerialName("supplement_product_id") val productId: Long,
    @SerialName("supplement_ingredient_id") val ingredientId: Long,
    @SerialName("compound_amount") val compoundAmount: Double? = null,
    @SerialName("compound_unit") val compoundUnit: String? = null,
    @SerialName("elemental_amount") val elementalAmount: Double? = null,
    @SerialName("elemental_unit") val elementalUnit: String? = null,
)

@Serializable
private data class SuppIngredientRow(val id: Long, val name: String)

/** Milligrams from an amount and its unit (mg, g, mcg/ug); null for any other unit. */
internal fun toMilligrams(amount: Double, unit: String?): Double? = when (unit?.trim()?.lowercase()) {
    "mg" -> amount
    "g" -> amount * 1000
    "mcg", "ug", "µg", "μg" -> amount / 1000
    else -> null
}

/** Which fuel nutrient an ingredient name is, by its words; null for everything else. */
internal fun fuelNutrientOf(ingredientName: String): String? {
    val n = ingredientName.lowercase()
    return when {
        "caffeine" in n -> "caffeine"
        "sodium" in n -> "sodium"
        "carbohydrate" in n || n == "carbs" -> "carbs"
        else -> null
    }
}

class FuelScheduleRepository(private val supabase: SupabaseClient) {

    suspend fun load(kind: String, refKey: String): FuelScheduleRow? =
        supabase.postgrest.from("fuel_schedules")
            .select(columns = Columns.list("id,kind,ref_key,duration_min,blocks,seeded_from,updated_at")) {
                filter { eq("kind", kind); eq("ref_key", refKey) }
                limit(1)
            }
            .decodeList<FuelScheduleRow>().firstOrNull()

    /** Saves in place: the same (kind, ref) updates the one row. */
    suspend fun save(kind: String, refKey: String, durationMin: Int, blocks: List<FuelBlock>, seededFrom: String?) {
        supabase.postgrest.from("fuel_schedules").upsert(
            FuelScheduleRow(kind = kind, refKey = refKey, durationMin = durationMin, blocks = blocks, seededFrom = seededFrom, updatedAt = Instant.now().toString()),
        ) { onConflict = "user_id,kind,ref_key" }
    }

    suspend fun delete(kind: String, refKey: String) {
        supabase.postgrest.from("fuel_schedules").delete { filter { eq("kind", kind); eq("ref_key", refKey) } }
    }

    /**
     * The supplement catalog as fuel: carbohydrate, sodium and caffeine per serving where the product's ingredients list them
     * (a pre-workout's caffeine, an electrolyte tablet's sodium). Sodium and caffeine are read in mg, carbohydrate in g.
     */
    suspend fun supplementOptions(): List<SupplementFuelOption> {
        val products = supabase.postgrest.from("supplement_products")
            .select(columns = Columns.list("id,name,serving_form")).decodeList<SuppProductRow>()
        if (products.isEmpty()) return emptyList()
        val links = supabase.postgrest.from("supplement_product_ingredients")
            .select(columns = Columns.list("supplement_product_id,supplement_ingredient_id,compound_amount,compound_unit,elemental_amount,elemental_unit"))
            .decodeList<SuppLinkRow>()
        val ingredients = if (links.isEmpty()) emptyMap() else
            supabase.postgrest.from("supplement_ingredients").select(columns = Columns.list("id,name"))
                .decodeList<SuppIngredientRow>().associateBy { it.id }
        val byProduct = links.groupBy { it.productId }
        return products.map { p ->
            var carbs: Double? = null
            var sodium: Double? = null
            var caffeine: Double? = null
            byProduct[p.id].orEmpty().forEach { l ->
                val kind = ingredients[l.ingredientId]?.name?.let(::fuelNutrientOf) ?: return@forEach
                // Sodium is the element in a salt: the elemental amount when the label gives one.
                val amount = l.elementalAmount ?: l.compoundAmount ?: return@forEach
                val unit = if (l.elementalAmount != null) l.elementalUnit else l.compoundUnit
                when (kind) {
                    "carbs" -> carbs = (carbs ?: 0.0) + (toMilligrams(amount, unit)?.div(1000) ?: 0.0)
                    "sodium" -> sodium = (sodium ?: 0.0) + (toMilligrams(amount, unit) ?: 0.0)
                    "caffeine" -> caffeine = (caffeine ?: 0.0) + (toMilligrams(amount, unit) ?: 0.0)
                }
            }
            SupplementFuelOption(p.id, p.name, p.servingForm, carbs, sodium, caffeine)
        }
    }
}
