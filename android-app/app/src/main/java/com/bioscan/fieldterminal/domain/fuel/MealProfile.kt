package com.bioscan.fieldterminal.domain.fuel

import kotlin.math.roundToInt

// The meal profile (docs/nutrition/meal-profile.md): separate indexes for one meal, read from the `meal_profiles` view,
// which computes them on the server from the meal and its ingredients. The owner asked for the indexes to stay separate:
// there is no combined meal score here, on purpose. Each line says its value, the band in words and how it was got;
// estimated indexes say "est.". A missing input gives no line rather than a guess.

data class MealProfile(
    val mealId: Long,
    val calories: Double?,
    val proteinG: Double?,
    val foodGrams: Double?,
    val energyDensityKcalG: Double?,
    val proteinPer100Kcal: Double?,
    val carbsPer100Kcal: Double?,
    val fiberPer1000Kcal: Double?,
    val glycemicLoadEst: Double?,
    val fullnessFactorEst: Double?,
    val ultraProcessedPct: Double?,
    val foodGroups: List<String>,
    val tags: List<String>,
    val macroMismatch: Boolean,
)

enum class MealIndex { ENERGY_DENSITY, PROTEIN, CARBS, FIBER, GLYCEMIC_LOAD, FULLNESS, PROCESSED }

/** One line of the profile: what it is, its value as written, the band in words, and a short why. */
data class MealIndexLine(val index: MealIndex, val label: String, val value: String, val band: String, val why: String)

/** The protein one meal should carry to count as a full dose: 0.3 g per kg (Moore 2015), 25 g without a known weight. */
fun proteinDoseG(weightKg: Double?): Double = if (weightKg != null && weightKg in 30.0..250.0) 0.3 * weightKg else 25.0

private fun Double.fmt(decimals: Int): String = if (decimals == 0) roundToInt().toString() else "%.${decimals}f".format(this)

/** The lines to show, in a fixed order; an index without its inputs is left out. */
fun mealIndexLines(p: MealProfile, weightKg: Double?): List<MealIndexLine> {
    val out = mutableListOf<MealIndexLine>()
    p.energyDensityKcalG?.let { ed ->
        // Rolls' energy-density categories: under 1.5 kcal/g low, 1.5-4 medium, over 4 high.
        val band = when { ed < 1.5 -> "light"; ed <= 4.0 -> "medium"; else -> "dense" }
        out += MealIndexLine(MealIndex.ENERGY_DENSITY, "Energy density", "${ed.fmt(1)} kcal/g", band,
            "${p.calories?.fmt(0) ?: "?"} kcal in ${p.foodGrams?.fmt(0) ?: "?"} g of food")
    }
    if (p.proteinG != null && (p.calories ?: 0.0) >= 50.0) {
        val dose = proteinDoseG(weightKg)
        val band = when { p.proteinG >= dose -> "full dose"; p.proteinG >= dose / 2 -> "partial dose"; else -> "little" }
        val density = p.proteinPer100Kcal?.let { " · ${it.fmt(1)} g per 100 kcal" } ?: ""
        out += MealIndexLine(MealIndex.PROTEIN, "Protein", "${p.proteinG.fmt(0)} g$density", band,
            "a full dose is ${dose.fmt(0)} g" + if (weightKg != null) " (0.3 g/kg)" else "")
    }
    p.carbsPer100Kcal?.let { c ->
        // Share of energy from carbohydrate: under 26% low (the usual low-carb line), over 55% high.
        val pct = 4 * c
        val band = when { pct < 26 -> "low carb"; pct <= 55 -> "moderate carb"; else -> "high carb" }
        out += MealIndexLine(MealIndex.CARBS, "Carb density", "${c.fmt(1)} g per 100 kcal", band, "${pct.fmt(0)}% of the energy")
    }
    p.fiberPer1000Kcal?.let { f ->
        // 14 g per 1,000 kcal is the usual adequate intake.
        val band = when { f >= 14 -> "high fibre"; f >= 7 -> "some fibre"; else -> "low fibre" }
        out += MealIndexLine(MealIndex.FIBER, "Fibre", "${f.fmt(1)} g per 1,000 kcal", band, "14 g per 1,000 kcal is a good day")
    }
    p.glycemicLoadEst?.let { gl ->
        val band = when { gl <= 10 -> "low"; gl < 20 -> "medium"; else -> "high" }
        out += MealIndexLine(MealIndex.GLYCEMIC_LOAD, "Glycemic load", "${gl.fmt(0)} est.", band, "from typical GI of its foods")
    }
    p.fullnessFactorEst?.let { ff ->
        // Fullness Factor runs 0.5-5; around 2 is an average food.
        val band = when { ff < 2 -> "less filling"; ff <= 3 -> "average"; else -> "filling" }
        out += MealIndexLine(MealIndex.FULLNESS, "Fullness", "${ff.fmt(1)} est.", band, "per 100 g, scale 0.5-5")
    }
    p.ultraProcessedPct?.takeIf { it > 0 }?.let { u ->
        val band = when { u >= 66 -> "mostly"; u >= 33 -> "partly"; else -> "a little" }
        out += MealIndexLine(MealIndex.PROCESSED, "Ultra-processed", "${u.fmt(0)}% of kcal", band, "sweets, snacks, soft drinks, processed meat")
    }
    return out
}

private val GROUP_LABELS = mapOf(
    "protein_powder" to "Protein powder", "sweets" to "Sweets", "snacks_salty" to "Salty snacks",
    "sugary_drinks" to "Sugary drinks", "alcohol" to "Alcohol", "coffee_tea" to "Coffee or tea", "plant_milk" to "Plant milk",
    "water_electrolytes" to "Water or electrolytes", "processed_meat" to "Processed meat", "whole_grains" to "Whole grains",
    "refined_grains" to "Refined grains", "starchy_veg" to "Starchy veg", "poultry" to "Poultry", "meat" to "Meat",
    "fish_seafood" to "Fish and seafood", "eggs" to "Eggs", "cheese" to "Cheese", "dairy" to "Dairy", "legumes" to "Legumes",
    "nuts_seeds" to "Nuts and seeds", "fruit" to "Fruit", "vegetables" to "Vegetables", "sugars" to "Sugar and honey",
    "fats_oils" to "Fats and oils", "sauces" to "Sauces",
)

private val TAG_LABELS = mapOf(
    "gluten" to "Gluten", "lactose" to "Lactose", "dairy" to "Dairy", "high_fodmap" to "High FODMAP", "fried" to "Fried",
    "spicy" to "Spicy", "sweetener" to "Sweetener", "caffeine" to "Caffeine", "alcohol" to "Alcohol",
    "ultra_processed" to "Ultra-processed", "high_fat" to "High fat",
)

/** The food groups in words, unrecognised ingredients last as "Other". */
fun mealGroupLabels(p: MealProfile): List<String> =
    p.foodGroups.filter { it != "other" }.mapNotNull { GROUP_LABELS[it] }.sorted() + if ("other" in p.foodGroups) listOf("Other") else emptyList()

/** The gut-relevant tags in words; "Dairy" is left out when "Lactose" says more, and processing is its own index. */
fun mealTagLabels(p: MealProfile): List<String> =
    p.tags.filter { it != "ultra_processed" && !(it == "dairy" && "lactose" in p.tags) }.mapNotNull { TAG_LABELS[it] }.sorted()
