package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SeriesBreakStartsTest {
    @Test
    fun testStartsFrom_parsesConfirmedDates_andDropsNulls() {
        val starts = seriesBreakStartsFrom(mapOf("hrv" to "2026-05-04", "rhr" to null, "deep_min" to "2026-03-02"))
        assertEquals(LocalDate.of(2026, 5, 4), starts.start("hrv"))
        assertEquals(LocalDate.of(2026, 3, 2), starts.start("deep_min"))
        assertNull(starts.start("rhr"))
        assertNull(starts.start("unknown_metric"))
    }

    @Test
    fun testNone_hasNoStarts() {
        assertTrue(SeriesBreakStarts.NONE.byMetric.isEmpty())
        assertNull(SeriesBreakStarts.NONE.start("hrv"))
    }

    @Test
    fun testSeriesBreakCategory_exists() {
        // Contract: the integrity vocabulary names the new category (docs/analysis-layer-3).
        assertTrue(com.bioscan.fieldterminal.domain.integrity.IntegrityCategory.values().any { it.name == "SERIES_BREAK" })
    }
}
