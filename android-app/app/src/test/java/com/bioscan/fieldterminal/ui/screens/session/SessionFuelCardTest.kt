package com.bioscan.fieldterminal.ui.screens.session

import com.bioscan.fieldterminal.data.model.SessionFuelRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionFuelCardTest {
    private fun row(fasted: Boolean, long: Boolean, during: Int?, protein: Int) = SessionFuelRow(
        sessionId = 1, date = "2026-11-22", durationMin = if (long) 132 else 50, long = long, hoursSinceMeal = if (fasted) 11.5 else 2.0,
        fasted = fasted, carbsBeforeG = if (fasted) 0 else 60, carbsDuringG = during, proteinAfterG = protein, carbsAfterG = 40, proteinDoseG = 22,
    )

    @Test
    fun `a long fasted session with no fuel is flagged`() {
        val lines = sessionFuelLines(row(fasted = true, long = true, during = null, protein = 10))
        assertEquals("11.5 h", lines[0].value)
        assertEquals("Started fasted", lines[0].note)
        assertTrue(lines[0].warn)
        assertEquals("none logged", lines[2].value)
        assertTrue(lines[2].warn)
        assertEquals("Under your 22 g", lines[3].note)
    }

    @Test
    fun `a fed short session reads plainly`() {
        val lines = sessionFuelLines(row(fasted = false, long = false, during = 30, protein = 30))
        assertEquals(null, lines[0].note)
        assertEquals("30 g", lines[2].value)
        assertEquals("Reached your 22 g", lines[3].note)
    }
}
