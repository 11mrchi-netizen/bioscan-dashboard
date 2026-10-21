package com.bioscan.fieldterminal.domain

import java.time.LocalDate
import kotlin.math.abs

// Marks on the run session page's RUN DYNAMICS tiles: this run against the usual for runs of its kind (RunBaseline:
// road the last 28 days, trail the last 5 trail runs within 60 days). A run is AS USUAL while it sits inside the
// normal run-to-run spread; beyond that it gets an arrow and a word. Only the efficiency factor, decoupling and
// vertical ratio have a better direction (IMPROVED / REGRESSED). Pace, power and cadence depend on what the run was
// for, so they only say which way (FASTER / SLOWER, HIGHER / LOWER).

enum class MarkTone { IMPROVED, REGRESSED, NEUTRAL, CAUTION, BUILDING }

data class RunMark(val symbol: String, val word: String, val tone: MarkTone, val usual: Double? = null)

enum class BetterWhen { HIGHER, LOWER, NEITHER }

// How a metric is read. [floor] is the smallest change that counts, so a quiet stretch with almost no spread does not
// flag noise: an absolute amount, or a share of the usual value when [relativeFloor].
data class MarkRule(
    val better: BetterWhen,
    val floor: Double,
    val relativeFloor: Boolean,
    val upWord: String,
    val downWord: String,
)

val EF_RULE = MarkRule(BetterWhen.HIGHER, 0.03, relativeFloor = true, upWord = "IMPROVED", downWord = "REGRESSED")
val DECOUPLING_RULE = MarkRule(BetterWhen.LOWER, 2.0, relativeFloor = false, upWord = "REGRESSED", downWord = "IMPROVED")
val VERTICAL_RATIO_RULE = MarkRule(BetterWhen.LOWER, 0.3, relativeFloor = false, upWord = "REGRESSED", downWord = "IMPROVED")
// Pace in min/km: a higher number is slower.
val PACE_RULE = MarkRule(BetterWhen.NEITHER, 0.03, relativeFloor = true, upWord = "SLOWER", downWord = "FASTER")
val POWER_RULE = MarkRule(BetterWhen.NEITHER, 0.03, relativeFloor = true, upWord = "HIGHER", downWord = "LOWER")
val CADENCE_RULE = MarkRule(BetterWhen.NEITHER, 3.0, relativeFloor = false, upWord = "HIGHER", downWord = "LOWER")

private const val MAD_TO_SD = 1.4826

private fun medianOfList(v: List<Double>): Double {
    val s = v.sorted()
    return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
}

// The usual run-to-run spread: the robust standard deviation (1.4826 × median absolute deviation), so one odd run
// does not widen it.
fun robustSpread(values: List<Double>): Double {
    if (values.size < 2) return 0.0
    val m = medianOfList(values)
    return MAD_TO_SD * medianOfList(values.map { abs(it - m) })
}

fun markAgainstUsual(
    value: Double,
    history: List<Pair<LocalDate, Double>>,
    date: LocalDate,
    baseline: RunBaseline,
    rule: MarkRule,
): RunMark {
    val window = baseline.window(history, date)
    val usual = baseline.before(history, date) ?: return RunMark("·", "BUILDING", MarkTone.BUILDING)
    val floor = if (rule.relativeFloor) rule.floor * abs(usual) else rule.floor
    val delta = value - usual
    if (abs(delta) <= maxOf(floor, robustSpread(window))) return RunMark("=", "AS USUAL", MarkTone.NEUTRAL, usual)
    val up = delta > 0
    val tone = when (rule.better) {
        BetterWhen.NEITHER -> MarkTone.NEUTRAL
        BetterWhen.HIGHER -> if (up) MarkTone.IMPROVED else MarkTone.REGRESSED
        BetterWhen.LOWER -> if (up) MarkTone.REGRESSED else MarkTone.IMPROVED
    }
    return RunMark(if (up) "▲" else "▼", if (up) rule.upWord else rule.downWord, tone, usual)
}

// ---- Stride for the pace ----------------------------------------------------------------------------------------

// Stride lengthens with speed, so a stride is only long or short for a given pace. The usual stride at this run's pace
// comes from a straight line through the person's own runs of the same surface over the last 90 days (stride against
// speed). LONG FOR THIS PACE, more than 8 % over the line, is the watch-side hint of overstriding: the same speed
// from fewer, longer steps (Cavanagh & Williams 1982: runners' freely chosen stride sits near their most economical,
// and lengthening it costs energy; Heiderscheit et al. 2011: a quicker, shorter step lowers loading at the hip and
// knee). Whether the foot actually lands ahead of the body only a video shows: the running form analyser. SHORT FOR
// THIS PACE is a quicker step for the speed; it is not a known risk on its own, but a sudden shortening can mean
// fatigue, a niggle or a slippery surface.

data class StridePacePoint(val date: LocalDate, val paceMinPerKm: Double, val strideCm: Double)

const val STRIDE_FOR_PACE_DAYS = 90L
const val STRIDE_FOR_PACE_MIN_RUNS = 6
const val STRIDE_FOR_PACE_TOLERANCE = 0.08

