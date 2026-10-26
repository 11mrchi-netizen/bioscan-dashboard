package com.bioscan.fieldterminal.ui.components

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

// Analysis review phase C (P10, design/ANALYTICAL_PRESENTATION_CONTRACT.md "Chart rules"). The rules live in the shared
// chart component so no screen has to remember them; these are their pure parts:
//   1. a y-axis with at least three labelled values (niceTicks);
//   2. a fixed personal scale per metric, the 2nd to 98th percentile of the person's own history, so an ordinary
//      wobble looks like a wobble instead of filling the chart (personalScale);
//   3. gaps stay gaps: a daily series breaks its line where days are missing (trendSegments).

// "Nice" tick values (1, 2 or 5 times a power of ten) inside [lo, hi]: at least `minTicks` of them.
fun niceTicks(lo: Double, hi: Double, minTicks: Int = 3): List<Double> {
    if (!(hi > lo) || !lo.isFinite() || !hi.isFinite()) return listOf(lo)
    var step = niceStep((hi - lo) / (minTicks - 1).coerceAtLeast(1))
    var ticks = ticksFor(lo, hi, step)
    var guard = 0
    while (ticks.size < minTicks && guard++ < 6) {
        step = smallerNiceStep(step)
        ticks = ticksFor(lo, hi, step)
    }
    return ticks
}

// Decimals a tick label needs for this tick spacing (5 -> 0, 0.5 -> 1, 0.05 -> 2).
fun tickDecimals(ticks: List<Double>): Int {
    if (ticks.size < 2) return 0
    val step = abs(ticks[1] - ticks[0])
    return if (step >= 1 || step <= 0) 0 else ceil(-log10(step) - 1e-9).toInt()
}

private fun niceStep(raw: Double): Double {
    val mag = 10.0.pow(floor(log10(raw)))
    val norm = raw / mag
    val nice = when {
        norm <= 1.0 -> 1.0
        norm <= 2.0 -> 2.0
        norm <= 5.0 -> 5.0
        else -> 10.0
    }
    return nice * mag
}

private fun smallerNiceStep(step: Double): Double {
    val mag = 10.0.pow(floor(log10(step) + 1e-9))
    val norm = step / mag
    return when {
        norm > 4.5 -> 2.0 * mag
        norm > 1.5 -> 1.0 * mag
        else -> 0.5 * mag
    }
}

private fun ticksFor(lo: Double, hi: Double, step: Double): List<Double> {
    val first = ceil(lo / step - 1e-9) * step
    val out = mutableListOf<Double>()
    var i = 0
    while (true) {
        val v = first + i * step
        if (v > hi + step * 1e-9 || i > 50) break
        out.add(if (abs(v) < step * 1e-9) 0.0 else v)
        i++
    }
    return out
}

// The person's usual range for a metric: 2nd to 98th percentile of their own readings (a year of them, or what there is).
// Null with fewer than 14 readings, when a scale would mostly reflect chance.
fun personalScale(values: List<Double>, lowPct: Double = 0.02, highPct: Double = 0.98): ClosedFloatingPointRange<Double>? {
    val v = values.filter { it.isFinite() }.sorted()
    if (v.size < 14) return null
    fun q(p: Double): Double {
        val pos = p * (v.size - 1)
        val i = floor(pos).toInt()
        val f = pos - i
        return if (i + 1 < v.size) v[i] + f * (v[i + 1] - v[i]) else v[i]
    }
    val lo = q(lowPct)
    val hi = q(highPct)
    return if (hi > lo) lo..hi else null
}

// Splits a date-sorted series where consecutive points are more than `maxGapDays` apart; null keeps it in one piece
// (session-based series, where a few days between points is the normal rhythm, not missing data).
fun <T> trendSegments(points: List<Pair<LocalDate, T>>, maxGapDays: Int?): List<List<Pair<LocalDate, T>>> {
    if (points.isEmpty()) return emptyList()
    if (maxGapDays == null) return listOf(points)
    val out = mutableListOf<MutableList<Pair<LocalDate, T>>>(mutableListOf(points.first()))
    for (i in 1 until points.size) {
        if (ChronoUnit.DAYS.between(points[i - 1].first, points[i].first) > maxGapDays) out.add(mutableListOf())
        out.last().add(points[i])
    }
    return out
}
