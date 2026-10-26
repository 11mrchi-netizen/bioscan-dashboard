package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.data.model.MealRow
import java.time.LocalDate

// Redesign (spec §5): the numbers behind the Nutrition page's Macros / kg and Per-meal protein cards.

enum class MacroMove { Growing, Shrinking, Steady }

/** Whether a series is growing or shrinking: the mean of its second half against its first, beyond [tolerance] (a share of the first). */
fun macroMove(values: List<Double>, tolerance: Double = 0.05): MacroMove? {
    if (values.size < 4) return null
    val half = values.size / 2
    val before = values.take(half).average()
    val after = values.takeLast(half).average()
    if (before <= 0.0) return null
    val change = (after - before) / before
    return when {
        change > tolerance -> MacroMove.Growing
        change < -tolerance -> MacroMove.Shrinking
        else -> MacroMove.Steady
    }
}

/** The weight in force on [date]: the latest weigh-in on or before it, else the first one after (so early days are not dropped). */
fun weightOn(date: LocalDate, weights: List<Pair<LocalDate, Double>>): Double? {
    val sorted = weights.sortedBy { it.first }
    return sorted.lastOrNull { !it.first.isAfter(date) }?.second ?: sorted.firstOrNull()?.second
}

/** Per kilogram of body weight, for each logged day; days with no weight on record are left out. */
fun perKg(days: List<DailyNutrition>, weights: List<Pair<LocalDate, Double>>, pick: (DailyNutrition) -> Double): List<Pair<LocalDate, Double>> =
    days.mapNotNull { d ->
        val date = LocalDate.parse(d.date)
        weightOn(date, weights)?.takeIf { it > 0 }?.let { date to pick(d) / it }
    }

/** Protein of each meal, in the order eaten, for each day. A meal with no protein recorded counts as 0 g (it is a logged meal). */
fun proteinByMeal(meals: List<MealRow>): Map<LocalDate, List<Double>> =
    meals.groupBy { LocalDate.parse(it.loggedAt.take(10)) }
        .mapValues { (_, day) -> day.sortedBy { it.loggedAt }.map { it.proteinG ?: 0.0 } }
