package com.bioscan.fieldterminal.ui.screens.detail

import com.bioscan.fieldterminal.data.model.SleepAnalysisRow
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class DetailColumnsTest {
    private val today = LocalDate.of(2026, 11, 3)

    @Test
    fun nightsAreColumnsUpToTwoMonthsThenWeeklyMeans() {
        val rows = (0 until 90).map { i ->
            SleepAnalysisRow(today.minusDays(i.toLong()).toString(), hours = 7.0, deepMin = 60.0 + i % 2 * 20, remMin = 90.0, lightMin = 240.0)
        } + SleepAnalysisRow(today.minusDays(200).toString(), hours = 7.0) // no stages: left out
        val month = stageColumns(rows, today, 30)
        assertEquals(30, month.size)
        assertEquals(listOf(60.0, 240.0, 90.0), month.last().second)
        val year = stageColumns(rows, today, 365)
        assertEquals(14, year.size) // 90 nights fall in 14 of the year's weeks
        assertEquals(70.0, year[1].second[0], 2.0)
    }

    @Test
    fun dayColumnsAverageWeeksPastTwoMonths() {
        val rows = (0 until 14).map { today.minusDays(it.toLong()) to listOf(it.toDouble()) }
        assertEquals(14, dayColumns(rows, today, 30) { it }.size)
        val weekly = dayColumns(rows, today, 90) { it }
        assertEquals(3, weekly.size)
        assertEquals(6.5, weekly.map { it.second[0] }.average(), 2.0)
    }
}
