package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.data.model.FoodNutrientRow
import com.bioscan.fieldterminal.data.model.FoodServingRow
import com.bioscan.fieldterminal.data.model.HydrationFactorModelRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NutritionResolverTest {

    private fun nutrient(name: String, amount: Double, basisQty: Double = 100.0, unit: String = "g") =
        FoodNutrientRow(foodId = 1, nutrient = name, amount = amount, unit = unit, basisQty = basisQty)

    // Hand-verified: USDA raw chicken breast is ~165 kcal / 31g protein / 3.6g fat per 100g.
    @Test
    fun testScaleNutrientToQuantity_150gChickenBreast() {
        val calories = nutrient(name = "calories", amount = 165.0)
        assertEquals(247.5, scaleNutrientToQuantity(calories, consumedAmount = 150.0), 0.001)
    }

    @Test
    fun testScaleNutrientToQuantity_nonStandardBasis() {
        // A dataset that records a value per 1g instead of per 100g.
        val sodium = nutrient(name = "sodium", amount = 0.5, basisQty = 1.0)
        assertEquals(75.0, scaleNutrientToQuantity(sodium, consumedAmount = 150.0), 0.001)
    }

    @Test
    fun testResolveConsumedBaseAmount_directQuantity() {
        assertEquals(150.0, resolveConsumedBaseAmount(150.0, "g", serving = null, servingCount = null), 0.001)
    }

    @Test
    fun testResolveConsumedBaseAmount_servingCount() {
        // "1 cup cooked rice" = 158g, user logged 2 cups.
        val serving = FoodServingRow(foodId = 1, servingName = "1 cup cooked", grams = 158.0)
        assertEquals(316.0, resolveConsumedBaseAmount(2.0, "cup", serving, servingCount = 2.0), 0.001)
    }

    @Test
    fun testResolveConsumedBaseAmount_servingByVolume() {
        val serving = FoodServingRow(foodId = 1, servingName = "1 can", ml = 355.0)
        assertEquals(355.0, resolveConsumedBaseAmount(1.0, "can", serving, servingCount = 1.0), 0.001)
    }

    @Test
    fun testResolveFoodItemNutrients_150gChickenBreast() {
        val nutrients = listOf(
            nutrient(name = "calories", amount = 165.0),
            nutrient(name = "protein", amount = 31.0),
            nutrient(name = "fat", amount = 3.6),
        )
        val resolved = resolveFoodItemNutrients(nutrients, consumedAmount = 150.0)
        assertEquals(247.5, resolved.calories!!, 0.001)
        assertEquals(46.5, resolved.proteinG!!, 0.001)
        assertEquals(5.4, resolved.fatG!!, 0.001)
        // Fiber was never recorded for this food -- stays null, not a fabricated 0.
        assertNull(resolved.fiberG)
    }

    @Test
    fun testAggregateMealItemNutrients_compositeMeal() {
        val chicken = ResolvedNutrients(calories = 247.5, proteinG = 46.5, fiberG = null)
        val rice = ResolvedNutrients(calories = 205.0, proteinG = 4.3, fiberG = 0.6)
        val totals = aggregateMealItemNutrients(listOf(chicken, rice))
        assertEquals(452.5, totals.calories!!, 0.001)
        assertEquals(50.8, totals.proteinG!!, 0.001)
        // One item had a real fiber value -- the aggregate isn't null just
        // because the other item's fiber was unknown.
        assertEquals(0.6, totals.fiberG!!, 0.001)
    }

    @Test
    fun testAggregateMealItemNutrients_allMissingStaysNull() {
        val totals = aggregateMealItemNutrients(listOf(ResolvedNutrients(calories = 100.0), ResolvedNutrients(calories = 200.0)))
        assertNull(totals.sodiumMg)
    }

    @Test
    fun testAggregateMealItemNutrients_recalculatesWithoutDuplicating() {
        // "Changing quantity recalculates totals without duplicates" -- since
        // aggregation is a pure fold over the current item list, re-running it
        // with an updated item list produces a fresh total, never an
        // accumulation on top of the previous call's result.
        val original = aggregateMealItemNutrients(listOf(ResolvedNutrients(calories = 100.0)))
        val afterQuantityEdit = aggregateMealItemNutrients(listOf(ResolvedNutrients(calories = 150.0)))
        assertEquals(100.0, original.calories!!, 0.001)
        assertEquals(150.0, afterQuantityEdit.calories!!, 0.001)
    }

    @Test
    fun testComputeEffectiveHydration_knownFactor() {
        val factors = listOf(HydrationFactorModelRow(modelVersion = "v1", beverageClass = "coffee", retentionFactor = 0.98))
        val result = computeEffectiveHydration(waterMl = 240.0, beverageClass = "coffee", factors = factors, modelVersion = "v1")
        assertEquals(235.2, result!!.effectiveHydrationMl, 0.001)
        assertEquals("v1", result.modelVersion)
    }

    @Test
    fun testComputeEffectiveHydration_unknownBeverageClassReturnsNull() {
        val factors = listOf(HydrationFactorModelRow(modelVersion = "v1", beverageClass = "coffee", retentionFactor = 0.98))
        assertNull(computeEffectiveHydration(waterMl = 240.0, beverageClass = "unknown_class", factors = factors, modelVersion = "v1"))
    }

    @Test
    fun testComputeEffectiveHydration_missingWaterReturnsNull() {
        val factors = listOf(HydrationFactorModelRow(modelVersion = "v1", beverageClass = "coffee", retentionFactor = 0.98))
        assertNull(computeEffectiveHydration(waterMl = null, beverageClass = "coffee", factors = factors, modelVersion = "v1"))
    }
}
