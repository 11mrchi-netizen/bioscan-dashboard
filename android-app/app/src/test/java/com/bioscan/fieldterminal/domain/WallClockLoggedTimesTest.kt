package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** supplement_log.taken_at is a logged wall-clock time: an evening dose stays on its own day (CLAUDE.md "Dates and times"). */
class WallClockLoggedTimesTest {
    @Test
    fun anEveningDoseStaysOnItsDay() {
        assertEquals(LocalDate.of(2026, 11, 4), WallClock.date("2026-11-04T21:30:00+00:00"))
        // Its real instant in Taipei is 8 h earlier than the digits read as UTC.
        assertEquals(java.time.Instant.parse("2026-11-04T13:30:00Z"), WallClock.instant("2026-11-04T21:30:00+00:00", ZoneId.of("Asia/Taipei")))
    }
}
