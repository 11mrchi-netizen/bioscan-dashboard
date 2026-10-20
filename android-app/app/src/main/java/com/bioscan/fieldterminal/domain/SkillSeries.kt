package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.domain.training.movementKey
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

// Series helpers for the User tab's skill pages (Running / Strength / Conditioning). Pure bucketing and
// folding over data the app already loads -- no new analysis, no imputation: a week with no sessions is
// a real zero (`count == 0`), and callers decide whether to draw it as an empty week.

data class WeekBucket(val weekStart: LocalDate, val value: Double, val count: Int)

enum class WeekAggregate { SUM, MAX, MEAN }

fun mondayOf(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

// One bucket per Monday-started week from the week containing `from` to the week containing `to`,
// zero-filled so the x axis stays honest about quiet weeks.
fun <T> bucketWeekly(
    items: List<T>,
    dateOf: (T) -> LocalDate,
    valueOf: (T) -> Double,
    from: LocalDate,
    to: LocalDate,
    aggregate: WeekAggregate = WeekAggregate.SUM,
): List<WeekBucket> {
    val firstWeek = mondayOf(from)
    val lastWeek = mondayOf(to)
    val weeks = ChronoUnit.WEEKS.between(firstWeek, lastWeek).toInt()
    val byWeek = items.filter { !dateOf(it).isBefore(firstWeek) && !dateOf(it).isAfter(to) }.groupBy { mondayOf(dateOf(it)) }
    return (0..weeks).map { i ->
        val week = firstWeek.plusWeeks(i.toLong())
        val values = byWeek[week].orEmpty().map(valueOf)
        val value = when {
            values.isEmpty() -> 0.0
            aggregate == WeekAggregate.SUM -> values.sum()
            aggregate == WeekAggregate.MAX -> values.max()
            else -> values.average()
        }
        WeekBucket(week, value, values.size)
    }
}

// Trailing mean over the previous `window` weeks including the current one (fewer at the start: the
// average of what exists, never padded with zeros).
fun trailingWeeklyAverage(buckets: List<WeekBucket>, window: Int = 4): List<Double> =
    buckets.indices.map { i -> buckets.subList(maxOf(0, i - window + 1), i + 1).map { it.value }.average() }

// A trailing median over [days] days at each point's date (that day included), once [minCount] points fall in the
// window: the 28-day line of the Running page's efficiency and form charts. One run is too noisy to read alone.
fun rollingMedian(series: List<Pair<LocalDate, Double>>, days: Long = 28, minCount: Int = 3): List<Pair<LocalDate, Double>> {
    val sorted = series.sortedBy { it.first }
    return sorted.mapNotNull { (date, _) ->
        val window = sorted.filter { !it.first.isAfter(date) && it.first.isAfter(date.minusDays(days)) }.map { it.second }.sorted()
        if (window.size < minCount) null
        else date to (if (window.size % 2 == 1) window[window.size / 2] else (window[window.size / 2 - 1] + window[window.size / 2]) / 2)
    }.distinctBy { it.first }
}

// One week's kilometres split into parts (the Running page's EASY / TRAIL / QUALITY bars), parts in the caller's order.
data class StackedWeek(val weekStart: LocalDate, val parts: List<Double>) {
    val total: Double get() = parts.sum()
}

// The weeks from `from` to `to` (zero-filled, as bucketWeekly), each item's value added to the part `partOf` names.
fun <T> bucketWeeklyStacked(
    items: List<T>,
    dateOf: (T) -> LocalDate,
    valueOf: (T) -> Double,
    partOf: (T) -> Int,
    partCount: Int,
    from: LocalDate,
    to: LocalDate,
): List<StackedWeek> {
    val firstWeek = mondayOf(from)
    val weeks = ChronoUnit.WEEKS.between(firstWeek, mondayOf(to)).toInt()
    val byWeek = items.filter { !dateOf(it).isBefore(firstWeek) && !dateOf(it).isAfter(to) }.groupBy { mondayOf(dateOf(it)) }
    return (0..weeks).map { i ->
        val week = firstWeek.plusWeeks(i.toLong())
        val parts = DoubleArray(partCount)
        byWeek[week].orEmpty().forEach { parts[partOf(it)] += valueOf(it) }
        StackedWeek(week, parts.toList())
    }
}

// ---- Strength ---------------------------------------------------------------------------------

data class LoggedSet(val date: LocalDate, val exerciseName: String, val reps: Int, val weightKg: Double, val rpe: Int? = null)

// Same Epley rule and 12-rep gate as StrengthLoad.estimatedOneRepMax, so a PR card, a session card and
// this series cannot disagree about what an e1RM is.
private const val MAX_REPS_FOR_E1RM = 12
fun setE1rmKg(reps: Int, weightKg: Double): Double? =
    if (reps in 1..MAX_REPS_FOR_E1RM && weightKg > 0) weightKg * (1 + reps / 30.0) else null

data class LiftSeries(val key: String, val name: String, val points: List<Pair<LocalDate, Double>>)

// Best e1RM per lift per day, lifts folded together with movementKey() (so "Standing Military Press"
// and "Overhead Press" are one series). Ordered by number of logged days, most-trained first.
fun liftE1rmSeries(sets: List<LoggedSet>): List<LiftSeries> =
    sets.groupBy { movementKey(it.exerciseName) }.map { (key, group) ->
        val points = group.mapNotNull { s -> setE1rmKg(s.reps, s.weightKg)?.let { s.date to it } }
            .groupBy({ it.first }, { it.second })
            .map { (date, values) -> date to values.max() }
            .sortedBy { it.first }
        LiftSeries(key, group.maxBy { it.date }.exerciseName, points)
    }.filter { it.points.isNotEmpty() }.sortedByDescending { it.points.size }

data class LiftPr(val date: LocalDate, val key: String, val name: String, val e1rmKg: Double, val previousKg: Double?)

// Every strict improvement over the running best, oldest first (the very first point is a PR with no
// previous value). Mirrors isNewRecord(): only a strictly higher value is a record.
fun e1rmPrTimeline(series: List<LiftSeries>): List<LiftPr> =
    series.flatMap { s ->
        var best: Double? = null
        s.points.mapNotNull { (date, value) ->
            val prev = best
            if (prev == null || value > prev) {
                best = value
                LiftPr(date, s.key, s.name, value, prev)
            } else null
        }
    }.sortedBy { it.date }

// Tonnage (reps x load) per set, bucketed by week.
fun weeklyTonnageKg(sets: List<LoggedSet>, from: LocalDate, to: LocalDate): List<WeekBucket> =
    bucketWeekly(sets, { it.date }, { it.reps * it.weightKg }, from, to)

// Distinct training days per week (a day with several sets counts once).
fun weeklyStrengthDays(sets: List<LoggedSet>, from: LocalDate, to: LocalDate): List<WeekBucket> {
    val days = sets.map { it.date }.distinct()
    return bucketWeekly(days, { it }, { 1.0 }, from, to)
}
