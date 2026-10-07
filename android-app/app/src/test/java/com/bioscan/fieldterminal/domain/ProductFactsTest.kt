package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProductFactsTest {
    private val yours = ProductFacts(servingSize = 1.0, servingUnit = "capsule", servingForm = "capsule", servingsPerContainer = null, suggestedUse = null)

    @Test
    fun onlyDifferingStatedFactsAreProposed() {
        val theirs = ProductFacts(servingSize = 1.0, servingUnit = "capsule", servingForm = "Capsule", servingsPerContainer = 240.0, suggestedUse = "1 daily with food")
        val p = proposeFacts(yours, theirs)
        assertEquals(listOf(FactField.ServingsPerContainer, FactField.SuggestedUse), p.map { it.field })
        assertEquals("240", p[0].theirs)
        assertEquals(null, p[0].yours)
    }

    @Test
    fun unstatedProviderFactsNeverOverwrite() {
        assertTrue(proposeFacts(yours.copy(servingsPerContainer = 60.0, suggestedUse = "mine"), ProductFacts()).isEmpty())
    }

    @Test
    fun servingSizeComparesQuantityAndUnitTogether() {
        val p = proposeFacts(yours, ProductFacts(servingSize = 2.0, servingUnit = "capsule"))
        assertEquals(listOf(FactField.ServingSize), p.map { it.field })
        assertEquals("1 capsule", p[0].yours)
        assertEquals("2 capsule", p[0].theirs)
    }

    @Test
    fun identicalFactsProposeNothing() {
        assertTrue(proposeFacts(yours, yours).isEmpty())
    }
}
