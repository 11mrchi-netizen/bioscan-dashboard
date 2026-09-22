package com.bioscan.fieldterminal.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AddEntryRepositoryTest {

    @Test
    fun testParsesValueAndUnitWithSpace() {
        assertEquals(500.0 to "mg", parseDose("500 mg"))
    }

    @Test
    fun testParsesValueAndUnitWithoutSpace() {
        assertEquals(500.0 to "mg", parseDose("500mg"))
    }

    @Test
    fun testParsesDecimalValue() {
        assertEquals(2.5 to "mg", parseDose("2.5 mg"))
    }

    @Test
    fun testKeepsTrailingFrequencyNoteInUnit() {
        // Real roster row: "36 mg, 3x/week" -- the frequency note stays
        // attached to the unit rather than being dropped.
        assertEquals(36.0 to "mg, 3x/week", parseDose("36 mg, 3x/week"))
    }

    @Test
    fun testNoLeadingNumberKeepsWholeStringAsUnit() {
        val (value, unit) = parseDose("as needed")
        assertNull(value)
        assertEquals("as needed", unit)
    }

    @Test
    fun testBlankDoseReturnsNulls() {
        val (value, unit) = parseDose("   ")
        assertNull(value)
        assertNull(unit)
    }
}
