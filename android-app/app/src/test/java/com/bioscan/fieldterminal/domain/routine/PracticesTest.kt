package com.bioscan.fieldterminal.domain.routine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class PracticesTest {
    private val tue = LocalDate.of(2026, 11, 10)
    private val meditation = RoutineActivity(1, "Meditation", ActivityKind.PRACTICE, ActivityCategory.MIND, 10, (1..7).toSet(), LocalTime.of(7, 0), remind = true)
    private val sauna = RoutineActivity(2, "Sauna", ActivityKind.PRACTICE, ActivityCategory.RECOVERY, 20, setOf(2, 4), LocalTime.of(19, 0), remind = true)
    private val stretch = RoutineActivity(3, "Stretching", ActivityKind.PRACTICE, ActivityCategory.BODY, 15)
    private val tv = RoutineActivity(4, "TV", ActivityKind.PASTIME, ActivityCategory.SCREEN)

    private fun done(id: Long, d: LocalDate, min: Double = 10.0) = RoutineLog(id * 1000 + d.toEpochDay(), id, d, null, null, min, LogStatus.DONE, LogSource.MANUAL)

    @Test
    fun todayShowsWhatIsScheduledInTimeOrderAndTheRestAsAnytime() {
        val list = practicesToday(listOf(sauna, meditation, stretch, tv), emptyList(), tue, LocalTime.of(8, 0))
        assertEquals(listOf("Meditation", "Sauna", "Stretching"), list.map { it.activity.name })
        assertEquals(listOf(PracticeState.DUE, PracticeState.LATER, PracticeState.ANYTIME), list.map { it.state })
        // Wednesday: no sauna.
        assertTrue(practicesToday(listOf(sauna), emptyList(), tue.plusDays(1), LocalTime.NOON).isEmpty())
    }

    @Test
    fun aLogMakesItDoneAndASkipSkipped() {
        val skip = RoutineLog(9, 2, tue, null, null, null, LogStatus.SKIPPED, LogSource.REMINDER)
        val list = practicesToday(listOf(meditation, sauna), listOf(done(1, tue, 12.0), skip), tue, LocalTime.of(20, 0))
        assertEquals(PracticeState.DONE, list[0].state)
        assertEquals(12.0, list[0].minutesDone, 0.0)
        assertEquals(PracticeState.SKIPPED, list[1].state)
    }

    @Test
    fun aReminderFiresOnceInItsWindowAndNotOnceLogged() {
        val due = practicesDue(listOf(meditation, sauna), emptyList(), tue.atTime(6, 50), tue.atTime(7, 5))
        assertEquals(listOf(1L), due.map { it.id })
        assertTrue(practicesDue(listOf(meditation), listOf(done(1, tue)), tue.atTime(6, 50), tue.atTime(7, 5)).isEmpty())
        assertTrue(practicesDue(listOf(meditation.copy(remind = false)), emptyList(), tue.atTime(6, 50), tue.atTime(7, 5)).isEmpty())
    }

    @Test
    fun adherenceCountsScheduledDaysAndTodayOnlyOnceDone() {
        val logs = (1..5).map { done(1, tue.minusDays(it.toLong())) } + done(1, tue.minusDays(7))
        val a = adherence(meditation, logs, tue, days = 8)
        assertEquals(6, a.done)
        assertEquals(7, a.scheduled) // 8 days, today not done yet: 7
        assertEquals(5, a.streak) // yesterday back to 5 days ago; 6 days ago missed
        val s = adherence(sauna, listOf(done(2, tue), done(2, tue.minusDays(5))), tue, days = 14)
        assertEquals(2, s.streak)
    }

    @Test
    fun theSmallHoursBelongToTheEveningBefore() {
        assertEquals(tue, dayOfBlock(tue.plusDays(1).atTime(0, 40)))
        assertEquals(tue, dayOfBlock(tue.atTime(21, 0)))
    }

    @Test
    fun inferredTimeLeavesRunningToTheWatchAndWeekTimeAverages() {
        val inputs = InferredInputs(
            sessions = listOf(Triple("run", tue, 76.0)),
            legs = listOf(Triple("walking", tue, 12.0), Triple("running", tue, 65.0), Triple("on_bicycle", tue.minusDays(1), 14.0)),
            outdoorDaylight = mapOf(tue.minusDays(1) to 70.0),
            phoneByCategory = mapOf(tue.minusDays(1) to mapOf("video" to 184.0, "productivity" to 107.0)),
            sleepHours = mapOf(tue to 7.5),
        )
        val e = inferredTime(inputs)
        assertTrue(e.none { it.key == "trip:running" })
        assertEquals(setOf("session:run", "trip:walking", "trip:on_bicycle", "outdoor_daylight", "phone:video", "sleep"), e.map { it.key }.toSet())
        val logged = loggedTime(listOf(tv), listOf(RoutineLog(1, 4, tue.minusDays(1), null, null, 70.0, LogStatus.DONE, LogSource.TIMER)))
        val lines = weekTime(e + logged, tue)
        assertEquals("TV", lines.first().label)
        assertEquals(10.0, lines.first().dailyAverageMin, 1e-9)
        assertEquals(184.0 / 7, lines.first { it.key == "phone:video" }.dailyAverageMin, 1e-9)
        assertEquals(76.0, lines.first { it.key == "session:run" }.todayMin, 0.0)
    }

    @Test
    fun scheduleInWords() {
        assertEquals("Every day", scheduleWords((1..7).toSet()))
        assertEquals("Tue Thu", scheduleWords(setOf(4, 2)))
        assertEquals("1 h 05", minutesWords(65.0))
    }
}
