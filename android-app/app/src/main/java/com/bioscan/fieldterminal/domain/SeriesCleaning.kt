package com.bioscan.fieldterminal.domain

import java.time.LocalDate

// Making a series fit to draw (the chart audit, Nov 2026). Pure.
//   - per-second watch data as a rolling median, so a GPS wobble or a missed beat does not spike the line;
//   - pace from speed only where it is running or walking pace (2-15 min/km): slower is standing at a light, which the
//     pace chart shows as a stop rather than a 90 min/km spike that flattens the rest;
//   - one value a day for a dated series, so two sessions on one day do not draw a vertical zigzag.

/** The fastest and slowest pace (min/km) a pace chart keeps; outside it the runner was stopped (or the GPS jumped). */
const val PACE_FASTEST_MIN_PER_KM = 2.0
const val PACE_SLOWEST_MIN_PER_KM = 15.0

/** Pace (min/km) from speed (km/h), only where it is a moving pace. */
fun paceSeries(speedKmh: List<TimePoint>): List<TimePoint> = speedKmh.mapNotNull { p ->
    if (p.value <= 0) return@mapNotNull null
    val pace = 60.0 / p.value
    if (pace in PACE_FASTEST_MIN_PER_KM..PACE_SLOWEST_MIN_PER_KM) TimePoint(p.offsetSeconds, pace) else null
}

/** Each point replaced by the median of the points within [windowSec] seconds centred on it; 0 or fewer leaves it. */
fun rollingMedian(points: List<TimePoint>, windowSec: Long): List<TimePoint> {
    if (windowSec <= 0 || points.size < 3) return points
    val sorted = points.sortedBy { it.offsetSeconds }
    val half = windowSec / 2
    var lo = 0
    var hi = 0
    return sorted.map { p ->
        while (sorted[lo].offsetSeconds < p.offsetSeconds - half) lo++
        while (hi + 1 < sorted.size && sorted[hi + 1].offsetSeconds <= p.offsetSeconds + half) hi++
        val window = (lo..hi).map { sorted[it].value }.sorted()
        val n = window.size
        TimePoint(p.offsetSeconds, if (n % 2 == 1) window[n / 2] else (window[n / 2 - 1] + window[n / 2]) / 2)
    }
}

/** Each point replaced by the mean of the points within [windowSec] seconds centred on it. */
fun rollingMean(points: List<TimePoint>, windowSec: Long): List<TimePoint> {
    if (windowSec <= 0 || points.size < 3) return points
    val sorted = points.sortedBy { it.offsetSeconds }
    val half = windowSec / 2
    var lo = 0
    var hi = 0
    var sum = 0.0
    hi = -1
    return sorted.map { p ->
        while (hi + 1 < sorted.size && sorted[hi + 1].offsetSeconds <= p.offsetSeconds + half) { hi++; sum += sorted[hi].value }
        while (sorted[lo].offsetSeconds < p.offsetSeconds - half) { sum -= sorted[lo].value; lo++ }
        TimePoint(p.offsetSeconds, sum / (hi - lo + 1))
    }
}

/** A per-second series made fit to draw: the rolling median over [windowSec] (spikes out), then a mean over half of it. */
fun smoothForChart(points: List<TimePoint>, windowSec: Long): List<TimePoint> =
    if (windowSec <= 0) points else rollingMean(rollingMedian(points, windowSec), windowSec / 2)

/** How a day's several values become one. */
enum class DayReduce { MEAN, MAX, MIN, LAST }

/** One value per date, oldest first: several on one date are reduced by [how] (LAST keeps the input order's last). */
fun oneADay(points: List<Pair<LocalDate, Double>>, how: DayReduce = DayReduce.MEAN): List<Pair<LocalDate, Double>> =
    points.groupBy({ it.first }, { it.second }).map { (d, v) ->
        d to when (how) {
            DayReduce.MEAN -> v.average()
            DayReduce.MAX -> v.max()
            DayReduce.MIN -> v.min()
            DayReduce.LAST -> v.last()
        }
    }.sortedBy { it.first }
