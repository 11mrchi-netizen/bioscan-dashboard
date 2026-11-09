package com.bioscan.fieldterminal.domain.score

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class SleepDebtTest {
    private val day = LocalDate.of(2026, 11, 4)
    private fun nights(vararg h: Double): Map<LocalDate, Double> = h.withIndex().associate { (i, v) -> day.minusDays((h.size - 1 - i).toLong()) to v }

    @Test
    fun aShortNightCarriesPartOfTheShortfall() {
        // Need 8 h: a 6 h night after full ones leaves 55 % of 2 h owed.
        assertEquals(1.1, sleepDebtAfter(day, nights(8.0, 8.0, 8.0, 6.0), 8.0)!!, 1e-9)
        // A full night after it: target 9.1 h, 8 h slept -> 0.55 x 1.1.
        assertEquals(0.605, sleepDebtAfter(day, nights(8.0, 8.0, 6.0, 8.0), 8.0)!!, 1e-9)
        // Meeting the whole target clears it; extra sleep builds no credit.
        assertEquals(0.0, sleepDebtAfter(day, nights(8.0, 6.0, 9.5, 10.0), 8.0)!!, 1e-9)
        // Under ten minutes counts as none; too few nights says nothing.
        assertEquals(0.0, sleepDebtAfter(day, nights(8.0, 8.0, 8.0, 7.75), 8.0)!!, 1e-9)
        assertNull(sleepDebtAfter(day, nights(6.0, 6.0), 8.0))
    }

    @Test
    fun bedByIsTheUsualWakeMinusTheTarget() {
        val t = sleepTarget(7.5, 0.5, listOf(390, 395, 400, 385, 410))
        assertEquals(LocalTime.of(6, 35), t.usualWake)
        assertEquals(LocalTime.of(22, 20), t.bedBy)
        assertEquals("7 h 30 + 30 min owed", t.words)
        assertNull(sleepTarget(7.5, 0.0, listOf(400)).bedBy)
    }
}
