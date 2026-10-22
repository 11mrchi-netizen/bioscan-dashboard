package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class RunningSeriesTest {
    private val d0 = LocalDate.of(2026, 9, 7) // a Monday

    @Test
    fun rollingMedianWaitsForItsMinimumAndUsesTheLast28Days() {
        val series = listOf(0 to 10.0, 3 to 20.0, 6 to 30.0, 9 to 40.0, 40 to 100.0).map { (day, v) -> d0.plusDays(day.toLong()) to v }
        val median = rollingMedian(series)
        assertEquals(listOf(d0.plusDays(6) to 20.0, d0.plusDays(9) to 25.0), median.take(2))
        // 31 days after day 9 the window holds only the newest run: under three, no point.
        assertEquals(2, median.size)
        // Two runs on one day give one median point.
        assertEquals(1, rollingMedian(listOf(d0 to 1.0, d0 to 3.0, d0 to 5.0)).size)
    }

    @Test
    fun theTrailBaselineIsTheLastFiveTrailRunsWithinSixtyDays() {
        // One trail run a week: 28 days never holds more than four, the trail baseline still has a median from the 3rd.
        val weekly = (0 until 8).map { w -> d0.plusDays(7L * w) to (10.0 + w) }
        assertEquals(0, RunBaseline(28, 6).line(weekly).size)
        val line = RunBaseline.TRAIL.line(weekly)
        assertEquals(d0.plusDays(14) to 11.0, line.first())
        // At week 7 the last five are 13..17.
        assertEquals(15.0, line.last().second, 1e-9)
        // A run more than 60 days back is not in the window, however few runs there are.
        val sparse = listOf(d0 to 1.0, d0.plusDays(70) to 5.0, d0.plusDays(80) to 6.0, d0.plusDays(90) to 7.0)
        assertEquals(listOf(d0.plusDays(90) to 6.0), RunBaseline.TRAIL.line(sparse))
    }

    @Test
    fun aRunIsComparedWithTheRunsBeforeItNeverItself() {
        val runs = listOf(d0 to 10.0, d0.plusDays(5) to 12.0, d0.plusDays(9) to 14.0, d0.plusDays(12) to 40.0)
        assertEquals(12.0, RunBaseline.ROAD.before(runs, d0.plusDays(12))!!, 1e-9)
        assertNull(RunBaseline.ROAD.before(runs, d0.plusDays(9))) // only two before it
        assertNull(RunBaseline.ROAD.before(runs, d0.plusDays(40))) // the window starts after day 12
        assertEquals("LAST-5 MEDIAN", RunBaseline.TRAIL.legend)
        assertEquals("28D MEDIAN", RunBaseline.ROAD.legend)
    }

    @Test
    fun stackedWeeksAddUpAndKeepQuietWeeks() {
        data class R(val date: LocalDate, val km: Double, val part: Int)
        val runs = listOf(R(d0, 10.0, 0), R(d0.plusDays(2), 5.0, 2), R(d0.plusDays(3), 8.0, 0), R(d0.plusDays(15), 20.0, 1))
        val weeks = bucketWeeklyStacked(runs, { it.date }, { it.km }, { it.part }, 3, d0, d0.plusDays(16))
        assertEquals(3, weeks.size)
        assertEquals(listOf(18.0, 0.0, 5.0), weeks[0].parts)
        assertEquals(0.0, weeks[1].total, 0.0)
        assertEquals(listOf(0.0, 20.0, 0.0), weeks[2].parts)
        assertEquals(runs.sumOf { it.km }, weeks.sumOf { it.total }, 1e-9)
    }

    @Test
    fun roadAndTrailRecords() {
        val road = listOf(
            RecordRun(d0, 12.0, 5.5, 40.0, 5.4),
            RecordRun(d0.plusDays(2), 4.0, 4.2, 10.0, 4.2), // fastest but under 5 km
            RecordRun(d0.plusDays(8), 6.0, 4.8, 20.0, 4.7),
        )
        val r = runRecords(road, trail = false, today = d0.plusDays(10))
        assertEquals(12.0, r.longest!!.value, 0.0)
        assertEquals(4.8, r.fastest!!.value, 0.0)
        assertNull(r.biggestClimb)
        assertEquals(16.0, r.biggestWeek!!.value, 0.0)

        val trail = listOf(
            RecordRun(d0, 23.7, 9.0, 1118.0, 6.1),
            RecordRun(d0.plusDays(22), 19.0, 7.5, 1129.0, 5.6), // same week as the 8 km run below
            RecordRun(d0.plusDays(27), 8.0, 6.0, 300.0, 5.0), // under 10 km: not the fastest
        )
        val t = runRecords(trail, trail = true, today = d0.plusDays(30))
        assertEquals(23.7, t.longest!!.value, 0.0)
        assertEquals(5.6, t.fastest!!.value, 0.0)
        assertEquals(1129.0, t.biggestClimb!!.value, 0.0)
        assertEquals(27.0, t.biggestWeek!!.value, 1e-9)
        assertEquals(RunRecords(null, null, null, null), runRecords(emptyList(), trail = true, today = d0))
    }
}
