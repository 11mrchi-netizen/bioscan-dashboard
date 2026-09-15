package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.HydrationDailyRow
import com.bioscan.fieldterminal.data.model.MealRow
import com.bioscan.fieldterminal.domain.DailyNutrition
import com.bioscan.fieldterminal.domain.aggregateMealsByDay
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order

data class NutritionOverview(
    val today: DailyNutrition?, // "today" = most recent day with any logged data, matching
                                 // index.html's own convention (nutrition.cal[length-1]),
                                 // not a strict calendar-date filter -- see ROADMAP.md P8 Step 6.
    val last7Days: List<DailyNutrition>,
    val todayHydrationMl: Int?,
    val lastHydrationLoggedDate: String?,
)

class NutritionRepository(private val supabase: SupabaseClient) {

    suspend fun loadOverview(): NutritionOverview {
        // 30 days of meals is enough to reliably cover the last 7 *days with
        // any entries* even across a real logging gap (this project's own
        // data has a documented 16-day gap once) -- narrower windows risked
        // an empty or misleadingly-short trend.
        val meals = supabase.postgrest.from("meals")
            .select(columns = Columns.list("logged_at,calories,protein_g,fat_g,carbs_g")) {
                order("logged_at", Order.DESCENDING)
                limit(200)
            }
            .decodeList<MealRow>()

        val dailyTotals = aggregateMealsByDay(meals)
        val last7 = dailyTotals.takeLast(7)

        val hydration = supabase.postgrest.from("hydration_daily")
            .select(columns = Columns.list("date,ml")) {
                order("date", Order.DESCENDING)
                limit(1)
            }
            .decodeList<HydrationDailyRow>()
            .firstOrNull()

        return NutritionOverview(
            today = dailyTotals.lastOrNull(),
            last7Days = last7,
            todayHydrationMl = hydration?.ml,
            lastHydrationLoggedDate = hydration?.date,
        )
    }
}
