package com.bioscan.fieldterminal.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class DailyAggregationTest {
    private val utc = ZoneOffset.UTC
    private fun at(day: Int, hour: Int) = Instant.parse("2026-10-%02dT%02d:00:00Z".format(day, hour))

    @Test
    fun testAverage_ignoresOrigin() {
        val v = listOf(TimedValue(at(1, 8), 40.0, "a"), TimedValue(at(1, 9), 60.0, "b"), TimedValue(at(1, 10), 50.0))
        assertEquals(50.0, averageByLocalDate(v, utc).getValue(LocalDate.of(2026, 10, 1)), 1e-9)
    }

    @Test
    fun testDominantOrigin_isTheOriginWithMostReadings() {
        val v = listOf(
            TimedValue(at(1, 7), 40.0, "watch"), TimedValue(at(1, 8), 41.0, "watch"), TimedValue(at(1, 9), 42.0, "watch"),
            TimedValue(at(1, 10), 50.0, "phone"),
        )
        assertEquals("watch", dominantOriginByLocalDate(v, utc).getValue(LocalDate.of(2026, 10, 1)))
    }

    @Test
    fun testDominantOrigin_tieGoesToTheMostRecentReading() {
        val v = listOf(TimedValue(at(2, 7), 40.0, "old"), TimedValue(at(2, 20), 41.0, "new"))
        assertEquals("new", dominantOriginByLocalDate(v, utc).getValue(LocalDate.of(2026, 10, 2)))
    }

    @Test
    fun testDominantOrigin_skipsUnknownOrigins_andSeparatesDays() {
        val v = listOf(TimedValue(at(3, 7), 40.0), TimedValue(at(4, 7), 41.0, "zepp"))
        val m = dominantOriginByLocalDate(v, utc)
        assertNull(m[LocalDate.of(2026, 10, 3)]) // never labelled with a guess
        assertEquals("zepp", m.getValue(LocalDate.of(2026, 10, 4)))
        assertTrue(dominantOriginByLocalDate(emptyList(), utc).isEmpty())
    }
}
