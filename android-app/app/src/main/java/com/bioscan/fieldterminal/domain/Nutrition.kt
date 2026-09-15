package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.data.model.MealRow
import java.time.LocalDate
import java.time.temporal.ChronoUnit

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

data class NutritionTotals(
    val calories: Double,
    val proteinG: Double,
    val carbsG: Double,
    val fatG: Double,
    val dayCount: Int,
)

// Same "< days" windowing convention as Training.kt's sumDistanceKmSince --
// days=1 matches only today, days=7 matches today and the 6 days before it.
// Kept consistent across both running-totals widgets rather than each
// screen inventing its own edge behavior for "how many days is 7D."
fun sumNutritionSince(daily: List<DailyNutrition>, today: LocalDate, days: Long): NutritionTotals {
    val matching = daily.filter { ChronoUnit.DAYS.between(LocalDate.parse(it.date), today) < days }
    return NutritionTotals(
        calories = matching.sumOf { it.calories },
        proteinG = matching.sumOf { it.proteinG },
        carbsG = matching.sumOf { it.carbsG },
        fatG = matching.sumOf { it.fatG },
        dayCount = matching.size,
    )
}
