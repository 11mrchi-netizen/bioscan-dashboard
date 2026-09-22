package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.data.model.FoodNutrientRow
import com.bioscan.fieldterminal.data.model.FoodServingRow
import com.bioscan.fieldterminal.data.model.HydrationFactorModelRow

// DAV-164. Pure, deterministic nutrition math -- no I/O, no Gemini. A
// repository fetches FoodRow/FoodNutrientRow/FoodServingRow/
// HydrationFactorModelRow from Supabase and passes them in already resolved;
// everything here is a plain function of its arguments, so "known food +
// quantity produces deterministic totals" and "changing quantity recalculates
// totals without duplicates" hold by construction -- there's no mutable state
// to duplicate.

data class ResolvedNutrients(
    val calories: Double? = null,
    val proteinG: Double? = null,
    val fatG: Double? = null,
    val carbsG: Double? = null,
    val fiberG: Double? = null,
    val sugarG: Double? = null,
    val sodiumMg: Double? = null,
    val waterMl: Double? = null,
    val caffeineMg: Double? = null,
    val alcoholG: Double? = null,
)

// Canonical food_nutrients.nutrient names this resolver knows how to read.
// Anything else (a future micronutrient) round-trips through meal_items'
// jsonb-free flat columns as unsupported today -- extending this map is how
// "extensible micronutrient handling" grows without a schema change.
private const val NUTRIENT_CALORIES = "calories"
private const val NUTRIENT_PROTEIN = "protein"
private const val NUTRIENT_FAT = "fat"
private const val NUTRIENT_CARBS = "carbs"
private const val NUTRIENT_FIBER = "fiber"
private const val NUTRIENT_SUGAR = "sugar"
private const val NUTRIENT_SODIUM = "sodium"
private const val NUTRIENT_WATER = "water"
private const val NUTRIENT_CAFFEINE = "caffeine"
private const val NUTRIENT_ALCOHOL = "alcohol"

// Converts what was actually consumed (either N servings of a specific
// FoodServingRow, or a direct quantity already in grams/ml) into the single
// gram-or-ml amount food_nutrients' basis_qty/basis_unit is scaled against.
// A serving's own grams/ml equivalence always wins when both a serving and a
// direct quantity are present -- quantity/quantityUnit is that serving's own
// display unit in that case (e.g. "2" servings of "1 cup cooked" = 240g),
// not a second independent measurement to reconcile.
fun resolveConsumedBaseAmount(
    quantity: Double,
    quantityUnit: String,
    serving: FoodServingRow?,
    servingCount: Double?,
): Double {
    if (serving != null && servingCount != null) {
        return servingCount * (serving.grams ?: serving.ml ?: 0.0)
    }
    return quantity
}

// food_nutrients stores each nutrient's amount per a *basis* (basis_qty
// basis_unit -- almost always "100 g", but not guaranteed, e.g. a dataset
// could record something per-serving already or per 1g). consumedAmount is
// already in the same unit family as basis_unit (resolveConsumedBaseAmount's
// job), so this is a straight ratio.
fun scaleNutrientToQuantity(nutrient: FoodNutrientRow, consumedAmount: Double): Double =
    nutrient.amount * (consumedAmount / nutrient.basisQty)

// Resolves one food's full nutrient row set against one consumed amount.
// Missing nutrients stay null (never fabricated as 0) -- a food with no
// recorded fiber value must not silently claim zero fiber.
fun resolveFoodItemNutrients(nutrients: List<FoodNutrientRow>, consumedAmount: Double): ResolvedNutrients {
    val byName = nutrients.associateBy { it.nutrient }
    fun scaled(name: String): Double? = byName[name]?.let { scaleNutrientToQuantity(it, consumedAmount) }

    return ResolvedNutrients(
        calories = scaled(NUTRIENT_CALORIES),
        proteinG = scaled(NUTRIENT_PROTEIN),
        fatG = scaled(NUTRIENT_FAT),
        carbsG = scaled(NUTRIENT_CARBS),
        fiberG = scaled(NUTRIENT_FIBER),
        sugarG = scaled(NUTRIENT_SUGAR),
        sodiumMg = scaled(NUTRIENT_SODIUM),
        waterMl = scaled(NUTRIENT_WATER),
        caffeineMg = scaled(NUTRIENT_CAFFEINE),
        alcoholG = scaled(NUTRIENT_ALCOHOL),
    )
}

// Sums a meal's resolved items into meal totals. Null-safe: a meal where
// every item is missing fiber has a null fiber total, not a false zero: the
// aggregate can only claim a real number once at least one item contributes
// one, per DAV-161/181's "unknown stays unknown" rule.
fun aggregateMealItemNutrients(items: List<ResolvedNutrients>): ResolvedNutrients {
    fun sum(select: (ResolvedNutrients) -> Double?): Double? =
        items.mapNotNull(select).takeIf { it.isNotEmpty() }?.sum()

    return ResolvedNutrients(
        calories = sum { it.calories },
        proteinG = sum { it.proteinG },
        fatG = sum { it.fatG },
        carbsG = sum { it.carbsG },
        fiberG = sum { it.fiberG },
        sugarG = sum { it.sugarG },
        sodiumMg = sum { it.sodiumMg },
        waterMl = sum { it.waterMl },
        caffeineMg = sum { it.caffeineMg },
        alcoholG = sum { it.alcoholG },
    )
}

data class EffectiveHydration(val effectiveHydrationMl: Double, val modelVersion: String)

// DAV-181's retention-factor model applied to a resolved water_ml. Returns
// null when either input is missing -- effective hydration is never
// estimated from an assumed beverage class or an assumed water content.
fun computeEffectiveHydration(
    waterMl: Double?,
    beverageClass: String?,
    factors: List<HydrationFactorModelRow>,
    modelVersion: String,
): EffectiveHydration? {
    if (waterMl == null || beverageClass == null) return null
    val factor = factors.firstOrNull { it.modelVersion == modelVersion && it.beverageClass == beverageClass } ?: return null
    return EffectiveHydration(effectiveHydrationMl = waterMl * factor.retentionFactor, modelVersion = modelVersion)
}
