package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.domain.fuel.CatalogueFood
import com.bioscan.fieldterminal.domain.fuel.FoodOption
import com.bioscan.fieldterminal.domain.fuel.LoggedFood
import com.bioscan.fieldterminal.domain.fuel.MealSlotKind
import com.bioscan.fieldterminal.domain.fuel.MealTimes
import com.bioscan.fieldterminal.domain.fuel.STAPLE_FOODS
import com.bioscan.fieldterminal.domain.fuel.ownFoods
import com.bioscan.fieldterminal.domain.fuel.slotForTime
import com.bioscan.fieldterminal.domain.fuel.staplesWithUses
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.OffsetDateTime
import java.time.ZoneId

// The foods meal suggestions draw on (fuel planning phase 10): the person's logged catalogue foods (meal_items with a
// food_id, values from food_nutrients) and the built-in staples, ranked by how often the person's meal descriptions
// mention them. Read-only; no model is involved (an item an AI estimate produced counts only through its catalogue food
// or its description, never through the estimate's numbers).

@Serializable
private data class HabitItemRow(
    val description: String? = null,
    @SerialName("food_id") val foodId: Long? = null,
    val quantity: Double? = null,
    @SerialName("quantity_unit") val quantityUnit: String? = null,
    @SerialName("is_beverage") val isBeverage: Boolean? = null,
)

@Serializable
private data class HabitMealRow(
    @SerialName("logged_at") val loggedAt: String,
    val description: String? = null,
    @SerialName("meal_items") val items: List<HabitItemRow> = emptyList(),
)

@Serializable
private data class CatalogueRow(val id: Long, val name: String)

@Serializable
private data class NutrientRow(
    @SerialName("food_id") val foodId: Long,
    val nutrient: String,
    val amount: Double? = null,
    @SerialName("basis_qty") val basisQty: Double? = null,
    @SerialName("basis_unit") val basisUnit: String? = null,
)

private const val HABIT_DAYS = 120L

class FoodHabitsRepository(private val supabase: SupabaseClient) {

    /** The foods to suggest from, the person's first. Falls back to the plain staples when nothing can be read. */
    suspend fun foods(times: MealTimes, zone: ZoneId = ZoneId.systemDefault()): List<FoodOption> =
        ReadCache.get("fuel.food_habits.$times", ttlMs = 60 * 60_000L, tables = setOf("meals", "meal_items", "foods", "food_nutrients")) {
            runCatching { load(times, zone) }.getOrDefault(STAPLE_FOODS)
        }

    private suspend fun load(times: MealTimes, zone: ZoneId): List<FoodOption> {
        val since = OffsetDateTime.now(zone).minusDays(HABIT_DAYS)
        val meals = supabase.postgrest.from("meals")
            .select(Columns.raw("logged_at,description,meal_items(description,food_id,quantity,quantity_unit,is_beverage)")) {
                filter { gte("logged_at", since.toInstant().toString()) }
                limit(1000)
            }
            .decodeList<HabitMealRow>()
        val descriptions = mutableListOf<Pair<MealSlotKind, String>>()
        val logged = mutableListOf<LoggedFood>()
        for (m in meals) {
            val at = runCatching { OffsetDateTime.parse(m.loggedAt).atZoneSameInstant(zone) }.getOrNull() ?: continue
            val slot = slotForTime(at.toLocalTime(), times)
            // One text per meal, so a staple counts once per meal however many items name it.
            descriptions += slot to (listOfNotNull(m.description) + m.items.mapNotNull { it.description }).joinToString(" · ")
            m.items.forEach { i ->
                val id = i.foodId ?: return@forEach
                val q = i.quantity ?: return@forEach
                if (i.isBeverage == true || (i.quantityUnit ?: "g") !in setOf("g", "ml")) return@forEach
                logged += LoggedFood(id, slot, q)
            }
        }
        val staples = staplesWithUses(descriptions)
        val ids = logged.groupBy { it.foodId }.filter { it.value.size >= com.bioscan.fieldterminal.domain.fuel.OWN_FOOD_MIN_USES }.keys
        if (ids.isEmpty()) return staples
        val names = supabase.postgrest.from("foods").select(Columns.list("id,name")) { filter { isIn("id", ids.toList()) } }.decodeList<CatalogueRow>()
        val nutrients = supabase.postgrest.from("food_nutrients")
            .select(Columns.list("food_id,nutrient,amount,basis_qty,basis_unit")) {
                filter {
                    isIn("food_id", ids.toList())
                    isIn("nutrient", listOf("calories", "carbs", "protein", "fat", "fiber"))
                }
            }
            .decodeList<NutrientRow>()
            .groupBy { it.foodId }
        val catalogue = names.associate { f ->
            val n = nutrients[f.id].orEmpty()
            fun per100(name: String) = n.firstOrNull { it.nutrient == name }?.let { r -> r.amount?.let { it * 100 / (r.basisQty ?: 100.0) } }
            val unit = n.firstOrNull()?.basisUnit?.takeIf { it == "ml" } ?: "g"
            f.id to CatalogueFood(f.id, f.name, per100("carbs"), per100("protein"), per100("fat"), per100("fiber"), per100("calories"), unit)
        }
        return ownFoods(logged, catalogue) + staples
    }
}
