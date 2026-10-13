package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AlcoholTest {
    @Test
    fun testEthanolGrams_beerPint() {
        // 500 ml x 5 % x 0.789 = 19.725 g -> 19.7
        assertEquals(19.7, ethanolGrams(500.0, 5.0)!!, 1e-9)
    }

    @Test
    fun testEthanolGrams_glassOfWine_andSpirit() {
        assertEquals(14.2, ethanolGrams(150.0, 12.0)!!, 1e-9) // 14.202
        assertEquals(11.8, ethanolGrams(37.5, 40.0)!!, 1e-9) // 11.835
    }

    @Test
    fun testEthanolGrams_rejectsUnusableInput() {
        assertNull(ethanolGrams(0.0, 5.0))
        assertNull(ethanolGrams(-100.0, 5.0))
        assertNull(ethanolGrams(500.0, 0.0))
        assertNull(ethanolGrams(500.0, 100.1))
        assertNull(ethanolGrams(Double.NaN, 5.0))
    }

    @Test
    fun testEthanolGrams_pureEthanolEdge() {
        assertEquals(78.9, ethanolGrams(100.0, 100.0)!!, 1e-9)
    }
}
