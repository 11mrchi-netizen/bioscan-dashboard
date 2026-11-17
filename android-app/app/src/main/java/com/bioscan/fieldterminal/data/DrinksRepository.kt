package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.HydrationFactorModelRow
import com.bioscan.fieldterminal.data.model.MealItemRow
import com.bioscan.fieldterminal.data.model.NewMealRow
import com.bioscan.fieldterminal.domain.DrinkAmounts
import com.bioscan.fieldterminal.domain.DrinkMatch
import com.bioscan.fieldterminal.domain.LoggedDrink
import com.bioscan.fieldterminal.domain.WallClock
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.LocalDate
import java.time.ZoneId

// The drink log against the catalog: search (public.search_drinks), drinks by id (public.drink_rows), the person's
// logged drinks (beverage meal items), and saving one drink as a meal with its item.
class DrinksRepository(private val supabase: SupabaseClient) {

    /** Drinks matching [query] (a name in English or Chinese, a brand, a barcode); an empty query gives one per class. */
    suspend fun search(query: String, limit: Int = 12): List<DrinkMatch> = withContext(Dispatchers.IO) {
        supabase.postgrest.rpc("search_drinks", buildJsonObject { put("q", query); put("n", limit) }).decodeList<DrinkMatch>()
    }

    suspend fun byIds(ids: List<Long>): List<DrinkMatch> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) emptyList()
        else supabase.postgrest.rpc("drink_rows", buildJsonObject { put("ids", buildJsonArray { ids.forEach { add(JsonPrimitive(it)) } }) })
            .decodeList<DrinkMatch>()
    }

    /** The beverage items logged in the last [days] days, newest first. Logged times are wall-clock times. */
    suspend fun logged(days: Long = 400, zone: ZoneId = ZoneId.systemDefault()): List<LoggedDrink> = withContext(Dispatchers.IO) {
        val since = LocalDate.now(zone).minusDays(days).toString()
        supabase.postgrest.from("meal_items")
            .select(columns = Columns.raw("food_id,description,quantity,quantity_unit,beverage_class,caffeine_mg,alcohol_g,effective_hydration_ml,meals!inner(logged_at)")) {
                filter {
                    eq("is_beverage", true)
                    gte("meals.logged_at", since)
                }
                order("created_at", Order.DESCENDING)
                limit(3000)
            }
            .decodeList<LoggedDrinkRow>()
            .mapNotNull { r ->
                val at = WallClock.instant(r.meal.loggedAt, zone) ?: return@mapNotNull null
                LoggedDrink(
                    at = at,
                    foodId = r.foodId,
                    description = r.description ?: "Drink",
                    beverageClass = r.beverageClass,
                    ml = r.quantity?.takeIf { r.quantityUnit == null || r.quantityUnit == "ml" || r.quantityUnit == "g" },
                    caffeineMg = r.caffeineMg,
                    alcoholG = r.alcoholG,
                    hydrationMl = r.effectiveHydrationMl,
                )
            }
    }

    /**
     * One drink as a meal of one item. [drink] null = only the class is known (nothing to look up). [estimated]: a
     * class default or an estimated caffeine, not the product's own numbers.
     */
    suspend fun save(
        loggedAt: String,
        drink: DrinkMatch?,
        beverageClass: String,
        amounts: DrinkAmounts,
        description: String,
        estimated: Boolean,
        source: String,
    ): Long = withContext(Dispatchers.IO) {
        val retention = catching {
            supabase.postgrest.from("hydration_factor_models")
                .select(columns = Columns.list("model_version,beverage_class,retention_factor")) { filter { eq("model_version", "v1") } }
                .decodeList<HydrationFactorModelRow>()
                .firstOrNull { it.beverageClass == beverageClass }?.retentionFactor
        }.getOrNull() ?: 1.0
        val mealId = supabase.postgrest.from("meals")
            .insert(
                NewMealRow(
                    loggedAt = loggedAt,
                    description = description,
                    calories = amounts.kcal,
                    proteinG = amounts.proteinG,
                    carbsG = amounts.carbsG,
                    fatG = amounts.fatG,
                    sugarG = amounts.sugarG,
                    sodiumMg = amounts.sodiumMg,
                ),
            ) { select(Columns.list("id")) }
            .decodeSingle<DrinkMealId>()
            .id
        try {
            supabase.postgrest.from("meal_items").insert(
                MealItemRow(
                    mealId = mealId,
                    foodId = drink?.id,
                    sortOrder = 0,
                    description = description,
                    quantity = amounts.ml,
                    quantityUnit = "ml",
                    calories = amounts.kcal,
                    proteinG = amounts.proteinG,
                    fatG = amounts.fatG,
                    carbsG = amounts.carbsG,
                    sugarG = amounts.sugarG,
                    sodiumMg = amounts.sodiumMg,
                    isEstimated = estimated,
                    source = source,
                    isBeverage = true,
                    waterMl = amounts.waterMl,
                    caffeineMg = amounts.caffeineMg,
                    alcoholG = amounts.alcoholG,
                    effectiveHydrationMl = amounts.ml * retention,
                    hydrationModelVersion = "v1",
                    beverageClass = beverageClass,
                ),
            )
        } catch (e: Exception) {
            supabase.postgrest.from("meals").delete { filter { eq("id", mealId) } }
            throw e
        }
        mealId
    }
}

@Serializable
private data class LoggedDrinkRow(
    @SerialName("food_id") val foodId: Long? = null,
    val description: String? = null,
    val quantity: Double? = null,
    @SerialName("quantity_unit") val quantityUnit: String? = null,
    @SerialName("beverage_class") val beverageClass: String? = null,
    @SerialName("caffeine_mg") val caffeineMg: Double? = null,
    @SerialName("alcohol_g") val alcoholG: Double? = null,
    @SerialName("effective_hydration_ml") val effectiveHydrationMl: Double? = null,
    @SerialName("meals") val meal: LoggedAtRow,
)

@Serializable
private data class LoggedAtRow(@SerialName("logged_at") val loggedAt: String)

@Serializable
private data class DrinkMealId(val id: Long)
