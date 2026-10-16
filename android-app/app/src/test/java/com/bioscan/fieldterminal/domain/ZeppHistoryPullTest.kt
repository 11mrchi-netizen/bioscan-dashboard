package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ZeppHistoryPullTest {
    private val today = LocalDate.parse("2026-10-16")

    @Test
    fun `windows walk back from today, a week each, without gaps`() {
        val s0 = startHistoryPull(today)
        assertEquals(LocalDate.parse("2026-10-10") to today, s0.nextWindow)
        val s1 = s0.afterWindow(workouts = 5, bandDays = 7)
        assertEquals(LocalDate.parse("2026-10-03") to LocalDate.parse("2026-10-09"), s1.nextWindow)
        assertEquals(5, s1.workouts)
        assertEquals(7, s1.bandDays)
        assertEquals("2026-10-10", s1.oldestWithData)
    }

    @Test
    fun `the pull stops after eight empty weeks in a row, a shorter gap does not stop it`() {
        var s = startHistoryPull(today).afterWindow(3, 7)
        repeat(HISTORY_STOP_AFTER_EMPTY - 1) { s = s.afterWindow(0, 0) }
        assertFalse(s.done)
        // Data again (seven weeks without the band): the streak starts over.
        s = s.afterWindow(1, 5)
        assertEquals(0, s.emptyStreak)
        repeat(HISTORY_STOP_AFTER_EMPTY) { s = s.afterWindow(0, 0) }
        assertTrue(s.done)
        assertEquals(4, s.workouts)
    }

    @Test
    fun `the pull never goes before the floor`() {
        val s = HistoryPullState(nextEnd = HISTORY_FLOOR.plusDays(5).toString()).afterWindow(1, 1)
        assertTrue(s.done)
    }

    @Test
    fun `the settings line says where it is`() {
        val s = startHistoryPull(today).afterWindow(2, 7)
        assertTrue(s.describe(running = true).startsWith("Pulling, now at 2026-10-09 back to 2026-10-10"))
        assertTrue(s.copy(error = "Zepp's token has expired").describe(running = false).startsWith("Paused"))
        assertTrue(s.copy(done = true).describe(running = false).startsWith("Done: Zepp history back to 2026-10-10"))
    }
}
