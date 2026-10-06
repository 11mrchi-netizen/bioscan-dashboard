package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

class SupplementAdherenceTest {

    private val d = { day: Int -> LocalDate.of(2026, 10, day) }

    private fun adherence(
        timeOfDay: String = "morning",
        everyNDays: Int? = null,
        start: LocalDate = d(1),
        end: LocalDate? = null,
        taken: Set<LocalDate>,
        from: LocalDate = d(1),
        to: LocalDate = d(10),
    ) = supplementAdherence(timeOfDay, everyNDays, start, end, taken, from, to)

    @Test
    fun dailyCountsEveryDayInWindow() {
        val a = adherence(taken = setOf(d(1), d(2), d(3), d(5)))
        assertEquals(10, a.scheduledDays)
        assertEquals(4, a.takenDays)
        assertEquals(0.4, a.fraction!!, 0.0001)
    }

    @Test
    fun windowIsClippedToSupplementStartAndEnd() {
        val a = adherence(start = d(4), end = d(8), taken = setOf(d(4), d(5)))
        assertEquals(5, a.scheduledDays)
        assertEquals(2, a.takenDays)
    }

    @Test
    fun takesOutsideTheClippedWindowDoNotCount() {
        val a = adherence(start = d(4), taken = setOf(d(1), d(2), d(4)))
        assertEquals(1, a.takenDays)
    }

    @Test
    fun intervalSupplementSchedulesCeilOfDaysOverN() {
        // 10 days, every 3 -> ceil(10/3) = 4 doses expected.
        val a = adherence(everyNDays = 3, taken = setOf(d(1), d(4), d(7)))
        assertEquals(4, a.scheduledDays)
        assertEquals(3, a.takenDays)
        assertEquals(0.75, a.fraction!!, 0.0001)
    }

    @Test
    fun fractionNeverExceedsOne() {
        val a = adherence(everyNDays = 5, taken = setOf(d(1), d(2), d(3), d(4), d(5), d(6)))
        assertEquals(1.0, a.fraction!!, 0.0001)
    }

    @Test
    fun asNeededHasNoAdherence() {
        val a = adherence(timeOfDay = "AS-NEEDED", taken = setOf(d(1), d(2)))
        assertNull(a.fraction)
    }

    @Test
    fun emptyOrInvertedWindowIsUndefinedNotZero() {
        assertNull(adherence(start = d(20), taken = emptySet()).fraction)
        assertNull(adherence(from = d(9), to = d(2), taken = emptySet()).fraction)
    }
}
