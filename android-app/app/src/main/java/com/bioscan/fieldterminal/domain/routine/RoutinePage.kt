package com.bioscan.fieldterminal.domain.routine

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.sqrt

// ME › ROUTINE (docs/routine-intelligence/README.md, "ME › ROUTINE"): the long view of the routine. Pure.
//   - the 24-hour ribbon: one row a day, each 15-minute slot coloured by what took it (training first, then logged
//     time, sleep, on the move, work, elsewhere, home, and last not recorded);
//   - daily lines (time, places, phone, regularity) with their 28-day average; a day a stream did not record is missing,
//     never zero, and an average needs 7 recorded days;
//   - a practice's calendar, how it was kept per 4 weeks and its streaks;
//   - the last 28 days against the 28 before, in words. Descriptions of what was recorded, not findings.

// ---- The ribbon ---------------------------------------------------------------------------------------------------

/** Kinds of time, in the order they win a slot (a more specific place over home, should two visits overlap). */
enum class RibbonKind(val label: String) {
    TRAINING("Training"), LOGGED("Logged time"), ASLEEP("Asleep"), MOVE("On the move"),
    WORK("Work"), OUT("Elsewhere"), HOME("Home"), UNKNOWN("Not recorded"),
}

const val RIBBON_SLOT_MIN = 15
const val RIBBON_SLOTS = 24 * 60 / RIBBON_SLOT_MIN

data class RibbonInterval(val startMs: Long, val endMs: Long, val kind: RibbonKind)

/** A run of slots of one kind: [from] inclusive, [to] exclusive, 0..[RIBBON_SLOTS]. */
data class RibbonSpan(val from: Int, val to: Int, val kind: RibbonKind)

data class RibbonDay(val date: LocalDate, val spans: List<RibbonSpan>) {
    /** Share of the slots drawn that something recorded. */
    val recordedShare: Double get() {
        val all = spans.sumOf { it.to - it.from }
        return if (all == 0) 0.0 else spans.filter { it.kind != RibbonKind.UNKNOWN }.sumOf { it.to - it.from }.toDouble() / all
    }
}

/** The kind of place a visit is at, for the ribbon. */
fun ribbonKindOf(category: PlaceCategory?): RibbonKind = when (category) {
    PlaceCategory.HOME -> RibbonKind.HOME
    PlaceCategory.WORK -> RibbonKind.WORK
    else -> RibbonKind.OUT
}

/** The day [date] in slots (to [nowMs] for today; later slots are not drawn), each taken by the first kind that covers its middle. */
fun ribbonDay(date: LocalDate, zone: ZoneId, intervals: List<RibbonInterval>, nowMs: Long): RibbonDay {
    val dayStart = date.atStartOfDay(zone).toInstant().toEpochMilli()
    val slotMs = RIBBON_SLOT_MIN * 60_000L
    val near = intervals.filter { it.endMs > dayStart && it.startMs < dayStart + RIBBON_SLOTS * slotMs }
    val kinds = (0 until RIBBON_SLOTS).mapNotNull { i ->
        val mid = dayStart + i * slotMs + slotMs / 2
        if (mid > nowMs) return@mapNotNull null
        near.filter { mid >= it.startMs && mid < it.endMs }.minByOrNull { it.kind.ordinal }?.kind ?: RibbonKind.UNKNOWN
    }
    val spans = mutableListOf<RibbonSpan>()
    kinds.forEachIndexed { i, k ->
        val last = spans.lastOrNull()
        if (last != null && last.kind == k && last.to == i) spans[spans.size - 1] = last.copy(to = i + 1) else spans += RibbonSpan(i, i + 1, k)
    }
    return RibbonDay(date, spans)
}

/** Minutes of each kind across [days] (the ribbon's legend), recorded slots only. */
fun ribbonTotals(days: List<RibbonDay>): Map<RibbonKind, Double> =
    days.flatMap { it.spans }.groupBy { it.kind }.mapValues { (_, s) -> s.sumOf { (it.to - it.from) * RIBBON_SLOT_MIN.toDouble() } }

// ---- Daily lines ----------------------------------------------------------------------------------------------------

