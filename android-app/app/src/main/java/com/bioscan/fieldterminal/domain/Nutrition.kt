package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.data.model.MealRow

data class DailyNutrition(
    val date: String, // "YYYY-MM-DD"
    val calories: Double,
    val proteinG: Double,
    val fatG: Double,
    val carbsG: Double,
    val mealCount: Int,
)

// Ported 1:1 from index.html's meals-\>daily-aggregation (see
// fetchDashboardData()'s "nutritionByDate" block) -- same UTC-calendar-date
// grouping (the first 10 characters of the timestamptz string, exactly
// matching the web version's `m.logged_at.slice(0,10)`, not a device-local-
// timezone conversion), same sum-per-day, sorted ascending by date.
fun aggregateMealsByDay(meals: List<MealRow>): List<DailyNutrition> {
    val byDate = linkedMapOf<String, MutableList<MealRow>>()
    for (meal in meals) {
        val date = meal.loggedAt.take(10)
        byDate.getOrPut(date) { mutableListOf() }.add(meal)
    }
    return byDate.entries
        .map { (date, rows) ->
            DailyNutrition(
                date = date,
                calories = rows.sumOf { it.calories ?: 0.0 },
                proteinG = rows.sumOf { it.proteinG ?: 0.0 },
                fatG = rows.sumOf { it.fatG ?: 0.0 },
                carbsG = rows.sumOf { it.carbsG ?: 0.0 },
                mealCount = rows.size,
            )
        }
        .sortedBy { it.date }
}
