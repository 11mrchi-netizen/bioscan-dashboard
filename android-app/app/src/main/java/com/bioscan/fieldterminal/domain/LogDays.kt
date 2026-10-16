package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.data.model.LogHydrationRow
import com.bioscan.fieldterminal.data.model.LogMealRow
import com.bioscan.fieldterminal.data.model.LogSleepRow
import com.bioscan.fieldterminal.data.model.LogSupplementTakenRow
import com.bioscan.fieldterminal.data.model.LogWellbeingRow
import com.bioscan.fieldterminal.data.model.SupplementScheduleRow
import java.time.LocalDate
import java.time.temporal.ChronoUnit

// Log tab, collapsed days. Everything here is pure: grouping entries by day, the per-day marks (what is logged, what is
// missing), the one-line summary, and which days can honestly be called incomplete. The screen only draws the result.

// The six things a day is read by, always in this order so gaps line up down the list, plus a catch-all.
enum class LogMark(val label: String) { FOOD("FOOD"), DRINK("DRINK"), SUPP("SUPP"), SLEEP("SLEEP"), WELL("WELL"), EX("EX"), OTHER("OTHER") }

val LogEntryKind.mark: LogMark
    get() = when (this) {
        LogEntryKind.Food -> LogMark.FOOD
        LogEntryKind.Drink -> LogMark.DRINK
        LogEntryKind.Supplement -> LogMark.SUPP
        LogEntryKind.Sleep -> LogMark.SLEEP
        LogEntryKind.Wellness -> LogMark.WELL
        LogEntryKind.Exercise, LogEntryKind.Grip -> LogMark.EX
        else -> LogMark.OTHER
    }

enum class MarkState {
    // Logged (for FOOD: at least a full day's meals, or today).
    PRESENT,
    // FOOD only: some meals but fewer than a complete day on a finished day.
    PARTIAL,
    // Expected for a finished day inside the loaded history, and nothing is logged.
    MISSING,
    // Nothing logged and nothing to say about it: not an expected kind, today, or older than the loaded history.
    NONE,
}

data class DayMark(val mark: LogMark, val count: Int, val state: MarkState)

data class LogDay(
    val date: LocalDate,
    val entries: List<LogEntry>,
    val byCategory: Map<LogCategory, List<LogEntry>>,
    val marks: List<DayMark>,
    val summary: String,
) {
    val hasGap: Boolean get() = marks.any { it.state == MarkState.MISSING || it.state == MarkState.PARTIAL }
}

// 3+ meals is a complete day, the same rule NutritionEvaluation.classifyNutritionDay uses (COMPLETE_MEALS_MIN).
const val LOG_COMPLETE_MEALS = 3

private val EXPECTED_DAILY = setOf(LogMark.FOOD, LogMark.DRINK, LogMark.SLEEP, LogMark.WELL)

// Per-source history coverage. The feed fetches each source's newest rows only, so a source that returned its row limit is
// only known back to its oldest row: that day may be cut mid-day, so coverage starts the day after. A source that returned
// fewer rows than the limit is complete (null = the whole history). Only marks that can be flagged missing matter here.
fun logCoverage(
    meals: List<LogMealRow>,
    hydration: List<LogHydrationRow>,
    supplementsTaken: List<LogSupplementTakenRow>,
    sleep: List<LogSleepRow>,
    wellbeing: List<LogWellbeingRow>,
    limit: Int,
): Map<LogMark, LocalDate?> {
    fun start(dates: List<LocalDate>): LocalDate? = if (dates.size >= limit) dates.minOrNull()?.plusDays(1) else null
    fun parsed(iso: String) = runCatching { LocalDate.parse(iso) }.getOrNull()
    return mapOf(
        LogMark.FOOD to start(meals.map { parseTimestamp(it.loggedAt).toLocalDate() }),
        LogMark.DRINK to start(hydration.mapNotNull { parsed(it.date) }),
        LogMark.SUPP to start(supplementsTaken.map { parseTimestamp(it.takenAt).toLocalDate() }),
        LogMark.SLEEP to start(sleep.mapNotNull { parsed(it.date) }),
        LogMark.WELL to start(wellbeing.mapNotNull { parsed(it.date) }),
    )
}

