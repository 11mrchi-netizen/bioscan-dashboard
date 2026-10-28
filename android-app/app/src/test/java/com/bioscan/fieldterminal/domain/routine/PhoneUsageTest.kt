package com.bioscan.fieldterminal.domain.routine

import com.bioscan.fieldterminal.domain.routine.UsageEventType.APP_PAUSED
import com.bioscan.fieldterminal.domain.routine.UsageEventType.APP_RESUMED
import com.bioscan.fieldterminal.domain.routine.UsageEventType.SCREEN_OFF
import com.bioscan.fieldterminal.domain.routine.UsageEventType.SCREEN_ON
import com.bioscan.fieldterminal.domain.routine.UsageEventType.UNLOCK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// Routine intelligence R1: a day of phone use from usage events.
class PhoneUsageTest {
    private val zone = ZoneId.of("Asia/Taipei")
    private val d = LocalDate.of(2026, 10, 20)

    private fun t(day: Int, h: Int, m: Int = 0): Long =
        LocalDate.of(2026, 10, day).atTime(h, m).atZone(zone).toInstant().toEpochMilli()
    private fun i(day: Int, h: Int, m: Int = 0): Instant = Instant.ofEpochMilli(t(day, h, m))

    private fun use(day: Int, h: Int, m: Int, minutes: Int, pkg: String, unlock: Boolean = true): List<UsageEvent> {
        val on = t(day, h, m)
        val off = on + minutes * 60_000L
        return listOfNotNull(
            UsageEvent(on, SCREEN_ON),
            if (unlock) UsageEvent(on + 1_000, UNLOCK) else null,
            UsageEvent(on + 2_000, APP_RESUMED, pkg),
            UsageEvent(off - 1_000, APP_PAUSED, pkg),
            UsageEvent(off, SCREEN_OFF),
        )
    }

    @Test
    fun screenTimeUnlocksAndAppsForOneDay() {
        val events = use(20, 9, 0, 25, "chat") + use(20, 13, 0, 15, "video") + use(20, 13, 30, 15, "video")
        val day = summarisePhoneDay(d, zone, events, night = null, wakeOfDay = null)
        assertEquals(55.0, day.screenMin, 0.01)
        assertEquals(3, day.unlocks)
        assertEquals(i(20, 9).plusSeconds(1), day.firstUnlockAt)
        assertEquals(listOf("video", "chat"), day.apps.map { it.pkg })
        assertEquals(2, day.apps.first().launches)
        assertEquals(30.0 - 6.0 / 60, day.apps.first().foregroundMin, 0.001)
        assertNull(day.bedWindowMin)
        assertNull(day.nightUnlocks)
    }

    @Test
    fun theSmallHoursBelongToTheDayBefore() {
        // 02:00 on the 21st is still the 20th's evening; 05:00 on the 21st is the next day.
        val events = use(21, 2, 0, 10, "chat") + use(21, 5, 0, 10, "chat")
        val day = summarisePhoneDay(d, zone, events, night = null, wakeOfDay = null)
        assertEquals(10.0, day.screenMin, 0.01)
        assertEquals(1, day.unlocks)
    }

    @Test
    fun bedWindowNightUseAndLastUseBeforeSleep() {
        val night = SleepWindow(onset = i(20, 23, 30), wake = i(21, 7, 0))
        val events = use(20, 22, 0, 20, "news") + // before the hour before bed
            use(20, 23, 0, 40, "video") + // 23:00-23:40: 30 minutes in the bed hour, 10 after onset
            use(21, 3, 0, 5, "chat") + // a night unlock
            use(21, 6, 30, 10, "chat") // after 04:00 but before wake: night use, not the day's
        val day = summarisePhoneDay(d, zone, events, night, wakeOfDay = null)
        assertEquals(30.0, day.bedWindowMin!!, 0.01)
        assertEquals(10.0 + 5.0 + 10.0, day.nightScreenMin!!, 0.01)
        assertEquals(2, day.nightUnlocks)
        assertEquals(i(20, 23, 30), day.lastUseBeforeSleepAt)
        // the day's own totals stop at 04:00
        assertEquals(20.0 + 40.0 + 5.0, day.screenMin, 0.01)
        val video = day.apps.first { it.pkg == "video" }
        assertEquals(40.0 - 3.0 / 60, video.bedWindowMin!!, 0.01)
        assertEquals(40.0 - 3.0 / 60, video.after21Min, 0.01)
    }

    @Test
    fun firstUnlockIsTheFirstAfterWaking() {
        val events = use(20, 5, 0, 2, "chat") + use(20, 7, 15, 5, "chat")
        val day = summarisePhoneDay(d, zone, events, night = null, wakeOfDay = i(20, 7, 0))
        assertEquals(i(20, 7, 15).plusSeconds(1), day.firstUnlockAt)
    }

    @Test
    fun aScreenAlreadyOnAtTheStartCountsFromTheStart() {
        val start = t(20, 4)
        val events = listOf(UsageEvent(start + 5 * 60_000L, SCREEN_OFF))
        val day = summarisePhoneDay(d, zone, events, night = null, wakeOfDay = null)
        assertEquals(5.0, day.screenMin, 0.01)
    }

    @Test
    fun movingBetweenOneAppsScreensIsOneLaunchAndExcludedPackagesAreDropped() {
        val on = t(20, 10)
        val events = listOf(
            UsageEvent(on, SCREEN_ON),
            UsageEvent(on + 1_000, APP_RESUMED, "launcher"),
            UsageEvent(on + 2_000, APP_PAUSED, "launcher"),
            UsageEvent(on + 3_000, APP_RESUMED, "mail"),
            UsageEvent(on + 60_000, APP_PAUSED, "mail"),
            UsageEvent(on + 60_500, APP_RESUMED, "mail"),
            UsageEvent(on + 120_000, SCREEN_OFF),
        )
        val day = summarisePhoneDay(d, zone, events, night = null, wakeOfDay = null, excludedPackages = setOf("launcher"))
        assertEquals(listOf("mail"), day.apps.map { it.pkg })
        assertEquals(1, day.apps.single().launches)
        assertEquals((120_000 - 3_000 - 500) / 60_000.0, day.apps.single().foregroundMin, 0.001)
    }

    @Test
    fun longestPhoneFreeStretchBetweenEightAndTen() {
        val events = use(20, 9, 0, 60, "mail") + use(20, 16, 0, 60, "mail")
        val day = summarisePhoneDay(d, zone, events, night = null, wakeOfDay = null)
        assertEquals(6 * 60.0, day.longestFreeMin!!, 0.01) // 10:00 to 16:00
    }

    @Test
    fun categoriesSumTheirApps() {
        val apps = listOf(AppDayUsage("a", 10.0, 1, null, 0.0), AppDayUsage("b", 5.0, 1, null, 0.0), AppDayUsage("c", 0.0, 1, null, 0.0))
        assertEquals(mapOf("social" to 15.0), minutesByCategory(apps) { if (it == "c") "game" else "social" })
    }
}
