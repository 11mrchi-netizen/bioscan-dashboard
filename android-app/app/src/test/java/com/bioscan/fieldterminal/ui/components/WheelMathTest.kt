package com.bioscan.fieldterminal.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class WheelMathTest {

    private val row = 100

    @Test
    fun theTopRowIsTheCentredValueBecauseBlankPaddingRowsSitAboveIt() {
        // 2 blank rows above value 0: with row 0 first visible, the centre slot shows value 0, not value -2.
        assertEquals(0, wheelCentredIndex(0, 0, row, 12))
        assertEquals(5, wheelCentredIndex(5, 0, row, 12))
    }

    @Test
    fun theLastValuesAreReachable() {
        // The list scrolls at most to firstVisible = count - 1 (the bottom blank rows fill the rest of the window).
        // This is the regression: hours stopped at 10 and minutes at 57 when the offset was wrongly subtracted again.
        assertEquals(11, wheelCentredIndex(11, 0, row, 12)) // hour 12
        assertEquals(10, wheelCentredIndex(10, 0, row, 12)) // hour 11
        assertEquals(59, wheelCentredIndex(59, 0, row, 60)) // minute 59
        assertEquals(58, wheelCentredIndex(58, 0, row, 60)) // minute 58
    }

    @Test
    fun theCentreFollowsTheNearestRowWhileDragging() {
        assertEquals(7, wheelCentredIndex(7, 49, row, 60))
        assertEquals(8, wheelCentredIndex(7, 50, row, 60))
        assertEquals(8, wheelCentredIndex(7, 99, row, 60))
    }

    @Test
    fun theResultIsAlwaysAValidIndex() {
        assertEquals(59, wheelCentredIndex(59, 90, row, 60)) // rounding up past the end clamps
        assertEquals(0, wheelCentredIndex(-1, 0, row, 60))
        assertEquals(0, wheelCentredIndex(0, 10, 0, 12)) // unmeasured row height: no rounding
    }
}
