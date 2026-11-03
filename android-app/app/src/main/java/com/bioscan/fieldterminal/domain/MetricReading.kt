package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.domain.score.InputDirection
import com.bioscan.fieldterminal.domain.score.Series
import com.bioscan.fieldterminal.domain.score.baselineOf
import java.time.LocalDate
import kotlin.math.exp

// How one daily metric reads on the detail pages (Sleep, Cardio, Wellbeing...): its latest value, the person's normal
// band (the same robust median ± spread the score engine ranks inputs with, over the 60 days before the latest reading),
// where the latest value sits against that band, and how it moved against the week before.

data class MetricReading(
    val latestDate: LocalDate?,
    val latest: Double?,
    /** The person's normal range in the metric's own units, or null with too little history. */
    val normal: Pair<Double, Double>?,
    /** ABOVE / NORMAL / BELOW the normal band; null without a band. */
    val position: InputDirection?,
    /** Latest minus the mean of the 7 days before it; null with fewer than 3 of them. */
    val change: Double?,
)

/**
 * Reads [series] at its most recent value on or before [today] (no older than [maxAgeDays]). [log] bands multiplicative
 * metrics (HRV) on the log scale; [minSd] floors the spread so a very steady metric does not get a hair-thin band.
 */
fun readMetric(
    series: Series,
    today: LocalDate,
    log: Boolean = false,
    minSd: Double = 0.0,
    window: Int = 60,
    minHistory: Int = 14,
    maxAgeDays: Long = 7,
): MetricReading {
    val latestDate = series.keys.filter { !it.isAfter(today) && !it.isBefore(today.minusDays(maxAgeDays)) }.maxOrNull()
    val latest = latestDate?.let { series[it] }
    val anchor = latestDate ?: today
    val normal = baselineOf(series, anchor, window, minHistory, log, minSd)?.let { (m, sd) ->
        if (log) exp(m - sd) to exp(m + sd) else (m - sd) to (m + sd)
    }
    val position = if (latest == null || normal == null) null else when {
        latest > normal.second -> InputDirection.ABOVE
        latest < normal.first -> InputDirection.BELOW
        else -> InputDirection.NORMAL
    }
    val before = (1..7L).mapNotNull { series[anchor.minusDays(it)] }
    val change = if (latest != null && before.size >= 3) latest - before.average() else null
    return MetricReading(latestDate, latest, normal, position, change)
}

/** The values of [series] in the [days] days ending on [day], oldest first, one slot per day (null where missing). */
fun Series.window(day: LocalDate, days: Int): List<Pair<LocalDate, Double?>> =
    (days - 1 downTo 0).map { i -> day.minusDays(i.toLong()).let { d -> d to this[d] } }

/** Pairs of (date, value) to a [Series], keeping the last value when a date repeats. */
fun List<Pair<LocalDate, Double>>.toSeries(): Series = associate { it.first to it.second }
