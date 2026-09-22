package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.MealItemRow
import com.bioscan.fieldterminal.data.model.NewMealRow
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// DAV-168. Turns a review sheet's confirmed line items into a real meal +
// meal_items, via DAV-164's resolver -- this file never computes a nutrient
// value itself. Also owns "repeat a recent meal" (DAV-168's recurring-meal
// requirement), which reuses existing food_id references rather than
// re-resolving or re-creating anything.

enum class MealItemSource(val value: String) {
    Manual("manual"),
    AiText("ai_text"),
    AiImage("ai_image"),
    Barcode("barcode"),
}

data class ConfirmedMealItem(
    val description: String,
    val foodId: Long,
    val quantity: Double,
    val quantityUnit: String,
    val servingId: Long? = null,
    val servingCount: Double? = null,
    val quantityLow: Double? = null,
    val quantityHigh: Double? = null,
    val preparation: String? = null,
    val source: MealItemSource,
    val isEstimated: Boolean,
    val confidence: Double? = null,
    val aiEstimateId: Long? = null,
)

@Serializable
private data class MealIdRow(val id: Long)

@Serializable
private data class AiEstimateLinkUpdate(
    @SerialName("meal_id") val mealId: Long,
    @SerialName("meal_item_id") val mealItemId: Long,
    val accepted: Boolean,
)

class NutritionMealSaveRepository(
    private val supabase: SupabaseClient,
    private val resolver: NutritionResolverRepository = NutritionResolverRepository(supabase),
) {

    // Resolves every item first (DAV-164), then writes the meal + its items
    // in one pass -- the meal's own flat totals are the resolved sum, so
    // doc 10's "meals stays authoritative until meal_items populated"
    // transition query reads correctly the moment this returns.
    suspend fun saveMeal(loggedAt: String, description: String, items: List<ConfirmedMealItem>): Long {
        val resolved = items.map { it to resolver.resolveMealItem(it.foodId, it.quantity, it.quantityUnit, it.servingId, it.servingCount) }

        val mealId = supabase.postgrest.from("meals")
            .insert(
                NewMealRow(
                    loggedAt = loggedAt,
                    description = description,
                    calories = resolved.sumNullable { it.second.nutrients.calories },
                    proteinG = resolved.sumNullable { it.second.nutrients.proteinG },
                    carbsG = resolved.sumNullable { it.second.nutrients.carbsG },
                    fatG = resolved.sumNullable { it.second.nutrients.fatG },
                    fiberG = resolved.sumNullable { it.second.nutrients.fiberG },
                    sugarG = resolved.sumNullable { it.second.nutrients.sugarG },
                    sodiumMg = resolved.sumNullable { it.second.nutrients.sodiumMg },
                ),
            ) { select(Columns.list("id")) }
            .decodeSingle<MealIdRow>()
            .id

        resolved.forEachIndexed { index, (item, result) ->
            val mealItemRow = supabase.postgrest.from("meal_items")
                .insert(
                    MealItemRow(
                        mealId = mealId,
                        foodId = item.foodId,
                        sortOrder = index,
                        description = item.description,
                        quantity = item.quantity,
                        quantityUnit = item.quantityUnit,
                        quantityLow = item.quantityLow,
                        quantityHigh = item.quantityHigh,
                        servingId = item.servingId,
                        servingCount = item.servingCount,
                        preparation = item.preparation,
                        calories = result.nutrients.calories,
                        proteinG = result.nutrients.proteinG,
                        fatG = result.nutrients.fatG,
                        carbsG = result.nutrients.carbsG,
                        fiberG = result.nutrients.fiberG,
                        sugarG = result.nutrients.sugarG,
                        sodiumMg = result.nutrients.sodiumMg,
                        isEstimated = item.isEstimated,
                        confidence = item.confidence,
                        source = item.source.value,
                        isBeverage = result.nutrients.waterMl != null && result.hydration != null,
                        waterMl = result.nutrients.waterMl,
                        caffeineMg = result.nutrients.caffeineMg,
                        alcoholG = result.nutrients.alcoholG,
                        effectiveHydrationMl = result.hydration?.effectiveHydrationMl,
                        hydrationModelVersion = result.hydration?.modelVersion,
                    ),
                ) { select(Columns.list("id")) }
                .decodeSingle<MealIdRow>()

            // Links the audit-trail row back to what was actually saved,
            // without ever touching ai_estimates.parsed_output -- the
            // difference between that original field and this meal_items
            // row IS the correction, per DAV-168's own "stored separately"
            // requirement. A raw mapOf(...) here fails at runtime
            // ("Serializer for class 'Any' is not found") since kotlinx.
            // serialization can't serialize a heterogeneously-typed Map
            // without a registered polymorphic serializer -- caught live,
            // fixed with a real @Serializable payload instead.
            if (item.aiEstimateId != null) {
                supabase.postgrest.from("ai_estimates")
                    .update(AiEstimateLinkUpdate(mealId = mealId, mealItemId = mealItemRow.id, accepted = true)) {
                        filter { eq("id", item.aiEstimateId) }
                    }
            }
        }

        return mealId
    }

    // Duplicates a previous meal's items under a new meal at newLoggedAt --
    // same food_id/quantity/servings, fresh row ids. No new foods rows, no
    // re-resolution: this is a straight copy of already-correct data.
    suspend fun cloneMeal(sourceMealId: Long, newLoggedAt: String): Long {
        // MealRow.loggedAt is non-nullable -- must be selected even though
        // newLoggedAt (the clone's own date) is what actually gets used.
        // Same class of bug as resolveMealItem's foods query: decodeSingle
        // fails on a required field missing from the select list, caught
        // live testing "repeat a recent meal."
        val sourceMeal = supabase.postgrest.from("meals")
            .select(
                columns = Columns.list("logged_at,description,calories,protein_g,carbs_g,fat_g,fiber_g,sugar_g,sodium_mg"),
            ) { filter { eq("id", sourceMealId) } }
            .decodeSingle<com.bioscan.fieldterminal.data.model.MealRow>()

        val sourceItems = supabase.postgrest.from("meal_items")
            .select { filter { eq("meal_id", sourceMealId) } }
            .decodeList<MealItemRow>()

        val newMealId = supabase.postgrest.from("meals")
            .insert(
                NewMealRow(
                    loggedAt = newLoggedAt,
                    description = sourceMeal.description ?: "",
                    calories = sourceMeal.calories,
                    proteinG = sourceMeal.proteinG,
                    carbsG = sourceMeal.carbsG,
                    fatG = sourceMeal.fatG,
                    fiberG = sourceMeal.fiberG,
                    sugarG = sourceMeal.sugarG,
                    sodiumMg = sourceMeal.sodiumMg,
                ),
            ) { select(Columns.list("id")) }
            .decodeSingle<MealIdRow>()
            .id

        if (sourceItems.isNotEmpty()) {
            supabase.postgrest.from("meal_items").insert(
                sourceItems.map { it.copy(id = null, mealId = newMealId) },
            )
        }

        return newMealId
    }
}

private inline fun <T> List<Pair<T, ResolvedMealItem>>.sumNullable(
    select: (Pair<T, ResolvedMealItem>) -> Double?,
): Double? = mapNotNull(select).takeIf { it.isNotEmpty() }?.sum()
