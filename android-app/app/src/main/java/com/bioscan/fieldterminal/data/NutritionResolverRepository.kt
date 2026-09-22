package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.FoodNutrientRow
import com.bioscan.fieldterminal.data.model.FoodRow
import com.bioscan.fieldterminal.data.model.FoodServingRow
import com.bioscan.fieldterminal.data.model.HydrationFactorModelRow
import com.bioscan.fieldterminal.domain.EffectiveHydration
import com.bioscan.fieldterminal.domain.ResolvedNutrients
import com.bioscan.fieldterminal.domain.computeEffectiveHydration
import com.bioscan.fieldterminal.domain.resolveConsumedBaseAmount
import com.bioscan.fieldterminal.domain.resolveFoodItemNutrients
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.serialization.Serializable

private const val DEFAULT_HYDRATION_MODEL_VERSION = "v1"

data class FoodProvenance(
    val foodId: Long,
    val foodSourceId: Long,
    val sourceFoodId: String?,
    val datasetVersion: String?,
)

data class ResolvedMealItem(
    val nutrients: ResolvedNutrients,
    val consumedAmount: Double,
    val hydration: EffectiveHydration?,
    val provenance: FoodProvenance,
)

// DAV-164. Bridges the pure domain/NutritionResolver.kt math to Supabase --
// fetches exactly one food's own foods/food_nutrients/food_servings rows
// (never scans all foods; candidate search is Gemini/barcode/text-search's
// job upstream of this), resolves one meal item, and keeps the
// food -> source -> dataset-version provenance chain attached to the result
// so it survives past the raw rows this function discards.
class NutritionResolverRepository(private val supabase: SupabaseClient) {

    suspend fun resolveMealItem(
        foodId: Long,
        quantity: Double,
        quantityUnit: String,
        servingId: Long? = null,
        servingCount: Double? = null,
        hydrationModelVersion: String = DEFAULT_HYDRATION_MODEL_VERSION,
    ): ResolvedMealItem {
        val food = supabase.postgrest.from("foods")
            .select(columns = Columns.list("id,food_source_id,source_food_id,beverage_class,beverage_subtype")) {
                filter { eq("id", foodId) }
            }
            .decodeSingle<FoodRow>()

        val nutrients = supabase.postgrest.from("food_nutrients")
            .select(columns = Columns.list("id,food_id,nutrient,amount,unit,basis_qty,basis_unit")) {
                filter { eq("food_id", foodId) }
            }
            .decodeList<FoodNutrientRow>()

        val serving = servingId?.let { id ->
            supabase.postgrest.from("food_servings")
                .select(columns = Columns.list("id,food_id,serving_name,grams,ml")) { filter { eq("id", id) } }
                .decodeSingle<FoodServingRow>()
        }

        val consumedAmount = resolveConsumedBaseAmount(quantity, quantityUnit, serving, servingCount)
        val resolved = resolveFoodItemNutrients(nutrients, consumedAmount)

        val hydration = food.beverageClass?.let { beverageClass ->
            val factors = supabase.postgrest.from("hydration_factor_models")
                .select(columns = Columns.list("id,model_version,beverage_class,retention_factor")) {
                    filter { eq("model_version", hydrationModelVersion) }
                }
                .decodeList<HydrationFactorModelRow>()
            computeEffectiveHydration(resolved.waterMl, beverageClass, factors, hydrationModelVersion)
        }

        val datasetVersion = supabase.postgrest.from("food_sources")
            .select(columns = Columns.list("version")) { filter { eq("id", food.foodSourceId) } }
            .decodeSingle<FoodSourceVersionRow>()
            .version

        return ResolvedMealItem(
            nutrients = resolved,
            consumedAmount = consumedAmount,
            hydration = hydration,
            provenance = FoodProvenance(
                foodId = requireNotNull(food.id),
                foodSourceId = food.foodSourceId,
                sourceFoodId = food.sourceFoodId,
                datasetVersion = datasetVersion,
            ),
        )
    }
}

@Serializable
private data class FoodSourceVersionRow(val version: String? = null)
