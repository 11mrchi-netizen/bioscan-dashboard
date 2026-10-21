package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.data.model.SupplementRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class SupplementsTodayTest {

    private val today = LocalDate.of(2026, 10, 14)

    private fun supp(id: Long, bucket: String, everyNDays: Int? = null, status: String = "active", endDate: String? = null) =
        SupplementRow(id = id, name = "S$id", dose = "1", timeOfDay = bucket, status = status, endDate = endDate, everyNDays = everyNDays)

    private val roster = listOf(
        supp(1, "morning"), supp(2, "morning"),
        supp(3, "afternoon"),
        supp(4, "night"), supp(5, "night"), supp(6, "night"),
        supp(7, "as-needed"),
    )

    @Test
    fun bucketsFollowTheClockAndTheSmallHoursHaveNone() {
        assertNull(supplementBucketAt(LocalTime.of(2, 30)))
        assertEquals("morning", supplementBucketAt(LocalTime.of(7, 0)))
        assertEquals("afternoon", supplementBucketAt(LocalTime.of(13, 0)))
        assertEquals("night", supplementBucketAt(LocalTime.of(21, 0)))
    }

    @Test
    fun theCurrentBundleIsSuggestedWhileItIsDue() {
        val s = supplementsToday(roster, emptyMap(), today, LocalTime.of(21, 0))
        assertEquals(mapOf("morning" to 2, "afternoon" to 1, "night" to 3), s.dueByBucket)
        assertEquals(6, s.scheduledCount)
        assertEquals("night", s.suggestedBucket)
        assertEquals("NIGHT STACK · 3 DUE", supplementsTodayLine(s))
    }

    @Test
    fun aMissedEarlierBundleIsSuggestedButALaterOneNeverIs() {
        // Afternoon taken, morning missed: at 15:00 the morning stack is still the one to log.
        val taken = mapOf(3L to today)
        val afternoon = supplementsToday(roster, taken, today, LocalTime.of(15, 0))
        assertEquals("morning", afternoon.suggestedBucket)
        assertEquals("night", afternoon.nextBucket)

        // Morning taken at 09:00: nothing due now, the next bundle is named but not ticked.
        val morningDone = supplementsToday(roster, mapOf(1L to today, 2L to today), today, LocalTime.of(9, 0))
        assertNull(morningDone.suggestedBucket)
        assertEquals("NEXT: AFTERNOON · 1", supplementsTodayLine(morningDone))
    }

    @Test
    fun afterMidnightNothingIsTickedAndTheMorningIsNext() {
        val s = supplementsToday(roster, emptyMap(), today, LocalTime.of(1, 0))
        assertNull(s.suggestedBucket)
        assertEquals("NEXT: MORNING · 2", supplementsTodayLine(s))
    }

    @Test
    fun intervalSupplementsCountOnlyOnTheirDueDays() {
        val every3 = listOf(supp(10, "morning", everyNDays = 3))
        assertEquals(emptyMap<String, Int>(), supplementsToday(every3, mapOf(10L to today.minusDays(1)), today, LocalTime.of(8, 0)).dueByBucket)
        assertEquals(mapOf("morning" to 1), supplementsToday(every3, mapOf(10L to today.minusDays(3)), today, LocalTime.of(8, 0)).dueByBucket)
    }

    @Test
    fun allTakenIsToldApartFromNothingScheduled() {
        val allTaken = (1L..6L).associateWith { today }
        assertEquals("ALL TAKEN TODAY", supplementsTodayLine(supplementsToday(roster, allTaken, today, LocalTime.of(22, 0))))
        val onlyAsNeeded = listOf(supp(7, "as-needed"), supp(8, "night", status = "ended", endDate = "2026-10-01"))
        assertEquals("NO SUPPLEMENTS SET", supplementsTodayLine(supplementsToday(onlyAsNeeded, emptyMap(), today, LocalTime.of(22, 0))))
    }
}
