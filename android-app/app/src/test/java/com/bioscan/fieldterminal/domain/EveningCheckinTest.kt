package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.data.model.NotificationKind
import com.bioscan.fieldterminal.domain.notifications.NotificationDestination
import com.bioscan.fieldterminal.domain.notifications.notificationDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

// Analysis review phase B: the evening check-in turns a quiet evening into a real zero.
class EveningCheckinTest {
    private val d = LocalDate.of(2026, 10, 14)

    @Test
    fun theSmallHoursBelongToTheEveningBefore() {
        assertEquals(d, checkinDateFor(LocalDateTime.of(2026, 10, 14, 22, 25)))
        assertEquals(d, checkinDateFor(LocalDateTime.of(2026, 10, 15, 0, 40)))
        assertEquals(d.plusDays(1), checkinDateFor(LocalDateTime.of(2026, 10, 15, 4, 0)))
    }

    @Test
    fun nothingElseAnswersEveryOpenExposureButNeverInventsStress() {
        val marked = EveningCheckin(d, drinks = 2).withNothingElse()
        assertEquals(2, marked.drinks)
        assertEquals(false, marked.caffeineAfter14)
        assertEquals(false, marked.lateMeal)
        assertEquals(null, marked.stress)
        assertTrue(marked.isComplete)
        assertFalse(eveningCheckinNeeded(marked))
    }

    @Test
    fun theReminderIsSkippedOnlyOnceTheEveningIsAnswered() {
        assertTrue(eveningCheckinNeeded(null))
        assertTrue(eveningCheckinNeeded(EveningCheckin(d, drinks = 0)))
        assertFalse(eveningCheckinNeeded(EveningCheckin(d, drinks = 0, caffeineAfter14 = true, lateMeal = false)))
    }

    @Test
    fun summaryReadsLikeASentence() {
        assertEquals("2 drinks · no caffeine after 14:00 · no late meal · stress 3",
            EveningCheckin(d, drinks = 2, caffeineAfter14 = false, lateMeal = false, stress = 3).summary())
        assertEquals("no drinks · caffeine after 14:00 · ate after 21:00",
            EveningCheckin(d, drinks = 0, caffeineAfter14 = true, lateMeal = true).summary())
        assertEquals("nothing answered yet", EveningCheckin(d).summary())
    }

    @Test
    fun theReminderOpensTheCheckIn() {
        assertEquals(NotificationDestination.CHECKIN, notificationDestination(NotificationKind.EVENING_CHECKIN))
        assertTrue(NotificationKind.EVENING_CHECKIN in NotificationKind.ALL)
        assertEquals(java.time.LocalTime.of(22, 25), EVENING_CHECKIN_TIME)
    }
}

// Analysis review phase B: overnight readings keyed to the morning their night ends on.
class NightDateTest {
    private val zone = java.time.ZoneId.of("Asia/Singapore")
    private fun at(day: Int, hour: Int) = LocalDateTime.of(2026, 10, day, hour, 0).atZone(zone).toInstant()

    @Test
    fun oneNightIsOneDate() {
        val values = listOf(TimedValue(at(13, 23), 50.0), TimedValue(at(14, 3), 60.0), TimedValue(at(14, 14), 40.0))
        val byNight = averageByNightDate(values, zone)
        assertEquals(mapOf(LocalDate.of(2026, 10, 14) to 50.0), byNight)
        // the old calendar-day grouping split that night across two dates
        assertEquals(2, averageByLocalDate(values, zone).size)
        assertEquals(LocalDate.of(2026, 10, 15), nightDate(at(14, 18), zone))
    }
}
