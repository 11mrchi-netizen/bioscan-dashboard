package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class VitalsTrendSeriesTest {

    @Test
    fun weekShowsRawDailyPoints() {
        val today = LocalDate.of(2026, 9, 29)
        val points = (0..6).map { today.minusDays(it.toLong()) to (40.0 + it) }
        val series = vitalsTrendSeries(points, VitalsTimeframe.Week, today)
        assertEquals(7, series.size)
    }

    @Test
    fun longerWindowBucketsIntoWeeklyMeans() {
        // Two readings 14 days apart land in different Monday-anchored weeks
        // regardless of where "today" falls in its own week -- bucketing
        // collapses each to its own weekly mean instead of one point per day.
        val today = LocalDate.of(2026, 9, 29)
        val points = listOf(today.minusDays(14) to 50.0, today to 60.0)
        val series = vitalsTrendSeries(points, VitalsTimeframe.Month, today)
        assertEquals(2, series.size)
        assertEquals(listOf(50.0, 60.0), series.map { it.second }.sorted())
    }

    @Test
    fun allTimeframeIgnoresDateFloor() {
        val today = LocalDate.of(2026, 9, 29)
        val oldPoint = today.minusYears(2) to 45.0
        val series = vitalsTrendSeries(listOf(oldPoint), VitalsTimeframe.All, today)
        assertEquals(1, series.size)
    }
}