// A supplement counts as expected on a day only when it is a daily one (no interval) whose known date range covers the day.
// Interval and as-needed supplements are never expected on a given day: whether one was due depends on the last take.
fun supplementExpectedOn(schedule: List<SupplementScheduleRow>, date: LocalDate): Boolean = schedule.any { s ->
    val interval = s.everyNDays
    if (interval != null && interval > 1) return@any false
    val start = s.startDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return@any false
    val end = s.endDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    val open = end == null && s.status.equals("active", ignoreCase = true)
    !date.isBefore(start) && ((end != null && !date.isAfter(end)) || open)
}

// Days newest first, from the later of today and the newest entry back to the oldest entry. A day with nothing logged still
// gets a row (that is the point), but only where at least one expected mark has history covering it; older than that, only
// days that actually have entries appear.
fun buildLogDays(
    entries: List<LogEntry>,
    today: LocalDate,
    coverage: Map<LogMark, LocalDate?> = emptyMap(),
    supplementExpected: (LocalDate) -> Boolean = { false },
): List<LogDay> {
    val byDate = entries.groupBy { it.timestamp.toLocalDate() }
    val newest = maxOf(today, byDate.keys.maxOrNull() ?: today)
    val oldest = byDate.keys.minOrNull() ?: today

    fun covered(mark: LogMark, date: LocalDate): Boolean = coverage[mark]?.let { !date.isBefore(it) } ?: true
    val days = mutableListOf<LogDay>()
    val span = ChronoUnit.DAYS.between(oldest, newest)
    for (i in 0..span) {
        val date = newest.minusDays(i)
        val dayEntries = byDate[date].orEmpty().sortedByDescending { it.timestamp }
        val anyExpectedCovered = EXPECTED_DAILY.any { covered(it, date) }
        if (dayEntries.isEmpty() && !anyExpectedCovered) continue
        val finished = date.isBefore(today)
        val counts = dayEntries.groupingBy { it.kind.mark }.eachCount()
        val marks = LogMark.entries.map { mark ->
            val n = counts[mark] ?: 0
            val flaggable = finished && covered(mark, date)
            val expected = mark in EXPECTED_DAILY || (mark == LogMark.SUPP && supplementExpected(date))
            val state = when {
                n > 0 && mark == LogMark.FOOD && n < LOG_COMPLETE_MEALS && flaggable -> MarkState.PARTIAL
                n > 0 -> MarkState.PRESENT
                flaggable && expected -> MarkState.MISSING
                else -> MarkState.NONE
            }
            DayMark(mark, n, state)
        }
        days += LogDay(date, dayEntries, dayEntries.groupBy { it.kind.category }, marks, daySummary(dayEntries))
    }
    return days
}

// "3 meals · 2150 kcal · 2.4 L · 5 supps · 7h 12m · Run · 6.2 km" -- only what exists, in the marks' order.
fun daySummary(entries: List<LogEntry>): String {
    if (entries.isEmpty()) return "Nothing logged"
    val parts = mutableListOf<String>()
    val meals = entries.filter { it.kind == LogEntryKind.Food }
    if (meals.isNotEmpty()) {
        val kcal = meals.mapNotNull { it.kcal }.sum()
        parts += "${meals.size} ${if (meals.size == 1) "meal" else "meals"}" + if (kcal > 0) " · ${kcal.toInt()} kcal" else ""
    }
    val ml = entries.filter { it.kind == LogEntryKind.Drink }.mapNotNull { it.ml }.sum()
    if (ml > 0) parts += if (ml >= 1000) "%.1f L".format(ml / 1000.0) else "$ml ml"
    val supps = entries.count { it.kind == LogEntryKind.Supplement }
    if (supps > 0) parts += "$supps ${if (supps == 1) "supp" else "supps"}"
    entries.firstOrNull { it.kind == LogEntryKind.Sleep }?.minutes?.let { parts += formatHoursMinutes(it) }
    val workouts = entries.filter { it.kind == LogEntryKind.Exercise }
    if (workouts.isNotEmpty()) {
        // First two " · " parts of the headline: type and distance when there is one.
        val first = workouts.first().headline.split(" · ").take(2).joinToString(" · ")
        parts += first + if (workouts.size > 1) " +${workouts.size - 1}" else ""
    }
    if (entries.any { it.kind == LogEntryKind.Wellness }) parts += "check-in"
    val other = entries.count { it.kind.mark == LogMark.OTHER || it.kind == LogEntryKind.Grip }
    if (other > 0) parts += "+$other other"
    return parts.joinToString(" · ")
}

private fun formatHoursMinutes(totalMinutes: Double): String {
    val h = (totalMinutes / 60).toInt()
    val m = (totalMinutes % 60).toInt()
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
