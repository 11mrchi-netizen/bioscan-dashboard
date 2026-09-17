package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.HydrationDailyRow
import com.bioscan.fieldterminal.data.model.MealRow
import com.bioscan.fieldterminal.domain.DailyNutrition
import com.bioscan.fieldterminal.domain.aggregateMealsByDay
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import io.github.jan.supabase.postgrest.query.Order
import java.time.LocalDate

data class NutritionOverview(
    val today: DailyNutrition?, // the real calendar-today's totals, or null if
                                 // nothing's been logged yet today -- was
                                 // previously "most recent day with any logged
                                 // data" (index.html's nutrition.cal[length-1]
                                 // convention), which meant yesterday's full
                                 // totals kept showing as "today" until you
                                 // logged something new. Real on-device bug
                                 // report: calories/macros not clearing at the
                                 // new day.
    val last7Days: List<DailyNutrition>,
    val allDays: List<DailyNutrition>, // kept for the 1D/7D/30D/90D macro-totals widget
    val todayHydrationMl: Int?,
    val lastHydrationLoggedDate: String?,
)

class NutritionRepository(private val supabase: SupabaseClient) {

    suspend fun loadOverview(): NutritionOverview {
        // 500 is a generous row-count margin, not a real 90-day date filter
        // -- fine while this account's real meal volume is under 100 total,
        // same "bounded, not unbounded" tradeoff LogRepository already makes.
        val meals = supabase.postgrest.from("meals")
            .select(columns = Columns.list("logged_at,calories,protein_g,fat_g,carbs_g")) {
                order("logged_at", Order.DESCENDING)
                limit(500)
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

        val today = LocalDate.now().toString()
        return NutritionOverview(
            today = dailyTotals.lastOrNull { it.date == today },
            last7Days = last7,
            allDays = dailyTotals,
            todayHydrationMl = hydration?.takeIf { it.date == today }?.ml,
            lastHydrationLoggedDate = hydration?.date,
        )
    }
}
