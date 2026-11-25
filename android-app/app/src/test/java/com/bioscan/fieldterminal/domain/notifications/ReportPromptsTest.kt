package com.bioscan.fieldterminal.domain.notifications

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class ReportPromptsTest {
    private val week = ReportBrief("week:2026-11-16", "week", "2026-11-16", "2026-11-22", better = 2, worse = 1, notFollowed = 0, firstSuggestion = "Sleep averaged 6.3 h.")
    private val month = ReportBrief("month:2026-11", "month", "2026-11-01", "2026-11-30", better = 0, worse = 0, notFollowed = 0, firstSuggestion = null)
    private val monday = LocalDate.parse("2026-11-23")

    @Test fun announcesTheNewReportOnceInTheDay() {
        assertEquals(week, reportToAnnounce(listOf(week), emptySet(), monday, LocalTime.of(9, 0)))
        assertNull(reportToAnnounce(listOf(week), setOf(reportDedupeKey(week.key)), monday, LocalTime.of(9, 0)))
        assertNull("not before 08:00", reportToAnnounce(listOf(week), emptySet(), monday, LocalTime.of(5, 0)))
        assertNull("not late at night", reportToAnnounce(listOf(week), emptySet(), monday, LocalTime.of(22, 0)))
    }

    @Test fun anOldReportFilledInLaterIsNotNews() {
        assertNull(reportToAnnounce(listOf(week), emptySet(), LocalDate.parse("2026-12-05"), LocalTime.of(9, 0)))
    }

    @Test fun aWeekAndAMonthEndingTogetherComeWeekFirst() {
        val w = week.copy(key = "week:2026-11-24", start = "2026-11-24", end = "2026-11-30")
        val today = LocalDate.parse("2026-12-01")
        assertEquals(w, reportToAnnounce(listOf(month, w), emptySet(), today, LocalTime.of(9, 0)))
        assertEquals(month, reportToAnnounce(listOf(month, w), setOf(reportDedupeKey(w.key)), today, LocalTime.of(9, 0)))
    }

    @Test fun wordsAndAddress() {
        assertEquals("Week 16–22 Nov report", reportTitle(week))
        assertEquals("November 2026 report", reportTitle(month))
        assertEquals("Week 26 Oct–1 Nov report", reportTitle(week.copy(start = "2026-10-26", end = "2026-11-01")))
        assertEquals("2 better, 1 worse than the week before. Sleep averaged 6.3 h.", reportBody(week))
        assertEquals("Everything steady against the period before.", reportBody(month))
        assertEquals("https://11mrchi-netizen.github.io/bioscan-dashboard/#/lab/reports?key=week%3A2026-11-16", reportWebUrl(week.key))
        assertEquals("https://11mrchi-netizen.github.io/bioscan-dashboard/#/lab/reports", reportWebUrl(null))
    }
}
