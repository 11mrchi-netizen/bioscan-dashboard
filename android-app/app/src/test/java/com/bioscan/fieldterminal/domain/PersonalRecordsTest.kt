package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.ui.screens.performance.stepPoints
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate

class PersonalRecordsTest {
    private val d = LocalDate.of(2025, 11, 1)

    @Test
    fun formatsTimesAndPace() {
        assertEquals("24:06", formatRecordTime(1445.9))
        assertEquals("2:05:46", formatRecordTime(7545.5))
        assertEquals("4:49 /km", recordPace(RecordKey.K5, 1445.9))
        assertEquals("1:12 faster", gainWords(RecordKey.K5, 72.0))
    }

    @Test
    fun historiesListEveryRecordInOrderAndReadImprovements() {
        val sets = listOf(
            RecordSet("fastest_5000", 1900.0, d, 1),
            RecordSet("fastest_5000", 1446.0, d.plusDays(30), 2, previousValue = 1900.0, previousDate = d),
        )
        val h = recordHistories(sets)
        assertEquals(RecordKey.entries.size, h.size)
        val k5 = h.first { it.key == RecordKey.K5 }
        assertEquals(1446.0, k5.current!!.value, 1e-9)
        val (s, gain, days) = k5.improvements.single()
        assertEquals(2L, s.sessionId)
        assertEquals(454.0, gain, 1e-9)
        assertEquals(30L, days)
        assertEquals(null, h.first { it.key == RecordKey.K100 }.current)
    }

    @Test
    fun aRecordIsDrawnAsStepsHeldToToday() {
        val sets = listOf(RecordSet("longest_run", 10.0, d, 1), RecordSet("longest_run", 15.0, d.plusDays(10), 2, 10.0, d))
        val pts = stepPoints(sets, d.plusDays(20))
        assertEquals(listOf(d to 10.0, d.plusDays(9) to 10.0, d.plusDays(10) to 15.0, d.plusDays(20) to 15.0), pts)
    }
}
