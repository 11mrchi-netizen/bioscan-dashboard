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

/** The names a meal can carry on the Nutrition page, in the order the day runs. */
val MEAL_NAMES = listOf("Breakfast", "Pre-session", "Lunch", "Snack", "Recovery", "Dinner")

private val MAIN_BY_CLOCK = listOf(4 to "Breakfast", 11 to "Lunch", 16 to "Dinner")

/**
 * The name of each meal of one day (same order as [day]): the label it was logged under (meal_slot), else the main meal its
 * clock time falls in (breakfast from 04:00, lunch from 11:00, dinner from 16:00), and a Snack when that main meal is
 * already taken that day. Logged times are wall clock, so their digits are the local hour.
 */
fun mealNames(day: List<MealRow>): List<String> {
    fun label(slot: String?) = slot?.trim()?.lowercase()?.replace('-', '_')?.let {
        when (it) { "breakfast" -> "Breakfast"; "lunch" -> "Lunch"; "dinner" -> "Dinner"; "snack" -> "Snack"
            "pre_session" -> "Pre-session"; "recovery" -> "Recovery"; else -> null }
    }
    val taken = day.mapNotNull { label(it.mealSlot) }.toMutableSet()
    return day.map { m ->
        label(m.mealSlot) ?: run {
            val hour = m.loggedAt.drop(11).take(2).toIntOrNull() ?: 12
            val main = MAIN_BY_CLOCK.lastOrNull { hour >= it.first }?.second ?: "Dinner"
            if (taken.add(main)) main else "Snack"
        }
    }
}

/** Protein of each meal for each day, one value per [MEAL_NAMES] entry (meals sharing a name add up). A logged meal with no protein counts as 0 g. */
fun proteinByMeal(meals: List<MealRow>): Map<LocalDate, List<Double>> =
    meals.groupBy { LocalDate.parse(it.loggedAt.take(10)) }
        .mapValues { (_, day) ->
            val sorted = day.sortedBy { it.loggedAt }
            val totals = DoubleArray(MEAL_NAMES.size)
            mealNames(sorted).forEachIndexed { i, n -> totals[MEAL_NAMES.indexOf(n)] += sorted[i].proteinG ?: 0.0 }
            totals.toList()
        }