/** One measure by day: only the days its stream recorded have a value. */
data class DailyLine(val key: String, val label: String, val source: TimeSource, val series: Map<LocalDate, Double>) {
    /** The mean over the [days] days up to and including [through], from at least [minDays] recorded days. */
    fun average(through: LocalDate, days: Int = 28, minDays: Int = 7): Double? = windowMean(series, through, days, minDays)
    fun daysIn(through: LocalDate, days: Int = 28): Int = series.keys.count { !it.isAfter(through) && it.isAfter(through.minusDays(days.toLong())) }
}

fun windowMean(series: Map<LocalDate, Double>, through: LocalDate, days: Int = 28, minDays: Int = 7): Double? {
    val from = through.minusDays(days.toLong())
    val v = series.filterKeys { !it.isAfter(through) && it.isAfter(from) }.values
    return if (v.size < minDays) null else v.average()
}

/**
 * The time entries as daily lines over [from]..[through]: a day the line's stream recorded and that has no entry is 0
 * (no TV that day), a day it did not record has no value. [recorded] says which days each stream recorded.
 */
fun timeLines(entries: List<TimeEntry>, recorded: Map<TimeSource, Set<LocalDate>>, from: LocalDate, through: LocalDate): List<DailyLine> =
    entries.filter { !it.date.isBefore(from) && !it.date.isAfter(through) }.groupBy { it.key }.map { (key, e) ->
        val byDay = e.groupBy { it.date }.mapValues { (_, v) -> v.sumOf { it.minutes } }
        val days = recorded[e.first().source].orEmpty().filter { !it.isBefore(from) && !it.isAfter(through) }
        DailyLine(key, e.first().label, e.first().source, days.associateWith { byDay[it] ?: 0.0 })
    }.filter { it.series.values.any { v -> v > 0 } }

/** The days a stream recorded from [first] (its first record) to [through]: for logged time and the watch, every day since. */
fun everyDaySince(first: LocalDate?, through: LocalDate): Set<LocalDate> {
    if (first == null || first.isAfter(through)) return emptySet()
    return (0..ChronoUnit.DAYS.between(first, through)).map { first.plusDays(it) }.toSet()
}

/** Hours by kind of place, a line per category, on the days Locations recorded at least half of. */
fun placeLines(days: Map<LocalDate, Pair<Map<String, Double>, Int>>, minCoveragePct: Int = 50): List<DailyLine> {
    val covered = days.filterValues { it.second >= minCoveragePct }
    val keys = covered.values.flatMap { it.first.keys }.toSet() - setOf("unknown")
    return keys.map { k ->
        val label = PlaceCategory.of(k)?.label ?: if (k == "unlabelled") "Unnamed places" else k.replaceFirstChar { it.uppercase() }
        DailyLine("place:$k", label, TimeSource.LOCATION, covered.mapValues { (_, v) -> (v.first[k] ?: 0.0) / 60.0 })
    }.filter { it.series.values.any { v -> v > 0 } }
}

// ---- Practices: calendar and streaks --------------------------------------------------------------------------------

enum class CalendarState { DONE, SKIPPED, MISSED, OFF, AHEAD }

/** [weeks] whole weeks, Monday first, ending with the week of [through]; days after [through] are AHEAD. */
fun practiceCalendar(a: RoutineActivity, logs: List<RoutineLog>, through: LocalDate, weeks: Int = 12): List<Pair<LocalDate, CalendarState>> {
    val mine = logs.filter { it.activityId == a.id }.groupBy { it.date }
    val start = through.with(DayOfWeek.MONDAY).minusWeeks((weeks - 1).toLong())
    return (0 until weeks * 7).map { i ->
        val d = start.plusDays(i.toLong())
        val day = mine[d].orEmpty()
        d to when {
            d.isAfter(through) -> CalendarState.AHEAD
            day.any { it.status == LogStatus.DONE } -> CalendarState.DONE
            day.any { it.status == LogStatus.SKIPPED } -> CalendarState.SKIPPED
            a.scheduledOn(d) && d != through -> CalendarState.MISSED
            else -> CalendarState.OFF
        }
    }
}

