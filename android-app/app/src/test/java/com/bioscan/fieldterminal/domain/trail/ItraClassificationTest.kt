package com.bioscan.fieldterminal.domain.trail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ItraClassificationTest {

    @Test
    fun bandsMatchPublishedThresholds() {
        assertEquals("XXS", itraCategory(0.0))
        assertEquals("XXS", itraCategory(24.9))
        assertEquals("XS", itraCategory(25.0))
        assertEquals("XS", itraCategory(44.9))
        assertEquals("S", itraCategory(45.0))
        assertEquals("S", itraCategory(74.9))
        assertEquals("M", itraCategory(75.0))
        assertEquals("M", itraCategory(114.9))
        assertEquals("L", itraCategory(115.0))
        assertEquals("L", itraCategory(154.9))
        assertEquals("XL", itraCategory(155.0))
        assertEquals("XL", itraCategory(209.9))
        assertEquals("XXL", itraCategory(210.0))
        assertEquals("XXL", itraCategory(500.0))
    }

    @Test
    fun negativeKmEffortIsNull() {
        assertNull(itraCategory(-1.0))
    }

    @Test
    fun workedExampleFromCitedSource() {
        // 42 km + 2000 m gain = 62 km-effort -> S, per trailia.run's own worked example.
        assertEquals("S", itraCategory(42.0 + 2000.0 / 100))
    }
}
