package com.bioscan.fieldterminal.domain.score

import com.bioscan.fieldterminal.data.model.StoolAnalysisRow
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.max

// Scoring system, FUEL › gut (docs/scoring-system/02-page-mockups.md §4): the two full-picture views of digestion.
//
//   Digestive pattern  a hypnogram of the stool log: each bowel movement a mark at its time, in the lane of its form,
//                      consecutive marks joined, lanes from LIQUID on top to HARD at the bottom (as Throne Health draws it).
//                      The lanes use the same Bristol groups as BristolEvaluation: 1-2 hard, 3-5 typical, 6 loose, 7 liquid.
//   Bowel timing       each day's movements by time of day, against the usual window: the median of the first movement's
//                      time over the 60 days before the view, plus or minus its robust spread (at least 30 minutes).
// Descriptive only, never phrased as a condition (BristolEvaluation's non-medical guard).

enum class StoolLane(val label: String, val types: IntRange) {
    LIQUID("LIQUID", 7..7),
    LOOSE("LOOSE", 6..6),
    HEALTHY("HEALTHY", 3..5),
    HARD("HARD", 1..2);

    companion object {
        /** Top to bottom, as drawn. */
        val DRAWN = listOf(LIQUID, LOOSE, HEALTHY, HARD)
        fun of(bristol: Int): StoolLane? = entries.firstOrNull { bristol in it.types }
    }
}

data class StoolMark(val at: Instant, val day: LocalDate, val minuteOfDay: Int, val bristol: Int?, val lane: StoolLane?)

data class StoolPattern(
    val from: LocalDate,
    val to: LocalDate,
    /** Oldest first. A mark without a Bristol type has no lane: it shows on the timing view only. */
    val marks: List<StoolMark>,
) {
    val counts: Map<StoolLane, Int> get() = StoolLane.entries.associateWith { lane -> marks.count { it.lane == lane } }
    val typed: Int get() = marks.count { it.lane != null }
    val days: Int get() = (to.toEpochDay() - from.toEpochDay() + 1).toInt()
}

private fun instantOf(text: String): Instant? =
    runCatching { Instant.parse(text) }.getOrNull() ?: runCatching { OffsetDateTime.parse(text).toInstant() }.getOrNull()

private fun StoolAnalysisRow.mark(zone: ZoneId): StoolMark? {
    val at = instantOf(occurredAt) ?: return null
    val local = at.atZone(zone)
    return StoolMark(at, local.toLocalDate(), local.toLocalTime().toSecondOfDay() / 60, bristolType, bristolType?.let(StoolLane::of))
}

/** The stool log from [from] to [to] (inclusive), as marks oldest first. */
fun stoolPattern(rows: List<StoolAnalysisRow>, from: LocalDate, to: LocalDate, zone: ZoneId = ZoneId.systemDefault()): StoolPattern =
    StoolPattern(from, to, rows.mapNotNull { it.mark(zone) }.filter { !it.day.isBefore(from) && !it.day.isAfter(to) }.sortedBy { it.at })

/** One line for the tile: "12 of 14 healthy · 1 loose · 1 hard". */
fun stoolPatternHeadline(pattern: StoolPattern): String {
    if (pattern.typed == 0) return if (pattern.marks.isEmpty()) "nothing logged" else "${pattern.marks.size} logged, no type"
    val c = pattern.counts
    val rest = StoolLane.DRAWN.filter { it != StoolLane.HEALTHY && (c[it] ?: 0) > 0 }.joinToString("") { " · ${c[it]} ${it.label.lowercase()}" }
    return "${c[StoolLane.HEALTHY] ?: 0} of ${pattern.typed} healthy$rest"
}

data class BowelTiming(
    val from: LocalDate,
    val to: LocalDate,
    /** Each day's movement times (minutes after midnight), oldest day first; a day with none has an empty list. */
    val days: List<Pair<LocalDate, List<Int>>>,
    /** The usual window for the first movement, minutes after midnight; null without 10 earlier days to learn it. */
    val usualStart: Int?,
    val usualEnd: Int?,
    /** Spread of the first movement's time over the view's days, minutes; null under 4 days with one. */
    val spreadMin: Double?,
    /** The same spread over the 60 days before, for "steady" against "more variable than usual". */
    val usualSpreadMin: Double?,
) {
    /** True when the view's spread is no more than a quarter above the person's usual spread. */
    val steady: Boolean? get() = if (spreadMin == null || usualSpreadMin == null) null else spreadMin <= usualSpreadMin * 1.25
    fun inWindow(minute: Int): Boolean? = if (usualStart == null || usualEnd == null) null else minute in usualStart..usualEnd
}

private fun medianOf(values: List<Double>): Double {
    val s = values.sorted()
    return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
}

private fun robustSpread(values: List<Double>): Double {
    val m = medianOf(values)
    return 1.4826 * medianOf(values.map { abs(it - m) })
}

/** Movements by time of day for [days] days ending [to], with the usual window learned from the 60 days before. */
fun bowelTiming(rows: List<StoolAnalysisRow>, to: LocalDate, days: Int = 14, zone: ZoneId = ZoneId.systemDefault()): BowelTiming {
    val from = to.minusDays(days - 1L)
    val marks = rows.mapNotNull { it.mark(zone) }
    val byDay = marks.groupBy { it.day }
    val firstByDay = byDay.mapValues { (_, m) -> m.minOf { it.minuteOfDay }.toDouble() }
    val prior = (1..60).mapNotNull { firstByDay[from.minusDays(it.toLong())] }
    val (start, end) = if (prior.size >= 10) {
        val m = medianOf(prior)
        val half = max(30.0, robustSpread(prior))
        (m - half).toInt().coerceAtLeast(0) to (m + half).toInt().coerceAtMost(24 * 60 - 1)
    } else null to null
    val window = (0 until days).map { from.plusDays(it.toLong()) }
    val firsts = window.mapNotNull { firstByDay[it] }
    return BowelTiming(
        from = from,
        to = to,
        days = window.map { d -> d to byDay[d].orEmpty().map { it.minuteOfDay }.sorted() },
        usualStart = start,
        usualEnd = end,
        spreadMin = if (firsts.size >= 4) robustSpread(firsts).coerceAtLeast(1.0) else null,
        usualSpreadMin = if (prior.size >= 10) robustSpread(prior).coerceAtLeast(1.0) else null,
    )
}

/** One line for the tile: "steady · usual 07:00–08:40" or "more variable than usual". */
fun bowelTimingHeadline(timing: BowelTiming): String {
    fun clock(m: Int) = "%02d:%02d".format(m / 60, m % 60)
    val window = if (timing.usualStart != null && timing.usualEnd != null) "window ${clock(timing.usualStart)}–${clock(timing.usualEnd)}" else null
    val state = when (timing.steady) {
        true -> "steady"
        false -> "more variable than usual"
        null -> if (timing.days.all { it.second.isEmpty() }) "nothing logged" else "learning your usual"
    }
    return listOfNotNull(state, window).joinToString(" · ")
}
