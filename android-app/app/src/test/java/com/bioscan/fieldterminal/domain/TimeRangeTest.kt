package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class TimeRangeTest {
    private val today = LocalDate.of(2026, 10, 26) // a Monday

    private fun daily(n: Int, v: (Int) -> Double = { 10.0 }) = (0 until n).map { today.minusDays(it.toLong()) to v(it) }

    @Test
    fun weekAndMonthStayDaily() {
        assertEquals(7, aggregate(daily(400), TimeRange.Week, today).size)
        assertEquals(30, aggregate(daily(400), TimeRange.Month, today).size)
    }

    @Test
    fun quarterAndHalfAreWeeklyMeans() {
        val out = aggregate(daily(200) { if (it < 7) 20.0 else 10.0 }, TimeRange.Quarter, today)
        assertEquals(today to 20.0, out.last())              // this Monday's week holds days 0..0 only (today is Monday)
        assertEquals(today.minusDays(7), out[out.size - 2].first)
        assertEquals(true, out.all { it.first.dayOfWeek == java.time.DayOfWeek.MONDAY })
    }

    @Test
    fun yearAndAllAreMonthlyMeans() {
        val out = aggregate(daily(800), TimeRange.All, today)
        assertEquals(true, out.all { it.first.dayOfMonth == 1 })
        assertEquals(10.0, out.first().second, 1e-9)
    }

    @Test
    fun rangeColumnsAverageLowsAndHighs() {
        val pts = listOf(today.minusDays(1) to DayRange(50.0, 100.0), today.minusDays(2) to DayRange(60.0, 140.0))
        val out = aggregateRanges(pts, TimeRange.Year, today)
        assertEquals(1, out.size)
        assertEquals(DayRange(55.0, 120.0), out[0].second)
    }

    @Test
    fun windowExcludesFuture() {
        assertEquals(0, aggregate(listOf(today.plusDays(1) to 1.0), TimeRange.Week, today).size)
    }
}
