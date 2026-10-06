package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.SupplementRow
import com.bioscan.fieldterminal.domain.StackEntry
import com.bioscan.fieldterminal.domain.StackIngredient
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate

@Serializable
private data class StackProductRow(
    val id: Long,
    @SerialName("match_confidence") val matchConfidence: String? = null,
)

@Serializable
private data class StackProductIngredientRow(
    @SerialName("supplement_product_id") val productId: Long,
    @SerialName("supplement_ingredient_id") val ingredientId: Long,
    @SerialName("compound_amount") val compoundAmount: Double,
    @SerialName("compound_unit") val compoundUnit: String,
    @SerialName("elemental_amount") val elementalAmount: Double? = null,
    @SerialName("elemental_unit") val elementalUnit: String? = null,
)

@Serializable
private data class StackIngredientRow(
    val id: Long,
    @SerialName("nutrient_key") val nutrientKey: String? = null,
    @SerialName("canonical_key") val canonicalKey: String? = null,
)

// Builds domain/SupplementStack.kt's input from the live roster. Kotlin-side
// joins (project convention). Only status = active supplements: unlike the
// roster display filter, a recently ended supplement is no longer part of the
// stack.
class SupplementStackRepository(private val supabase: SupabaseClient) {

    suspend fun loadActiveStack(): List<StackEntry> {
        val today = LocalDate.now()
        val supplements = supabase.postgrest.from("supplements")
            .select(columns = Columns.list("id,name,dose,time_of_day,status,end_date,every_n_days,product_id"))
            .decodeList<SupplementRow>()
            .filter { it.status.equals("active", ignoreCase = true) }
            .filter { it.endDate == null || LocalDate.parse(it.endDate) >= today }
        if (supplements.isEmpty()) return emptyList()

        val productIds = supplements.mapNotNull { it.productId }.distinct()
        val products = if (productIds.isEmpty()) emptyMap() else
            supabase.postgrest.from("supplement_products")
                .select(columns = Columns.list("id,match_confidence")) { filter { isIn("id", productIds) } }
                .decodeList<StackProductRow>().associateBy { it.id }
        val links = if (productIds.isEmpty()) emptyList() else
            supabase.postgrest.from("supplement_product_ingredients")
                .select(columns = Columns.list("supplement_product_id,supplement_ingredient_id,compound_amount,compound_unit,elemental_amount,elemental_unit")) {
                    filter { isIn("supplement_product_id", productIds) }
                }
                .decodeList<StackProductIngredientRow>()
        val ingredients = if (links.isEmpty()) emptyMap() else
            supabase.postgrest.from("supplement_ingredients")
                .select(columns = Columns.list("id,nutrient_key,canonical_key")) {
                    filter { isIn("id", links.map { it.ingredientId }.distinct()) }
                }
                .decodeList<StackIngredientRow>().associateBy { it.id }
        val linksByProduct = links.groupBy { it.productId }

        return supplements.map { s ->
            StackEntry(
                supplementId = s.id,
                name = s.name,
                everyNDays = s.everyNDays,
                asNeeded = s.timeOfDay.equals("as-needed", ignoreCase = true),
                matchConfidence = s.productId?.let { products[it]?.matchConfidence },
                ingredients = s.productId?.let { linksByProduct[it] }.orEmpty().mapNotNull { link ->
                    val ing = ingredients[link.ingredientId] ?: return@mapNotNull null
                    StackIngredient(
                        canonicalKey = ing.canonicalKey,
                        nutrientKey = ing.nutrientKey,
                        amount = link.elementalAmount ?: link.compoundAmount,
                        unit = link.elementalUnit ?: link.compoundUnit,
                        hasElemental = link.elementalAmount != null,
                    )
                },
            )
        }
    }
}
