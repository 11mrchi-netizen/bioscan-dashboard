package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BarcodeTest {
    @Test
    fun upcAIsKept() = assertEquals("810014674858", normalizeBarcode("810014674858"))

    @Test
    fun ean13WithLeadingZeroBecomesUpcA() = assertEquals("033674139417", normalizeBarcode("0033674139417"))

    @Test
    fun spacesAndDashesAreStripped() = assertEquals("033674139417", normalizeBarcode("0 33674-13941 7"))

    @Test
    fun trueEan13IsKept() = assertEquals("4006381333931", normalizeBarcode("4006381333931"))

    @Test
    fun oddLengthsAreRejected() {
        assertNull(normalizeBarcode("12345"))
        assertNull(normalizeBarcode("abc"))
        assertNull(normalizeBarcode(""))
    }

    // The scan that created a product under a wrong number: an unknown code is only accepted when read twice the same.
    @Test
    fun anUnknownScanIsConfirmedOnlyByTheSameNumberAgain() {
        val check = UnknownScanCheck()
        assertEquals(false, check.confirmed("753019770715")) // first read, wrong
        assertEquals(false, check.confirmed("4713057770715")) // second read differs: not confirmed, looked up instead
        assertEquals(true, check.confirmed("4713057770715")) // read the same again: this is the number on the package
        assertEquals(false, check.confirmed("4713057770715")) // a later scan starts over
    }
}
