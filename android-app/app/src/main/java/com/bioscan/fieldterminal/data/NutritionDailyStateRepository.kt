package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.FoodRow
import com.bioscan.fieldterminal.data.model.HydrationDailyRow
import com.bioscan.fieldterminal.data.model.MealItemRow
import com.bioscan.fieldterminal.data.model.MealRow
import com.bioscan.fieldterminal.domain.analysis.NutritionDailyState
import com.bioscan.fieldterminal.domain.analysis.buildNutritionDailyState
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

// DAV-180. Fetches one calendar day's real meals/meal_items/hydration_daily
// rows and hands them to the pure builder -- this file owns I/O only, same
// split as NutritionResolverRepository/NutritionResolver.
class NutritionDailyStateRepository(private val supabase: SupabaseClient) {

    suspend fun loadDailyState(date: LocalDate, zone: ZoneId = ZoneId.systemDefault()): NutritionDailyState {
        val startIso = date.atStartOfDay().toString()
        val endIso = date.plusDays(1).atStartOfDay().toString()

        // Same gte/lt-on-a-naive-local-string bound as HealthConnectWriteBackRepository,
        // then a real per-row local-date check -- logged_at carries an offset,
        // so a lexicographic bound alone can't be trusted at a DST edge.
        val meals = supabase.postgrest.from("meals")
            .select(columns = Columns.list("id,logged_at")) {
                filter {
                    gte("logged_at", startIso)
                    lt("logged_at", endIso)
                }
            }
            .decodeList<MealRow>()
            .filter { OffsetDateTime.parse(it.loggedAt).toLocalDateTime().atZone(zone).toLocalDate() == date }

        val mealLoggedAtById = meals.mapNotNull { meal -> meal.id?.let { it to meal.loggedAt } }.toMap()

        val items = if (mealLoggedAtById.isNotEmpty()) {
            supabase.postgrest.from("meal_items")
                .select { filter { isIn("meal_id", mealLoggedAtById.keys.toList()) } }
                .decodeList<MealItemRow>()
        } else {
            emptyList()
        }

        val hydrationMl = supabase.postgrest.from("hydration_daily")
            .select(columns = Columns.list("date,ml")) { filter { eq("date", date.toString()) } }
            .decodeList<HydrationDailyRow>()
            .firstOrNull()?.ml

        val beverageFoodIds = items.filter { it.isBeverage }.mapNotNull { it.foodId }.distinct()
        val beverageClassByFoodId = if (beverageFoodIds.isNotEmpty()) {
            // food_source_id/name must stay in the select even though only
            // beverage_class is read here -- FoodRow decode throws on any of
            // its non-nullable fields missing from the column list, caught
            // live during DAV-168 (see docs/analysis-layer-2/16-....md).
            supabase.postgrest.from("foods")
                .select(columns = Columns.list("id,food_source_id,name,beverage_class")) {
                    filter { isIn("id", beverageFoodIds) }
                }
                .decodeList<FoodRow>()
                .mapNotNull { row -> row.id?.let { it to row.beverageClass } }
                .toMap()
        } else {
            emptyMap()
        }

        return buildNutritionDailyState(date, items, mealLoggedAtById, hydrationMl, beverageClassByFoodId)
    }
}
