package com.bioscan.fieldterminal.domain

import com.bioscan.fieldterminal.domain.score.ScoreKeys
import com.bioscan.fieldterminal.domain.score.Series
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.sqrt

// "Since you changed X" (docs/regimen/README.md, phase 3): what moved in the weeks after a start, stop or dose change,
// compared with the weeks before. Before/after on one person cannot tell the change apart from everything else that
// moved at the same time, so a reading is only shown when the difference is clearly bigger than the metric's own
// day-to-day noise, it is worded as what happened alongside the change, the other changes made around the same time are
// listed, and a self-experiment is offered as the way to find out.

data class WatchedMetric(val key: String, val label: String, val unit: String, val betterHigher: Boolean?, val decimals: Int)

val REGIMEN_WATCHED_METRICS: List<WatchedMetric> = listOf(
    WatchedMetric(ScoreKeys.HRV, "Overnight HRV", "ms", true, 0),
    WatchedMetric(ScoreKeys.RHR, "Resting heart rate", "bpm", false, 0),
    WatchedMetric(ScoreKeys.SLEEP_HOURS, "Sleep", "h", true, 1),
    WatchedMetric(ScoreKeys.NIGHT_STRESS, "Night stress", "", false, 0),
    WatchedMetric(ScoreKeys.ENERGY, "Energy", "/5", true, 1),
    WatchedMetric(ScoreKeys.MOOD, "Mood", "/5", true, 1),
    WatchedMetric(ScoreKeys.FELT_STRESS, "Felt stress", "/5", false, 1),
)

/** Days compared on each side of a change, the days skipped right after it, and the readings needed on each side. */
const val EFFECT_WINDOW_DAYS = 21
const val EFFECT_SETTLE_DAYS = 1
const val EFFECT_MIN_READINGS = 10

data class ChangeReading(
    val metric: WatchedMetric,
    val before: Double,
    val after: Double,
    val nBefore: Int,
    val nAfter: Int,
    /** The difference in units of the before-weeks' robust day-to-day spread. */
    val spreads: Double,
) {
    val difference: Double get() = after - before
    /** Whether the move is in the metric's better direction; null for metrics with no better side. */
    val better: Boolean? get() = metric.betterHigher?.let { (difference > 0) == it }
}

private fun avgOf(v: List<Double>) = v.sum() / v.size

private fun robustSd(v: List<Double>): Double {
    val s = v.sorted()
    val med = s[s.size / 2]
    val mad = v.map { abs(it - med) }.sorted()[v.size / 2]
    return 1.4826 * mad
}

/**
 * The metrics that moved clearly after [changeDate]: before = the [EFFECT_WINDOW_DAYS] days before it, after = the same
 * span starting [EFFECT_SETTLE_DAYS] after it (cut at [today]). Each side needs [EFFECT_MIN_READINGS] readings. Clearly
 * = the difference is at least half the before-weeks' robust day-to-day spread and more than twice its standard error.
 */
fun changeReadings(changeDate: LocalDate, series: Map<String, Series>, today: LocalDate, metrics: List<WatchedMetric> = REGIMEN_WATCHED_METRICS): List<ChangeReading> {
    if (ChronoUnit.DAYS.between(changeDate, today) < EFFECT_MIN_READINGS + EFFECT_SETTLE_DAYS) return emptyList()
    return metrics.mapNotNull { m ->
        val s = series[m.key] ?: return@mapNotNull null
        val before = (1..EFFECT_WINDOW_DAYS).mapNotNull { s[changeDate.minusDays(it.toLong())] }
        val after = (EFFECT_SETTLE_DAYS until EFFECT_SETTLE_DAYS + EFFECT_WINDOW_DAYS)
            .map { changeDate.plusDays(it.toLong()) }.filter { !it.isAfter(today) }.mapNotNull { s[it] }
        if (before.size < EFFECT_MIN_READINGS || after.size < EFFECT_MIN_READINGS) return@mapNotNull null
        val sd = maxOf(robustSd(before), 1e-9)
        val diff = avgOf(after) - avgOf(before)
        val se = sd * sqrt(1.0 / before.size + 1.0 / after.size)
        if (abs(diff) < 0.5 * sd || abs(diff) < 2 * se) return@mapNotNull null
        ChangeReading(m, avgOf(before), avgOf(after), before.size, after.size, diff / sd)
    }.sortedByDescending { abs(it.spreads) }
}

/** Other regimen changes within [days] of [date], not counting [itemName]'s own: what the reading cannot separate. */
fun nearbyChanges(changes: List<RegimenChange>, date: LocalDate, itemName: String, days: Long = 14): List<RegimenChange> =
    changes.filter { it.itemName != itemName && abs(ChronoUnit.DAYS.between(date, it.date)) <= days }

private fun fmt(v: Double, decimals: Int) = if (decimals == 0) Math.round(v).toString() else "%.${decimals}f".format(v)

/** "Overnight HRV averaged 52 ms in the 3 weeks after, 46 ms in the 3 weeks before (+6 ms)." */
fun changeReadingWords(r: ChangeReading): String {
    val u = r.metric.unit.let { if (it.isEmpty() || it.startsWith("/")) it else " $it" }
    val sign = if (r.difference >= 0) "+" else "−"
    return "${r.metric.label} averaged ${fmt(r.after, r.metric.decimals)}$u in the ${r.nAfter} days with data after, " +
        "${fmt(r.before, r.metric.decimals)}$u in the ${r.nBefore} before ($sign${fmt(abs(r.difference), r.metric.decimals)}$u)."
}
