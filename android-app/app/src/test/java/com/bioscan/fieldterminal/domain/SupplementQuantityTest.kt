package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class SupplementQuantityTest {
    @Test fun formatsWholeFractionAndMixed() {
        assertEquals("1", formatSupplementQty(1.0))
        assertEquals("½", formatSupplementQty(0.5))
        assertEquals("¼", formatSupplementQty(0.25))
        assertEquals("1¾", formatSupplementQty(1.75))
        assertEquals("2", formatSupplementQty(2.0))
    }
}