fun strideForPace(strideCm: Double, paceMinPerKm: Double, history: List<StridePacePoint>, date: LocalDate): RunMark {
    val window = history.filter { it.date.isBefore(date) && it.date.isAfter(date.minusDays(STRIDE_FOR_PACE_DAYS)) && it.paceMinPerKm > 0 }
    if (window.size < STRIDE_FOR_PACE_MIN_RUNS || paceMinPerKm <= 0) return RunMark("·", "BUILDING", MarkTone.BUILDING)
    val speeds = window.map { 1000.0 / it.paceMinPerKm }
    val strides = window.map { it.strideCm }
    val speed = 1000.0 / paceMinPerKm
    // Outside the paces the line was drawn from (with 5 % either side) the line says nothing.
    if (speed < speeds.min() * 0.95 || speed > speeds.max() * 1.05) return RunMark("·", "PACE OUTSIDE USUAL RANGE", MarkTone.BUILDING)
    val mx = speeds.average()
    val my = strides.average()
    val sxx = speeds.sumOf { (it - mx) * (it - mx) }
    val slope = if (sxx > 0) speeds.indices.sumOf { (speeds[it] - mx) * (strides[it] - my) } / sxx else 0.0
    val expected = my + slope * (speed - mx)
    if (expected <= 0) return RunMark("·", "BUILDING", MarkTone.BUILDING)
    val ratio = strideCm / expected - 1
    return when {
        ratio > STRIDE_FOR_PACE_TOLERANCE -> RunMark("▲", "LONG FOR THIS PACE", MarkTone.CAUTION, expected)
        ratio < -STRIDE_FOR_PACE_TOLERANCE -> RunMark("▼", "SHORT FOR THIS PACE", MarkTone.NEUTRAL, expected)
        else -> RunMark("=", "USUAL FOR THIS PACE", MarkTone.NEUTRAL, expected)
    }
}

// ---- One run's marks ---------------------------------------------------------------------------------------------

data class RunDynamicsMarks(
    val trail: Boolean,
    val categoryLabel: String,
    val pace: RunMark?,
    val power: RunMark?,
    val efficiencyFactor: RunMark?,
    val hrDecoupling: RunMark?,
    val cadence: RunMark?,
    val stride: RunMark?,
    val verticalRatio: RunMark?,
)

// Per-run history the marks read: one row per run, the same values the Running page plots.
data class RunHistoryRow(
    val id: Long,
    val date: LocalDate,
    val distanceKm: Double,
    val category: RunCategory,
    val paceMinPerKm: Double,
    val avgPowerW: Double?,
    val efficiencyFactor: Double?,
    val hrDecouplingPct: Double?,
    val cadenceSpm: Double?,
    val strideCm: Double?,
    val verticalRatioPct: Double?,
) {
    val trail: Boolean get() = category == RunCategory.TRAIL
}

// The run among [rows] that is this session: by id, else (the session page may show the other copy of a run that
// was recorded twice) the same day with the closest distance, within 5 %.
fun findRun(rows: List<RunHistoryRow>, id: Long, date: LocalDate, distanceKm: Double?): RunHistoryRow? =
    rows.firstOrNull { it.id == id } ?: distanceKm?.let { km ->
        rows.filter { it.date == date && abs(it.distanceKm - km) <= km * 0.05 }.minByOrNull { abs(it.distanceKm - km) }
    }

fun runDynamicsMarks(run: RunHistoryRow, rows: List<RunHistoryRow>): RunDynamicsMarks {
    val sameSurface = rows.filter { it.trail == run.trail && it.id != run.id }
    val sameKind = rows.filter { it.category == run.category && it.id != run.id }
    val baseline = RunBaseline.of(run.trail)
    fun series(from: List<RunHistoryRow>, pick: (RunHistoryRow) -> Double?) = from.mapNotNull { r -> pick(r)?.let { r.date to it } }
    fun mark(pick: (RunHistoryRow) -> Double?, from: List<RunHistoryRow>, rule: MarkRule, b: RunBaseline = baseline) =
        pick(run)?.let { markAgainstUsual(it, series(from, pick), run.date, b, rule) }
    return RunDynamicsMarks(
        trail = run.trail,
        categoryLabel = run.category.name.lowercase(),
        pace = mark({ it.paceMinPerKm }, sameKind, PACE_RULE),
        power = mark({ it.avgPowerW }, sameKind, POWER_RULE),
        efficiencyFactor = mark({ it.efficiencyFactor }, sameSurface, EF_RULE, efficiencyBaseline(run.trail)),
        hrDecoupling = mark({ it.hrDecouplingPct }, sameSurface, DECOUPLING_RULE),
        cadence = mark({ it.cadenceSpm }, sameSurface, CADENCE_RULE),
        stride = run.strideCm?.let { s ->
            strideForPace(s, run.paceMinPerKm, sameSurface.mapNotNull { r -> r.strideCm?.let { StridePacePoint(r.date, r.paceMinPerKm, it) } }, run.date)
        },
        verticalRatio = mark({ it.verticalRatioPct }, sameSurface, VERTICAL_RATIO_RULE),
    )
}
