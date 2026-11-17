package com.bioscan.fieldterminal.domain.routine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class RoutinePageTest {
    private val zone = ZoneId.of("Asia/Taipei")
    private val tue = LocalDate.of(2026, 11, 10)
    private fun ms(d: LocalDate, h: Int, m: Int = 0) = d.atTime(h, m).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun theRibbonGivesEachSlotToTheFirstKindAndLeavesTheFutureOut() {
        val iv = listOf(
            RibbonInterval(ms(tue.minusDays(1), 23), ms(tue, 6, 30), RibbonKind.ASLEEP),
            RibbonInterval(ms(tue, 6, 30), ms(tue, 8), RibbonKind.HOME),
            RibbonInterval(ms(tue, 7), ms(tue, 8), RibbonKind.TRAINING), // a run from home wins over home
            RibbonInterval(ms(tue, 8), ms(tue, 8, 30), RibbonKind.MOVE),
        )
        val day = ribbonDay(tue, zone, iv, nowMs = ms(tue, 9))
        assertEquals(listOf(RibbonKind.ASLEEP, RibbonKind.HOME, RibbonKind.TRAINING, RibbonKind.MOVE, RibbonKind.UNKNOWN), day.spans.map { it.kind })
        assertEquals(26, day.spans.first().to) // 00:00-06:30
        assertEquals(36, day.spans.last().to) // drawn to 09:00 only
        assertEquals(60.0, ribbonTotals(listOf(day))[RibbonKind.TRAINING]!!, 0.0)
    }

    @Test
    fun aDayAStreamDidNotRecordIsMissingNotZero() {
        val entries = listOf(TimeEntry("phone:video", "Video", TimeSource.PHONE, tue.minusDays(1), 120.0))
        val recorded = mapOf(TimeSource.PHONE to setOf(tue.minusDays(1), tue.minusDays(2)))
        val line = timeLines(entries, recorded, tue.minusDays(27), tue).single()
        assertEquals(mapOf(tue.minusDays(1) to 120.0, tue.minusDays(2) to 0.0), line.series)
        assertNull(line.average(tue)) // two recorded days: not enough for an average
        val full = DailyLine("x", "X", TimeSource.LOGGED, (0L..9L).associate { tue.minusDays(it) to it.toDouble() })
        assertEquals(4.5, full.average(tue)!!, 1e-9)
    }

    @Test
    fun placesCountOnlyWellRecordedDays() {
        val lines = placeLines(mapOf(tue to (mapOf("home" to 600.0, "food" to 60.0) to 90), tue.minusDays(1) to (mapOf("home" to 120.0) to 20)))
        val home = lines.first { it.key == "place:home" }
        assertEquals(mapOf(tue to 10.0), home.series)
        assertEquals("Food", lines.first { it.key == "place:food" }.label)
    }

    @Test
    fun calendarStreaksAndKeptBlocks() {
        val a = RoutineActivity(1, "Meditation", ActivityKind.PRACTICE, days = setOf(1, 3, 5)) // Mon Wed Fri
        val logs = listOf(tue.minusDays(1), tue.minusDays(4), tue.minusDays(6), tue.minusDays(8)).map {
            RoutineLog(it.toEpochDay(), 1, it, null, null, 10.0, LogStatus.DONE, LogSource.MANUAL)
        } + RoutineLog(99, 1, tue.minusDays(11), null, null, null, LogStatus.SKIPPED, LogSource.REMINDER)
        val cal = practiceCalendar(a, logs, tue, weeks = 3).toMap()
        assertEquals(CalendarState.DONE, cal[tue.minusDays(1)]) // Mon 9
        assertEquals(CalendarState.OFF, cal[tue]) // Tue: not scheduled
        assertEquals(CalendarState.AHEAD, cal[tue.plusDays(1)])
        assertEquals(CalendarState.SKIPPED, cal[tue.minusDays(11)]) // Fri 30 Oct
        assertEquals(CalendarState.MISSED, cal[tue.minusDays(13)]) // Wed 28 Oct
        assertEquals(4, bestStreak(a, logs, tue, days = 14)) // Mon 2, Wed 4, Fri 6, Mon 9
        assertEquals(3, keptByFourWeeks(a, logs, tue).size)
    }

    @Test
    fun theMonthAgainstTheOneBeforeInWords() {
        val series = (0L until 56L).associate { tue.minusDays(it) to if (it < 28) 38.0 else 24.0 }
        val c = compareWindows(DailyLine("outdoor", "Outdoors", TimeSource.LOCATION, series), tue)!!
        assertEquals(38.0, c.recent, 1e-9)
        assertEquals("up from 24 min", changeWords(c, { "${it.toInt()} min" }))
        assertNull(compareWindows(DailyLine("x", "X", TimeSource.PHONE, series.filterKeys { it.isAfter(tue.minusDays(28)) }), tue))
    }

    @Test
    fun bedtimesEitherSideOfMidnightAreClose() {
        assertEquals(330.0, minutesSinceEvening(LocalTime.of(23, 30)), 1e-9)
        assertEquals(390.0, minutesSinceEvening(LocalTime.of(0, 30)), 1e-9)
        val spread = rollingSpread((0L..6L).associate { tue.minusDays(it) to if (it % 2 == 0L) 330.0 else 390.0 })
        assertTrue(spread[tue]!! in 25.0..40.0)
        assertTrue(tue.minusDays(5) !in spread) // fewer than 4 nights in its window
    }
}
