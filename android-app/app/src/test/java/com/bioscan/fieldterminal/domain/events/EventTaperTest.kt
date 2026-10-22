package com.bioscan.fieldterminal.domain.events

import com.bioscan.fieldterminal.domain.training.generate.WeekKind
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class EventTaperTest {
    private val monday = LocalDate.of(2026, 11, 2)
    private fun ev(p: EventPriority, min: Double, date: LocalDate = monday.plusWeeks(9).plusDays(6)) =
        AthleteEvent(id = 1, name = "Synthetic race", kind = EventKind.RACE, sport = EventSport.RUN, priority = p, date = date, targetTimeMin = min)

    @Test
    fun theBlockEndsInTheEventsWeek() {
        assertEquals(10, weeksToEvent(monday, monday.plusWeeks(9).plusDays(6))) // Sunday of week 10
        assertEquals(1, weeksToEvent(monday, monday))
        assertEquals(0, weeksToEvent(monday, monday.minusDays(1)))
    }

    @Test
    fun taperLengthFollowsPriorityAndLength() {
        assertEquals(2, taperWeeks(ev(EventPriority.A, 45.0)))
        assertEquals(2, taperWeeks(ev(EventPriority.B, 120.0)))
        assertEquals(1, taperWeeks(ev(EventPriority.B, 45.0)))
        assertEquals(0, taperWeeks(ev(EventPriority.C, 200.0)))
    }

    @Test
    fun theLastWeeksBecomeLighterWeeks() {
        val t = List(6) { WeekKind.Normal }.toMutableList().also { it[5] = WeekKind.Test }
        assertEquals(listOf(WeekKind.Normal, WeekKind.Normal, WeekKind.Normal, WeekKind.Normal, WeekKind.Deload, WeekKind.Deload), withTaper(t, 2))
        assertEquals(t, withTaper(t, 0))
        // Never the whole block.
        assertEquals(WeekKind.Normal, withTaper(listOf(WeekKind.Normal, WeekKind.Normal), 5).first())
    }

    @Test
    fun onlyOpenABEventsFarEnoughAheadCanBeBuiltToward() {
        val list = listOf(ev(EventPriority.A, 200.0), ev(EventPriority.C, 60.0), ev(EventPriority.B, 60.0, monday.plusDays(10)))
        assertEquals(listOf(EventPriority.A), buildableEvents(list, monday).map { it.priority })
    }
}
