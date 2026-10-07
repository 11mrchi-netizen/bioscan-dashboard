package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProductMatchingTest {

    private data class Listing(val name: String?, val offMarket: Boolean? = null)

    private fun pick(items: List<Listing>, productName: String) =
        pickBestListing(items, productName, { it.name }, { it.offMarket })

    private val alive = listOf(
        Listing("Alive! Women's 50+ Ultra Potency Complete Multivitamin"),
        Listing("Alive! Women's 50+ Ultra Multivitamin"),
        Listing("Alive! Once Daily Women's 50+ Ultra Potency Complete Multivitamin"),
    )

    @Test
    fun picksTheListingWhoseNameBestOverlapsTheUsersProduct() {
        assertEquals(alive[1], pick(alive, "Alive! Women's 50+ Ultra Multivitamin"))
        assertEquals(alive[2], pick(alive, "Alive Once Daily Womens 50+ Ultra Potency Complete Multivitamin"))
    }

    @Test
    fun brandParentheticalsDoNotHurtTheMatch() {
        val items = listOf(Listing("Zinc Picolinate 22 mg"), Listing("Extra Strength Zinc Picolinate 50 mg"))
        assertEquals(items[1], pick(items, "Zinc Picolinate (Swanson) 50 mg Extra Strength"))
    }

    @Test
    fun tiesPreferAnOnMarketListing() {
        val items = listOf(Listing("Boron 10 mg", offMarket = true), Listing("Boron 10 mg", offMarket = false))
        assertEquals(items[1], pick(items, "Boron 10 mg"))
    }

    @Test
    fun fullTieKeepsTheFirstListing() {
        val items = listOf(Listing("Creatine A"), Listing("Creatine B"))
        assertEquals(items[0], pick(items, "Creatine"))
    }

    @Test
    fun unnamedListingsFallBackToTheFirst() {
        val items = listOf(Listing(null), Listing(null))
        assertEquals(items[0], pick(items, "Anything"))
    }

    @Test
    fun emptyListIsNull() {
        assertNull(pick(emptyList(), "Anything"))
    }
}
