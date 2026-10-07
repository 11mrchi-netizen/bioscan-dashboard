package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DsldComparisonTest {

    private fun product(name: String, amount: Double, unit: String = "mg", elemental: Pair<Double, String>? = null) =
        ProductIngredientView(name, amount, unit, elemental?.first, elemental?.second)

    private fun dsld(name: String, amount: Double?, unit: String? = "mg") = DsldIngredient(name, null, amount, unit)

    @Test
    fun brandParentheticalsAreIgnoredWhenMatchingNames() {
        assertEquals("vitamin d3", normalizeIngredientName("Vitamin D3 (Now Food)"))
        val r = compareToDsld(listOf(product("Vitamin D3 (Now Food)", 125.0, "mcg")), listOf(dsld("Vitamin D3", 125.0, "mcg")))
        assertEquals(1, r.matchedIngredients)
        assertTrue(r.conflicts.isEmpty())
    }

    @Test
    fun sameAmountInDifferentMassUnitsIsNotAConflict() {
        // 0.5 g on the product vs 500 mg on the label.
        val r = compareToDsld(listOf(product("Vitamin C", 0.5, "g")), listOf(dsld("Vitamin C", 500.0, "mg")))
        assertTrue(r.conflicts.isEmpty())
    }

    @Test
    fun differingAmountIsAConflictWithBothValuesShown() {
        val r = compareToDsld(listOf(product("Zinc", 50.0)), listOf(dsld("Zinc", 30.0)))
        val c = r.conflicts.single()
        assertEquals(DsldConflictKind.AMOUNT_DIFFERS, c.kind)
        assertEquals("50 mg", c.productText)
        assertEquals("30 mg", c.dsldText)
    }

    @Test
    fun smallDifferenceWithinToleranceIsConsistent() {
        val r = compareToDsld(listOf(product("Creatine", 5000.0)), listOf(dsld("Creatine", 5100.0)))
        assertTrue(r.conflicts.isEmpty())
    }

    @Test
    fun elementalAmountAgreeingWithTheLabelCountsAsConsistent() {
        // Product stores 400 mg compound / 80 mg elemental; the label lists the elemental form.
        val r = compareToDsld(
            listOf(product("Magnesium", 400.0, elemental = 80.0 to "mg")),
            listOf(dsld("Magnesium", 80.0)),
        )
        assertTrue(r.conflicts.isEmpty())
    }

    @Test
    fun nonMassUnitsAreFlaggedNotGuessed() {
        // IU cannot be converted to mg without a substance-specific factor.
        val r = compareToDsld(listOf(product("Vitamin D", 5000.0, "IU")), listOf(dsld("Vitamin D", 125.0, "mcg")))
        assertEquals(DsldConflictKind.UNIT_NOT_COMPARABLE, r.conflicts.single().kind)
    }

    @Test
    fun labelRowWithoutQuantityIsMatchedButNotCompared() {
        val r = compareToDsld(listOf(product("Proprietary Blend", 500.0)), listOf(dsld("Proprietary Blend", null, null)))
        assertEquals(1, r.matchedIngredients)
        assertTrue(r.conflicts.isEmpty())
    }

    @Test
    fun unmatchedRowsAreCountedNotReportedAsConflicts() {
        val r = compareToDsld(
            listOf(product("Boron", 10.0), product("Ashwagandha", 300.0)),
            listOf(dsld("Boron", 10.0), dsld("Vitamin A", 1.0, "mcg"), dsld("Biotin", 30.0, "mcg")),
        )
        assertEquals(1, r.matchedIngredients)
        assertEquals(2, r.dsldOnlyCount)
        assertEquals(listOf("Ashwagandha"), r.productOnly)
        assertTrue(r.conflicts.isEmpty())
    }

    @Test
    fun differentIngredientsWithSharedFirstWordDoNotMatch() {
        val r = compareToDsld(listOf(product("Vitamin C", 500.0)), listOf(dsld("Vitamin B6", 500.0)))
        assertEquals(0, r.matchedIngredients)
    }
}
