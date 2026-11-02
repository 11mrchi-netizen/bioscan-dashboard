package com.bioscan.fieldterminal.domain.score

import com.bioscan.fieldterminal.data.model.StoolAnalysisRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class GutPatternTest {
    private val zone = ZoneOffset.UTC
    private val to = LocalDate.of(2026, 11, 2)

    private fun row(day: LocalDate, hhmm: String, type: Int?) = StoolAnalysisRow("${day}T$hhmm:00Z", type)

    @Test
    fun lanesFollowTheBristolGroups() {
        assertEquals(StoolLane.HARD, StoolLane.of(1))
        assertEquals(StoolLane.HARD, StoolLane.of(2))
        assertEquals(StoolLane.HEALTHY, StoolLane.of(3))
        assertEquals(StoolLane.HEALTHY, StoolLane.of(5))
        assertEquals(StoolLane.LOOSE, StoolLane.of(6))
        assertEquals(StoolLane.LIQUID, StoolLane.of(7))
        assertNull(StoolLane.of(0))
        assertEquals(listOf(StoolLane.LIQUID, StoolLane.LOOSE, StoolLane.HEALTHY, StoolLane.HARD), StoolLane.DRAWN)
    }

    @Test
    fun patternKeepsTheWindowInTimeOrder() {
        val rows = listOf(
            row(to, "19:00", 6),
            row(to, "08:10", 4),
            row(to.minusDays(1), "08:30", 2),
            row(to.minusDays(20), "08:00", 4), // outside a 14-day window
            row(to.minusDays(2), "09:00", null),
        )
        val p = stoolPattern(rows, to.minusDays(13), to, zone)
        assertEquals(4, p.marks.size)
        assertEquals(listOf(9 * 60, 8 * 60 + 30, 8 * 60 + 10, 19 * 60), p.marks.map { it.minuteOfDay })
        assertEquals(3, p.typed)
        assertEquals("1 of 3 healthy · 1 loose · 1 hard", stoolPatternHeadline(p))
        assertEquals(14, p.days)
    }

    @Test
    fun headlineWithNothingLogged() {
        assertEquals("nothing logged", stoolPatternHeadline(stoolPattern(emptyList(), to.minusDays(13), to, zone)))
    }

    @Test
    fun timingLearnsTheUsualWindowAndCallsASteadyFortnight() {
        // 60 earlier days around 07:50 (07:30-08:10), then 14 days the same.
        val rows = (0 until 74).map { i ->
            val d = to.minusDays(i.toLong())
            val minute = 7 * 60 + 30 + (i % 5) * 10
            row(d, "%02d:%02d".format(minute / 60, minute % 60), 4)
        }
        val t = bowelTiming(rows, to, 14, zone)
        assertEquals(14, t.days.size)
        assertEquals(to, t.days.last().first)
        assertTrue(t.usualStart!! < 7 * 60 + 30 && t.usualEnd!! > 8 * 60 + 10)
        assertEquals(true, t.steady)
        assertEquals(true, t.inWindow(7 * 60 + 50))
        assertEquals(false, t.inWindow(13 * 60))
        assertTrue(bowelTimingHeadline(t).startsWith("steady · window "))
    }

    @Test
    fun timingFlagsAMoreVariableFortnight() {
        val prior = (14 until 74).map { i -> row(to.minusDays(i.toLong()), "07:${(30 + (i % 3) * 10)}", 4) }
        val recent = (0 until 14).map { i -> row(to.minusDays(i.toLong()), "%02d:00".format(6 + (i % 4) * 4), 4) }
        val t = bowelTiming(prior + recent, to, 14, zone)
        assertFalse(t.steady!!)
        assertTrue(bowelTimingHeadline(t).startsWith("more variable than usual"))
    }

    @Test
    fun timingWithoutHistoryIsLearning() {
        val t = bowelTiming(listOf(row(to, "08:00", 4)), to, 14, zone)
        assertNull(t.usualStart)
        assertNull(t.steady)
        assertEquals("learning your usual", bowelTimingHeadline(t))
    }
}
