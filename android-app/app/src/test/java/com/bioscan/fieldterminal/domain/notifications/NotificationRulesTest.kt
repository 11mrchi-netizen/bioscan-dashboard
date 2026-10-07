package com.bioscan.fieldterminal.domain.notifications

import com.bioscan.fieldterminal.data.model.NotificationKind
import com.bioscan.fieldterminal.data.model.NotificationLimits
import com.bioscan.fieldterminal.data.model.NotificationPrefsRow
import com.bioscan.fieldterminal.data.model.NotificationRuleRow
import com.bioscan.fieldterminal.data.model.NotificationSchedule
import com.bioscan.fieldterminal.data.model.SupplementRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class NotificationRulesTest {

    // 2026-10-05 is a Monday.
    private val monday = LocalDate.of(2026, 10, 5)
    private fun at(h: Int, m: Int = 0, date: LocalDate = monday) = LocalDateTime.of(date, java.time.LocalTime.of(h, m))

    private val prefs = NotificationPrefsRow()

    private fun checkin(times: List<String>, days: List<Int> = listOf(1, 2, 3, 4, 5, 6, 7), enabled: Boolean = true) =
        NotificationRuleRow(
            kind = NotificationKind.CHECKIN_MORNING,
            enabled = enabled,
            schedule = NotificationSchedule(times = times, days = days),
        )

    // -------------------------------------------------------------------
    // nextFireTime
    // -------------------------------------------------------------------

    @Test
    fun picksNextTimeLaterToday() {
        val rule = checkin(listOf("09:00", "15:00"))
        assertEquals(at(15), nextFireTime(rule, prefs, at(10)))
    }

    @Test
    fun rollsToTomorrowWhenTodaysTimesPassed() {
        val rule = checkin(listOf("09:00"))
        assertEquals(at(9, date = monday.plusDays(1)), nextFireTime(rule, prefs, at(10)))
    }

    @Test
    fun exactlyNowDoesNotRefire() {
        val rule = checkin(listOf("09:00"))
        assertEquals(at(9, date = monday.plusDays(1)), nextFireTime(rule, prefs, at(9)))
    }

    @Test
    fun skipsDaysNotInSchedule() {
        // Only Wednesday (3): from Monday morning the next fire is Wednesday.
        val rule = checkin(listOf("09:00"), days = listOf(3))
        assertEquals(at(9, date = monday.plusDays(2)), nextFireTime(rule, prefs, at(8)))
    }

    @Test
    fun supplementBucketsUseTheirOwnTimes() {
        val rule = NotificationRuleRow(
            kind = NotificationKind.SUPPLEMENT_DUE,
            schedule = NotificationSchedule(buckets = mapOf("night" to "21:00", "morning" to "08:00")),
        )
        assertEquals(at(8), nextFireTime(rule, prefs, at(7)))
        assertEquals(at(21), nextFireTime(rule, prefs, at(12)))
    }

    @Test
    fun disabledRuleOrMasterOffNeverFires() {
        assertNull(nextFireTime(checkin(listOf("09:00"), enabled = false), prefs, at(8)))
        assertNull(nextFireTime(checkin(listOf("09:00")), prefs.copy(masterEnabled = false), at(8)))
        assertNull(nextFireTime(checkin(emptyList()), prefs, at(8)))
    }

    // -------------------------------------------------------------------
    // quiet hours (default window 23:00..07:00, crosses midnight)
    // -------------------------------------------------------------------

    @Test
    fun crossMidnightWindowMembership() {
        fun inQ(h: Int, m: Int = 0) = isInQuietWindow(java.time.LocalTime.of(h, m), prefs)
        assertTrue(inQ(23))
        assertTrue(inQ(2))
        assertTrue(inQ(6, 59))
        assertFalse(inQ(7))      // quietEnd itself is outside
        assertFalse(inQ(12))
        assertFalse(inQ(22, 59))
    }

    @Test
    fun sameDayWindowMembership() {
        val p = prefs.copy(quietStart = "13:00", quietEnd = "15:00")
        assertTrue(isInQuietWindow(java.time.LocalTime.of(13, 0), p))
        assertTrue(isInQuietWindow(java.time.LocalTime.of(14, 30), p))
        assertFalse(isInQuietWindow(java.time.LocalTime.of(15, 0), p))
        assertFalse(isInQuietWindow(java.time.LocalTime.of(2, 0), p))
    }

    @Test
    fun disabledOrEmptyWindowIsNeverQuiet() {
        assertFalse(isInQuietWindow(java.time.LocalTime.of(2, 0), prefs.copy(quietEnabled = false)))
        assertFalse(isInQuietWindow(java.time.LocalTime.of(2, 0), prefs.copy(quietStart = "22:00", quietEnd = "22:00")))
    }

    @Test
    fun outsideWindowIsUnchanged() {
        assertEquals(at(12), resolveQuietHours(at(12), prefs))
    }

    @Test
    fun eveningSideOfWindowHoldsUntilTomorrowMorning() {
        assertEquals(at(7, date = monday.plusDays(1)), resolveQuietHours(at(23, 30), prefs))
    }

    @Test
    fun morningSideOfWindowHoldsUntilSameDayEnd() {
        assertEquals(at(7), resolveQuietHours(at(3), prefs))
    }

    @Test
    fun sameDayWindowHoldsUntilItsEnd() {
        val p = prefs.copy(quietStart = "13:00", quietEnd = "15:00")
        assertEquals(at(15), resolveQuietHours(at(14), p))
    }

    @Test
    fun reminderInsideQuietHoursFiresAtWindowEndNotDropped() {
        // 23:30 daily check-in: held until 07:00 the next day.
        val rule = checkin(listOf("23:30"))
        assertEquals(at(7, date = monday.plusDays(1)), nextFireTime(rule, prefs, at(20)))
        // With quiet hours switched off it fires when scheduled.
        assertEquals(at(23, 30), nextFireTime(rule, prefs.copy(quietEnabled = false), at(20)))
    }

    // -------------------------------------------------------------------
    // withinLimits
    // -------------------------------------------------------------------

    private val rule = checkin(listOf("09:00")).copy(limits = NotificationLimits(maxPerDay = 2, minGapMin = 120, snoozeMin = 30))

    @Test
    fun emptyDayIsWithinLimits() {
        assertTrue(withinLimits(rule, prefs, at(9), emptyList()))
    }

    @Test
    fun perRuleMaxPerDayBlocks() {
        val shown = listOf(ShownEntry(rule.kind, at(1)), ShownEntry(rule.kind, at(5)))
        assertFalse(withinLimits(rule, prefs, at(20), shown))
    }

    @Test
    fun globalDailyCapBlocksAcrossRules() {
        val shown = List(6) { ShownEntry(NotificationKind.SUPPLEMENT_DUE, at(1 + it)) }
        assertFalse(withinLimits(rule, prefs.copy(dailyCap = 6), at(20), shown))
    }

    @Test
    fun minGapBlocksUnlessSnoozeRefire() {
        val shown = listOf(ShownEntry(rule.kind, at(9)))
        assertFalse(withinLimits(rule, prefs, at(10), shown))
        assertTrue(withinLimits(rule, prefs, at(10), shown, isSnoozeRefire = true))
        assertTrue(withinLimits(rule, prefs, at(11, 30), shown))
    }

    // -------------------------------------------------------------------
    // supplementsDueForBucket
    // -------------------------------------------------------------------

    private fun supp(
        id: Long,
        name: String,
        timeOfDay: String = "morning",
        status: String = "active",
        endDate: String? = null,
        everyNDays: Int? = null,
    ) = SupplementRow(id = id, name = name, dose = "1", timeOfDay = timeOfDay, status = status, endDate = endDate, everyNDays = everyNDays)

    private fun due(
        list: List<SupplementRow>,
        lastTaken: Map<Long, LocalDate> = emptyMap(),
        takenToday: Set<Long> = emptySet(),
        skipIfLogged: Boolean = true,
    ) = supplementsDueForBucket(list, "morning", lastTaken, takenToday, monday, skipIfLogged).map { it.id }

    @Test
    fun onlyMatchingBucketCaseInsensitive() {
        val list = listOf(supp(1, "A", "MORNING"), supp(2, "B", "night"))
        assertEquals(listOf(1L), due(list))
    }

    @Test
    fun endedSupplementsNeverRemind() {
        val list = listOf(
            supp(1, "ended", status = "ended"),
            supp(2, "past end date", endDate = monday.minusDays(1).toString()),
            supp(3, "ends today", endDate = monday.toString()),
        )
        assertEquals(listOf(3L), due(list))
    }

    @Test
    fun intervalSupplementWaitsUntilDue() {
        val list = listOf(supp(1, "every 3 days", everyNDays = 3))
        assertEquals(emptyList<Long>(), due(list, lastTaken = mapOf(1L to monday.minusDays(1))))
        assertEquals(listOf(1L), due(list, lastTaken = mapOf(1L to monday.minusDays(3))))
    }

    @Test
    fun alreadyTakenTodayIsSkippedOnlyWhenConfigured() {
        val list = listOf(supp(1, "A"), supp(2, "B"))
        assertEquals(listOf(2L), due(list, takenToday = setOf(1L)))
        assertEquals(listOf(1L, 2L), due(list, takenToday = setOf(1L), skipIfLogged = false))
    }

    // -------------------------------------------------------------------
    // checkinNeeded
    // -------------------------------------------------------------------

    @Test
    fun checkinOnlyNeededWhenItsFieldIsEmpty() {
        assertTrue(checkinNeeded(NotificationKind.CHECKIN_MORNING, morningWood = null, arousal = 7))
        assertFalse(checkinNeeded(NotificationKind.CHECKIN_MORNING, morningWood = 8, arousal = null))
        assertTrue(checkinNeeded(NotificationKind.CHECKIN_EVENING, morningWood = 8, arousal = null))
        assertFalse(checkinNeeded(NotificationKind.CHECKIN_EVENING, morningWood = null, arousal = 7))
        assertFalse(checkinNeeded(NotificationKind.SUPPLEMENT_DUE, null, null))
    }
}