/** How the practice was kept in each of the [blocks] 4-week blocks up to [through], oldest first. */
fun keptByFourWeeks(a: RoutineActivity, logs: List<RoutineLog>, through: LocalDate, blocks: Int = 3): List<Pair<LocalDate, Adherence>> =
    (blocks - 1 downTo 0).map { b ->
        val end = through.minusDays(28L * b)
        end to adherence(a, logs, end, days = 28)
    }

/** The longest run of scheduled days done in a row over [days] days (an unscheduled practice: days done in a row). */
fun bestStreak(a: RoutineActivity, logs: List<RoutineLog>, through: LocalDate, days: Int = 365): Int {
    val done = logs.filter { it.activityId == a.id && it.status == LogStatus.DONE }.map { it.date }.toSet()
    var best = 0
    var run = 0
    for (i in days - 1 downTo 0) {
        val d = through.minusDays(i.toLong())
        val counts = if (a.isScheduled()) a.scheduledOn(d) else true
        if (!counts) continue
        if (d in done) { run++; best = maxOf(best, run) } else if (d != through) run = 0
    }
    return best
}

/** The practices to list for the last [days]: every active one, and archived ones with something logged in the window. */
fun practicesInWindow(activities: List<RoutineActivity>, logs: List<RoutineLog>, through: LocalDate, days: Int = 28): List<RoutineActivity> {
    val from = through.minusDays(days.toLong())
    val logged = logs.filter { it.date.isAfter(from) && !it.date.isAfter(through) }.map { it.activityId }.toSet()
    return activities.filter { it.kind == ActivityKind.PRACTICE && (!it.archived || it.id in logged) }
}

// ---- The last 28 days against the 28 before -------------------------------------------------------------------------

data class WindowChange(val key: String, val label: String, val recent: Double, val before: Double) {
    val change: Double get() = recent - before
    val relative: Double? get() = if (before == 0.0) null else change / before
}

/** The mean of the [days] up to [through] and of the [days] before them, each from at least [minDays] recorded days. */
fun compareWindows(line: DailyLine, through: LocalDate, days: Int = 28, minDays: Int = 7): WindowChange? {
    val recent = windowMean(line.series, through, days, minDays) ?: return null
    val before = windowMean(line.series, through.minusDays(days.toLong()), days, minDays) ?: return null
    return WindowChange(line.key, line.label, recent, before)
}

/** "up from 24 min", "down from 2 h 10", "about the same as 7 h 05" (within 5 % or [sameWithin] of the unit). */
fun changeWords(c: WindowChange, format: (Double) -> String, sameWithin: Double = 1.0): String = when {
    kotlin.math.abs(c.change) < sameWithin || (c.relative != null && kotlin.math.abs(c.relative!!) < 0.05) -> "about the same as ${format(c.before)}"
    c.change > 0 -> "up from ${format(c.before)}"
    else -> "down from ${format(c.before)}"
}

// ---- Regularity -----------------------------------------------------------------------------------------------------

/** A clock time as minutes since 18:00, so bedtimes either side of midnight are close (23:30 = 330, 00:30 = 390). */
fun minutesSinceEvening(t: LocalTime): Double = ((t.toSecondOfDay() / 60.0) - 18 * 60 + 1440) % 1440

/** The spread (standard deviation, minutes) of [values] over each [window] days ending on a day, from [min] values. */
fun rollingSpread(values: Map<LocalDate, Double>, window: Int = 7, min: Int = 4): Map<LocalDate, Double> =
    values.keys.associateWith { d ->
        val v = (0 until window).mapNotNull { values[d.minusDays(it.toLong())] }
        if (v.size < min) null else {
            val m = v.average()
            sqrt(v.sumOf { (it - m) * (it - m) } / (v.size - 1))
        }
    }.filterValues { it != null }.mapValues { it.value!! }

/** Bedtimes or wake times (real instants) by the day the night ends, as minutes since 18:00 in [zone]. */
fun clockSeries(times: Map<LocalDate, Instant>, zone: ZoneId): Map<LocalDate, Double> =
    times.mapValues { (_, t) -> minutesSinceEvening(t.atZone(zone).toLocalTime()) }
