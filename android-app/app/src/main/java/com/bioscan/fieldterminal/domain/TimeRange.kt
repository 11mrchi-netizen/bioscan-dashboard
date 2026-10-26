package com.bioscan.fieldterminal.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

// Redesign: the one range selector on the Training, Nutrition and Health pages (7D · 1M · 3M · 6M · 1Y · ALL). It sets
// the window and the grain of every graph on those pages: daily values up to a month, weekly means for 3M and 6M,
// monthly means for 1Y and All. Distinct from TotalsPeriod (the running-totals card) and VitalsTimeframe (older HRV/RHR toggle).
enum class TimeRange(val label: String, val days: Long?) {
    Week("7D", 7), Month("1M", 30), Quarter("3M", 90), Half("6M", 180), Year("1Y", 365), All("ALL", null);

    val grain: Grain get() = when (this) {
        Week, Month -> Grain.Day
        Quarter, Half -> Grain.Week
        Year, All -> Grain.Month
    }

    /** First day shown; null = from the first reading. The window is inclusive of [asOf]. */
    fun start(asOf: LocalDate = LocalDate.now()): LocalDate? = days?.let { asOf.minusDays(it - 1) }

    enum class Grain { Day, Week, Month }
}

private fun LocalDate.bucket(grain: TimeRange.Grain): LocalDate = when (grain) {
    TimeRange.Grain.Day -> this
    TimeRange.Grain.Week -> with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    TimeRange.Grain.Month -> YearMonth.from(this).atDay(1)
}

/** Points inside the range's window, sorted by date. */
fun <T> windowed(points: List<Pair<LocalDate, T>>, range: TimeRange, asOf: LocalDate = LocalDate.now()): List<Pair<LocalDate, T>> {
    val start = range.start(asOf)
    return points.filter { (d, _) -> d <= asOf && (start == null || d >= start) }.sortedBy { it.first }
}

/** The range's window at the range's grain: daily as logged, or the mean of each Monday-week / calendar month, dated at the bucket start. */
fun aggregate(points: List<Pair<LocalDate, Double>>, range: TimeRange, asOf: LocalDate = LocalDate.now()): List<Pair<LocalDate, Double>> {
    val inWindow = windowed(points, range, asOf)
    if (range.grain == TimeRange.Grain.Day) return inWindow
    return inWindow.groupBy { it.first.bucket(range.grain) }.toSortedMap().map { (d, v) -> d to v.map { it.second }.average() }
}

/** A day's low and high (heart rate, blood pressure). */
data class DayRange(val low: Double, val high: Double)

/**
 * Range columns at the range's grain: daily as logged; for a week or month, the mean of its daily lows and the mean of
 * its daily highs (the typical day in that bucket, not the single most extreme minute in it).
 */
fun aggregateRanges(points: List<Pair<LocalDate, DayRange>>, range: TimeRange, asOf: LocalDate = LocalDate.now()): List<Pair<LocalDate, DayRange>> {
    val inWindow = windowed(points, range, asOf)
    if (range.grain == TimeRange.Grain.Day) return inWindow
    return inWindow.groupBy { it.first.bucket(range.grain) }.toSortedMap()
        .map { (d, v) -> d to DayRange(v.map { it.second.low }.average(), v.map { it.second.high }.average()) }
}

/** Days of history a fetch must cover so the range is not silently cut short. */
fun TimeRange.fetchDays(): Long = days ?: ChronoUnit.DAYS.between(LocalDate.of(2015, 1, 1), LocalDate.now())
