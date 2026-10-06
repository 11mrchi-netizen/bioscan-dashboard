package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.data.model.SupplementProductIngredientWithKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SupplementCompositionTest {

    // -------------------------------------------------------------------
    // convertMass
    // -------------------------------------------------------------------

    @Test
    fun convertsGramsToMilligrams() {
        assertEquals(2000.0, convertMass(2.0, "g", "mg")!!, 0.001)
    }

    @Test
    fun convertsMilligramsToMicrograms() {
        assertEquals(5000.0, convertMass(5.0, "mg", "mcg")!!, 0.001)
    }

    @Test
    fun sameUnitIsIdentity() {
        assertEquals(400.0, convertMass(400.0, "mg", "mg")!!, 0.001)
    }

    @Test
    fun unsupportedUnitReturnsNullNeverGuessed() {
        // IU is substance-specific (differs per vitamin) -- never converted.
        assertNull(convertMass(5000.0, "IU", "mcg"))
        assertNull(convertMass(1.0, "pill", "mg"))
    }

    // -------------------------------------------------------------------
    // nutrientContribution
    // -------------------------------------------------------------------

    private fun ingredient(
        compoundAmount: Double,
        compoundUnit: String = "mg",
        elementalAmount: Double? = null,
        elementalUnit: String? = null,
        nutrientKey: String? = "magnesium",
    ) = SupplementProductIngredientWithKey(compoundAmount, compoundUnit, elementalAmount, elementalUnit, nutrientKey)

    @Test
    fun compoundOnlyFallsBackToCompoundAmount() {
        // A mineral complex with no known elemental split -- same number the
        // old regex fallback already produced, never fabricated.
        val result = nutrientContribution(ingredient(compoundAmount = 400.0, compoundUnit = "mg"), servingCount = 1.0)
        assertEquals(NutrientContribution("magnesium", 400.0, "mg"), result)
    }

    @Test
    fun elementalAmountPreferredWhenKnown() {
        val result = nutrientContribution(
            ingredient(compoundAmount = 2000.0, compoundUnit = "mg", elementalAmount = 200.0, elementalUnit = "mg"),
            servingCount = 1.0,
        )
        assertEquals(NutrientContribution("magnesium", 200.0, "mg"), result)
    }

    @Test
    fun servingCountMultiplies() {
        val result = nutrientContribution(ingredient(compoundAmount = 500.0, elementalAmount = 500.0, elementalUnit = "mg"), servingCount = 2.0)
        assertEquals(NutrientContribution("magnesium", 1000.0, "mg"), result)
    }

    @Test
    fun noNutrientKeyReturnsNull() {
        val result = nutrientContribution(ingredient(compoundAmount = 500.0, nutrientKey = null), servingCount = 1.0)
        assertNull(result)
    }
}
