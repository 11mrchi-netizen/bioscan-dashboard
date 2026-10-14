package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class FreshnessTest {

    private val today = LocalDate.of(2026, 10, 14)

    @Test
    fun todaysReadingWithTodaysSyncShowsTheTimeOnly() {
        val f = freshnessLine(today, "WEARABLE", LocalDateTime.of(2026, 10, 14, 7, 42), today, staleAfterDays = 1)
        assertEquals("TODAY'S READING · WEARABLE · SYNCED 07:42", f.text)
        assertFalse(f.stale)
    }

    @Test
    fun yesterdaysReadingIsNotStaleAndAnOlderSyncShowsItsDay() {
        val f = freshnessLine(today.minusDays(1), "WEARABLE", LocalDateTime.of(2026, 10, 13, 21, 5), today, staleAfterDays = 1)
        assertEquals("YESTERDAY'S READING · WEARABLE · SYNCED 13 OCT 21:05", f.text)
        assertFalse(f.stale)
    }

    @Test
    fun aReadingOlderThanTheThresholdIsStale() {
        val f = freshnessLine(LocalDate.of(2026, 10, 9), "WEARABLE", null, today, staleAfterDays = 1)
        assertEquals("STALE · READING FROM 9 OCT · WEARABLE", f.text)
        assertTrue(f.stale)
    }

    @Test
    fun theThresholdComesFromTheCaller() {
        assertFalse(freshnessLine(today.minusDays(2), "WEARABLE", null, today, staleAfterDays = 2).stale)
        assertTrue(freshnessLine(today.minusDays(3), "WEARABLE", null, today, staleAfterDays = 2).stale)
    }

    @Test
    fun noReadingIsSaidPlainlyAndIsNotStale() {
        val f = freshnessLine(null, "WEARABLE", null, today, staleAfterDays = 1)
        assertEquals("NO READING YET · WEARABLE", f.text)
        assertFalse(f.stale)
    }
}
