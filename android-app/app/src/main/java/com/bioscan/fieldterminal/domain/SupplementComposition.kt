package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.data.model.SupplementProductIngredientWithKey

// Supplement Intelligence Phase 1 (DAV-328 audit). Converts one product
// ingredient's known composition into a nutrient_intake-ready (nutrient key,
// amount) pair -- the real replacement for AddEntryRepository's old
// name-regex guess, used only where a supplement is linked to a real
// supplement_products row.

// g/mg/mcg only -- fixed SI mass factors, never a substance-specific
// conversion (e.g. IU, which differs per vitamin) -- returns null rather
// than fabricate one.
private val MASS_FACTORS_TO_MG = mapOf("g" to 1000.0, "mg" to 1.0, "mcg" to 0.001)

fun convertMass(amount: Double, fromUnit: String, toUnit: String): Double? {
    val fromFactor = MASS_FACTORS_TO_MG[fromUnit.lowercase()] ?: return null
    val toFactor = MASS_FACTORS_TO_MG[toUnit.lowercase()] ?: return null
    return amount * fromFactor / toFactor
}

data class NutrientContribution(val nutrientKey: String, val amount: Double, val unit: String)

// Null when the ingredient has no nutrient_key yet (e.g. an herbal/other
// ingredient with no resolvable nutrient, or a not-yet-reviewed mineral
// salt) -- never guessed from the ingredient's name. Prefers the real
// elemental amount+unit when known; falls back to the compound amount+unit
// when it isn't (e.g. a mineral complex whose real label hasn't been checked
// yet) -- the same number the old regex fallback already produced, just now
// from a structured, flaggable source instead of a silent guess.
fun nutrientContribution(ingredient: SupplementProductIngredientWithKey, servingCount: Double): NutrientContribution? {
    val nutrientKey = ingredient.nutrientKey ?: return null
    val amount = ingredient.elementalAmount ?: ingredient.compoundAmount
    val unit = ingredient.elementalUnit ?: ingredient.compoundUnit
    return NutrientContribution(nutrientKey, amount * servingCount, unit)
}
