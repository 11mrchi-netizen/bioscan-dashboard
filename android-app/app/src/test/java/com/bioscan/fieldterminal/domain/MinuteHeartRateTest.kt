package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.data.model.HrPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MinuteHeartRateTest {
    @Test
    fun `the band's no-reading markers and impossible values are not charted`() {
        val raw = listOf(HrPoint(0, 255), HrPoint(1, 254), HrPoint(2, 58), HrPoint(3, 12), HrPoint(4, 230), HrPoint(5, 61))
        assertEquals(listOf(HrPoint(2, 58), HrPoint(5, 61)), plausibleMinutes(raw))
    }

    @Test
    fun `minutes come back in order, once each, inside the day`() {
        val raw = listOf(HrPoint(10, 70), HrPoint(3, 60), HrPoint(10, 71), HrPoint(1440, 65), HrPoint(-1, 65))
        assertEquals(listOf(HrPoint(3, 60), HrPoint(10, 70)), plausibleMinutes(raw))
    }

    @Test
    fun `a gap longer than five minutes breaks the line`() {
        val pts = listOf(HrPoint(0, 60), HrPoint(5, 61), HrPoint(11, 62), HrPoint(12, 63), HrPoint(600, 80))
        val segs = minuteSegments(pts)
        assertEquals(listOf(listOf(0, 5), listOf(11, 12), listOf(600)), segs.map { s -> s.map { it.offsetMinutes } })
    }

    @Test
    fun `the summary counts worn minutes and spans the readings`() {
        val s = minuteHrSummary(listOf(HrPoint(0, 50), HrPoint(1, 70), HrPoint(2, 61)))!!
        assertEquals(MinuteHrSummary(wornMinutes = 3, low = 50, high = 70, average = 60), s)
        assertNull(minuteHrSummary(emptyList()))
        assertEquals("06:42", minuteOfDayLabel(402))
    }
}
