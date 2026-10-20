package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.data.model.LogMealRow
import com.bioscan.fieldterminal.data.model.SupplementScheduleRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class LogDaysTest {

    private val today = LocalDate.of(2026, 10, 14)

    private fun entry(
        kind: LogEntryKind,
        date: LocalDate,
        hour: Int = 12,
        headline: String = kind.label,
        kcal: Double? = null,
        ml: Int? = null,
        minutes: Double? = null,
        id: Long = 0,
    ) = LogEntry(id, LogSource.Meal, kind, LocalDateTime.of(date, java.time.LocalTime.of(hour, 0)), headline, null, kcal, ml, minutes)

    private fun meals(date: LocalDate, n: Int) = (0 until n).map { entry(LogEntryKind.Food, date, 8 + it * 4, kcal = 600.0, id = it.toLong()) }

    private fun mark(day: LogDay, m: LogMark) = day.marks.first { it.mark == m }

    @Test
    fun daysRunNewestFirstAndAQuietDayInsideHistoryStillGetsARow() {
        val d = today.minusDays(3)
        val days = buildLogDays(meals(d, 3) + meals(today.minusDays(1), 3), today)
        // today, yesterday, 2 days ago (nothing logged), 3 days ago
        assertEquals(listOf(today, today.minusDays(1), today.minusDays(2), d), days.map { it.date })
        val quiet = days[2]
        assertTrue(quiet.entries.isEmpty())
        assertEquals("Nothing logged", quiet.summary)
        assertEquals(MarkState.MISSING, mark(quiet, LogMark.FOOD).state)
        assertTrue(quiet.hasGap)
    }

    @Test
    fun mealsArePartialUnderThreeOnAFinishedDayAndNeverFlaggedToday() {
        val y = today.minusDays(1)
        val days = buildLogDays(meals(y, 2) + meals(today, 1), today)
        assertEquals(MarkState.PARTIAL, mark(days.first { it.date == y }, LogMark.FOOD).state)
        val t = days.first { it.date == today }
        assertEquals(MarkState.PRESENT, mark(t, LogMark.FOOD).state)
        // Today with no drink / sleep / wellness logged yet is simply not flagged.
        assertEquals(MarkState.NONE, mark(t, LogMark.DRINK).state)
        assertFalse(t.hasGap)
    }

    @Test
    fun threeMealsIsAFullDay() {
        val y = today.minusDays(1)
        val day = buildLogDays(meals(y, 3), today).first { it.date == y }
        assertEquals(MarkState.PRESENT, mark(day, LogMark.FOOD).state)
    }

    @Test
    fun expectedKindsMissingOnFinishedDaysButExerciseAndOtherAreNever() {
        val y = today.minusDays(1)
        val day = buildLogDays(meals(y, 3), today).first { it.date == y }
        assertEquals(MarkState.MISSING, mark(day, LogMark.DRINK).state)
        assertEquals(MarkState.MISSING, mark(day, LogMark.SLEEP).state)
        assertEquals(MarkState.MISSING, mark(day, LogMark.WELL).state)
        assertEquals(MarkState.NONE, mark(day, LogMark.EX).state)
        assertEquals(MarkState.NONE, mark(day, LogMark.OTHER).state)
        assertEquals(MarkState.NONE, mark(day, LogMark.SUPP).state)
    }

    @Test
    fun supplementsAreMissingOnlyWhenADailyOneWasExpected() {
        val y = today.minusDays(1)
        val withRoster = buildLogDays(meals(y, 3), today, supplementExpected = { true }).first { it.date == y }
        assertEquals(MarkState.MISSING, mark(withRoster, LogMark.SUPP).state)
        val logged = buildLogDays(meals(y, 3) + entry(LogEntryKind.Supplement, y), today, supplementExpected = { true }).first { it.date == y }
        assertEquals(MarkState.PRESENT, mark(logged, LogMark.SUPP).state)
    }

    @Test
    fun daysOlderThanTheLoadedHistoryAreNotFlaggedAndEmptyOnesAreSkipped() {
        val old = today.minusDays(10)
        val coverageStart = today.minusDays(5)
        val coverage = mapOf(
            LogMark.FOOD to coverageStart, LogMark.DRINK to coverageStart, LogMark.SLEEP to coverageStart,
            LogMark.WELL to coverageStart, LogMark.SUPP to coverageStart,
        )
        val days = buildLogDays(meals(old, 1) + meals(today.minusDays(1), 3), today, coverage)
        val oldDay = days.first { it.date == old }
        assertEquals(MarkState.PRESENT, mark(oldDay, LogMark.FOOD).state) // logged, so shown, and 1 meal is not called partial
        assertFalse(oldDay.hasGap)
        // 7 days ago has no entries and is older than every mark's coverage: no row at all.
        assertTrue(days.none { it.date == today.minusDays(7) })
        // 4 days ago is inside coverage and empty: a real gap row.
        assertTrue(days.first { it.date == today.minusDays(4) }.hasGap)
    }

    @Test
    fun summaryListsOnlyWhatExists() {
        val y = today.minusDays(1)
        val entries = listOf(
            entry(LogEntryKind.Food, y, kcal = 700.0), entry(LogEntryKind.Food, y, 14, kcal = 650.0),
            entry(LogEntryKind.Drink, y, ml = 2400),
            entry(LogEntryKind.Supplement, y), entry(LogEntryKind.Supplement, y, 9),
            entry(LogEntryKind.Sleep, y, 7, minutes = 432.0),
            entry(LogEntryKind.Exercise, y, 18, headline = "Run · 6.2 km · 38m", minutes = 38.0),
            entry(LogEntryKind.Note, y),
        )
        assertEquals("2 meals · 1350 kcal · 2.4 L · 2 supps · 7h 12m · Run · 6.2 km · +1 other", daySummary(entries))
        assertEquals("Nothing logged", daySummary(emptyList()))
        assertEquals("1 meal", daySummary(listOf(entry(LogEntryKind.Food, y))))
    }

    @Test
    fun kindsMapToMarks() {
        assertEquals(LogMark.EX, LogEntryKind.Grip.mark)
        assertEquals(LogMark.OTHER, LogEntryKind.Stool.mark)
        assertEquals(LogMark.OTHER, LogEntryKind.Encounter.mark)
        assertEquals(LogMark.WELL, LogEntryKind.Wellness.mark)
        assertEquals(LogMark.FOOD, LogEntryKind.Food.mark)
    }

    @Test
    fun coverageStartsTheDayAfterTheOldestRowWhenALimitWasHit() {
        fun meal(id: Long, iso: String) = LogMealRow(id = id, loggedAt = iso)
        val rows = listOf(meal(1, "2026-10-10T12:00:00+00:00"), meal(2, "2026-10-12T12:00:00+00:00"), meal(3, "2026-10-13T12:00:00+00:00"))
        val atLimit = logCoverage(rows, emptyList(), emptyList(), emptyList(), emptyList(), limit = 3)
        assertEquals(LocalDate.of(2026, 10, 11), atLimit[LogMark.FOOD])
        val belowLimit = logCoverage(rows, emptyList(), emptyList(), emptyList(), emptyList(), limit = 60)
        assertNull(belowLimit[LogMark.FOOD])
        assertNull(belowLimit[LogMark.DRINK])
    }

    @Test
    fun aWindowedFeedIsKnownFromItsFirstDayOnly() {
        fun meal(id: Long, iso: String) = LogMealRow(id = id, loggedAt = iso)
        val since = LocalDate.of(2026, 9, 22)
        val rows = listOf(meal(1, "2026-09-25T12:00:00+00:00"), meal(2, "2026-10-13T12:00:00+00:00"))
        val windowed = logCoverage(rows, emptyList(), emptyList(), emptyList(), emptyList(), limit = 1000, since = since)
        // Every source starts at the window, empty ones included: an older day is never called missing.
        assertEquals(since, windowed[LogMark.FOOD])
        assertEquals(since, windowed[LogMark.SUPP])
        // A source that hit its cap inside the window is known only from the day after its oldest row.
        val capped = logCoverage(rows, emptyList(), emptyList(), emptyList(), emptyList(), limit = 2, since = since)
        assertEquals(LocalDate.of(2026, 9, 26), capped[LogMark.FOOD])
        // Days before the window get no row; inside it an empty finished day is a gap.
        val today = LocalDate.of(2026, 10, 14)
        val days = buildLogDays(emptyList(), today, windowed, from = since)
        assertEquals(today, days.first().date)
        assertEquals(since, days.last().date)
        assertTrue(days.drop(1).all { it.hasGap })
    }

    @Test
    fun onlyDailySupplementsWithAKnownRangeAreExpected() {
        val daily = SupplementScheduleRow(1, "active", "2026-09-01", null, null)
        val ended = SupplementScheduleRow(2, "ended", "2026-09-01", "2026-09-30", null)
        val interval = SupplementScheduleRow(3, "active", "2026-09-01", null, 3)
        val noStart = SupplementScheduleRow(4, "active", null, null, null)
        val d = LocalDate.of(2026, 10, 5)
        assertTrue(supplementExpectedOn(listOf(daily), d))
        assertFalse(supplementExpectedOn(listOf(daily), LocalDate.of(2026, 8, 31)))
        assertTrue(supplementExpectedOn(listOf(ended), LocalDate.of(2026, 9, 15)))
        assertFalse(supplementExpectedOn(listOf(ended), d))
        assertFalse(supplementExpectedOn(listOf(interval), d))
        assertFalse(supplementExpectedOn(listOf(noStart), d))
    }
}
