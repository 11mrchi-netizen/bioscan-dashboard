package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

class WallClockTest {
    private val taipei = ZoneId.of("Asia/Taipei")

    @Test
    fun loggedTimesKeepTheirDigits() {
        // A lunch logged at 13:10 in Taipei is stored as 13:10 with a zero offset, and must read back as 13:10.
        assertEquals(LocalDateTime.of(2026, 11, 2, 13, 10, 51), WallClock.read("2026-11-02 13:10:51.523556+00")?.withNano(0))
        assertEquals(LocalDateTime.of(2026, 11, 2, 13, 10), WallClock.read("2026-11-02T13:10:00+00:00"))
        assertEquals(LocalDate.of(2026, 11, 2), WallClock.date("2026-11-02T21:34:11+00:00")) // not the 3rd
        assertEquals(13, WallClock.zoned("2026-11-02T13:10:00Z", taipei)!!.hour)
    }

    @Test
    fun writingKeepsTheLocalDigits() {
        val lunch = ZonedDateTime.of(2026, 11, 2, 13, 10, 0, 0, taipei)
        assertEquals("2026-11-02T13:10Z", WallClock.write(lunch))
        assertEquals(lunch.toLocalDateTime(), WallClock.read(WallClock.write(lunch)))
    }

    @Test
    fun theInstantIsTheLocalTimeInTheZone() {
        // 08:11 local in Taipei is 00:11 UTC: what a true-instant source (a stress sample) would carry.
        assertEquals("2026-11-02T00:11:36Z", WallClock.instant("2026-11-02 08:11:36+00", taipei).toString())
    }
}
