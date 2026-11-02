package com.bioscan.fieldterminal.domain.score

import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

// Scoring system: the numbers behind the score screens' small graphs (docs/scoring-system/02-page-mockups.md), kept
// pure so the screens only draw them.

// ---- bullet chart: one input against its normal band or target ----

/**
 * A driver row's bullet chart: a track from [min] to [max], the zone that counts as good shaded ([zoneLo]..[zoneHi]),
 * an optional target [tick], and today's [value] (clamped to the track).
 */
data class Bullet(val min: Double, val max: Double, val zoneLo: Double, val zoneHi: Double, val tick: Double?, val value: Double) {
    fun fraction(v: Double): Double = if (max - min <= 1e-12) 0.5 else ((v - min) / (max - min)).coerceIn(0.0, 1.0)
}

fun bulletOf(input: InputScore): Bullet? {
    val v = input.value
    return when (val kind = input.spec.kind) {
        is InputKind.Baseline, is InputKind.Stability -> {
            val ref = input.reference ?: return null
            val sd = input.spread ?: return null
            val log = (kind as? InputKind.Baseline)?.log ?: (kind as InputKind.Stability).log
            fun at(k: Double) = if (log) ref * exp(k * sd) else ref + k * sd
            val lo = min(at(-3.0), v)
            val hi = max(at(3.0), v)
            Bullet(lo, hi, at(-1.0), at(1.0), null, v)
        }
        is InputKind.AtLeast -> {
            val top = max(kind.target * 1.5, v * 1.05).coerceAtLeast(kind.target + kind.scale)
            Bullet(min(0.0, v), top, kind.target, top, kind.target, v)
        }
        is InputKind.AtMost -> {
            val top = max(kind.limit + 3 * kind.scale, v * 1.05)
            Bullet(min(0.0, v), top, min(0.0, v), kind.limit, kind.limit, v)
        }
        is InputKind.Band -> {
            val lo = min(kind.lo - 2 * kind.scale, v)
            val hi = max(kind.hi + 2 * kind.scale, v)
            Bullet(lo, hi, kind.lo, kind.hi, null, v)
        }
    }
}

// ---- the ANS map: HRV against resting HR, both against their own baselines ----

data class AnsPoint(val day: LocalDate, val hrvZ: Double, val rhrZ: Double)

/** The last [days] mornings' HRV and resting-HR z against each morning's own 60-day baseline, oldest first. */
fun ansPoints(series: Map<String, Series>, day: LocalDate, days: Int = 14): List<AnsPoint> =
    (days - 1 downTo 0).mapNotNull { i ->
        val d = day.minusDays(i.toLong())
        val hrv = series[ScoreKeys.HRV]?.get(d)?.takeIf { it > 0 } ?: return@mapNotNull null
        val rhr = series[ScoreKeys.RHR]?.get(d) ?: return@mapNotNull null
        val (hm, hs) = baselineOf(series[ScoreKeys.HRV].orEmpty(), d, 60, 14, log = true, minSd = 0.03) ?: return@mapNotNull null
        val (rm, rs) = baselineOf(series[ScoreKeys.RHR].orEmpty(), d, 60, 14, log = false, minSd = 1.0) ?: return@mapNotNull null
        AnsPoint(d, (ln(hrv) - hm) / hs, (rhr - rm) / rs)
    }

// ---- a metric's own page ----

data class MetricStats(
    /** The last 7 mornings' mean. */
    val sevenDay: Double?,
    /** Day-to-day variation over the last 7 mornings, % of the mean. */
    val cvPct: Double?,
    /** How many of the last 60 mornings were further from usual than today (0-1); null without enough. */
    val rarerThan: Double?,
    /** Of the last 30 mornings, how many sat inside the normal band, and how many had a reading. */
    val insideBand: Int?,
    val withReading: Int,
)

fun metricStats(series: Series, day: LocalDate, log: Boolean, minSd: Double): MetricStats {
    val week = (0..6).mapNotNull { series[day.minusDays(it.toLong())] }
    val mean = week.takeIf { it.size >= 4 }?.average()
    val cv = if (week.size >= 4 && mean != null && abs(mean) > 1e-9) {
        val m = week.average()
        100 * sqrt(week.sumOf { (it - m) * (it - m) } / (week.size - 1)) / abs(m)
    } else null
    fun z(d: LocalDate): Double? {
        val v = series[d] ?: return null
        if (log && v <= 0) return null
        val (m, s) = baselineOf(series, d, 60, 14, log, minSd) ?: return null
        return ((if (log) ln(v) else v) - m) / s
    }
    val today = z(day)
    val past = (1..60).mapNotNull { z(day.minusDays(it.toLong())) }
    val rarer = if (today != null && past.size >= 20) past.count { abs(it) < abs(today) }.toDouble() / past.size else null
    val month = (0..29).mapNotNull { z(day.minusDays(it.toLong())) }
    return MetricStats(mean, cv, rarer, if (month.isEmpty()) null else month.count { abs(it) <= 1.0 }, month.size)
}

/** Another input that moved with this one: its key, label and the correlation over the mornings both had. */
data class Relation(val key: String, val label: String, val r: Double, val days: Int)

/**
 * The inputs whose values went together with [key]'s over the last [window] mornings (at least [minDays] in common),
 * strongest first. Descriptive: "goes with", not a cause.
 */
fun relatedInputs(
    series: Map<String, Series>,
    key: String,
    day: LocalDate,
    labels: Map<String, String>,
    window: Int = 120,
    minDays: Int = 30,
    top: Int = 3,
): List<Relation> {
    val mine = series[key] ?: return emptyList()
    val dates = (0 until window).map { day.minusDays(it.toLong()) }
    return labels.filterKeys { it != key }.mapNotNull { (other, label) ->
        val theirs = series[other] ?: return@mapNotNull null
        val pairs = dates.mapNotNull { d -> mine[d]?.let { a -> theirs[d]?.let { b -> a to b } } }
        if (pairs.size < minDays) return@mapNotNull null
        val ma = pairs.map { it.first }.average()
        val mb = pairs.map { it.second }.average()
        val sab = pairs.sumOf { (a, b) -> (a - ma) * (b - mb) }
        val saa = pairs.sumOf { (a, _) -> (a - ma) * (a - ma) }
        val sbb = pairs.sumOf { (_, b) -> (b - mb) * (b - mb) }
        if (saa < 1e-12 || sbb < 1e-12) return@mapNotNull null
        Relation(other, label, sab / sqrt(saa * sbb), pairs.size)
    }.filter { abs(it.r) >= 0.2 }.sortedByDescending { abs(it.r) }.take(top)
}

/** One series' last [days] mornings, oldest first (missing mornings left out), for a sparkline. */
fun sparkline(series: Series?, day: LocalDate, days: Int): List<Pair<LocalDate, Double>> =
    (days - 1 downTo 0).mapNotNull { i -> day.minusDays(i.toLong()).let { d -> series?.get(d)?.let { d to it } } }
