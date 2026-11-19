package com.bioscan.fieldterminal.data

import com.bioscan.fieldterminal.data.model.MealItemRow
import com.bioscan.fieldterminal.data.model.MealRow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive

// Re-logging a recent meal at another portion (Logging Flows Redesign Plan: Food › CONFIRM). Every amount of the meal and of
// its items is multiplied by the portion: quantities, energy and nutrients, water, caffeine, alcohol and the per-item
// nutrient map. What is not an amount stays as it is: the food, the serving chosen, the units, the confidence, the
// source, the hydration model.

/** The portions offered when a meal is logged again. */
val MEAL_PORTIONS = listOf(0.5, 0.75, 1.0, 1.25, 1.5, 2.0)

private fun Double?.times(f: Double): Double? = this?.let { it * f }

fun MealRow.scaled(f: Double): MealRow = if (f == 1.0) this else copy(
    calories = calories.times(f), proteinG = proteinG.times(f), fatG = fatG.times(f), carbsG = carbsG.times(f),
    fiberG = fiberG.times(f), sugarG = sugarG.times(f), sodiumMg = sodiumMg.times(f),
)

fun MealItemRow.scaled(f: Double): MealItemRow = if (f == 1.0) this else copy(
    quantity = quantity.times(f), quantityLow = quantityLow.times(f), quantityHigh = quantityHigh.times(f),
    servingCount = servingCount.times(f),
    calories = calories.times(f), proteinG = proteinG.times(f), fatG = fatG.times(f), carbsG = carbsG.times(f),
    fiberG = fiberG.times(f), sugarG = sugarG.times(f), sodiumMg = sodiumMg.times(f),
    waterMl = waterMl.times(f), caffeineMg = caffeineMg.times(f), alcoholG = alcoholG.times(f),
    effectiveHydrationMl = effectiveHydrationMl.times(f),
    cronometerNutrients = cronometerNutrients?.let { m ->
        JsonObject(m.mapValues { (_, v) -> (v as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.let { JsonPrimitive(it * f) } ?: v })
    },
)

/** "½", "1¼", "2" for a portion. */
fun portionLabel(f: Double): String = when (f) {
    0.5 -> "½"; 0.75 -> "¾"; 1.0 -> "1"; 1.25 -> "1¼"; 1.5 -> "1½"; 2.0 -> "2"
    else -> "%.2f".format(f).trimEnd('0').trimEnd('.')
}
