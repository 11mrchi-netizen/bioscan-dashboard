package com.bioscan.fieldterminal.domain

import java.time.LocalTime

// App restructure, NUTRITION TODAY on the Today page: how close the day is to its targets. A bar per nutrient reads
// against the share of the day's target the hour of the day expects, so a morning is not called short for having eaten
// a morning's worth. Pure; the card supplies the targets (today's fuel plan when there is one, else the nutrition goal).

enum class ProgressMark(val word: String) {
    ON_TRACK("ON TRACK"),
    SHORT("SHORT"),
    OVER("OVER"),
    NO_TARGET("NO TARGET"),
}

// The eating window the expected share is spread over: nothing expected before 07:00, all of it by 21:00, linear in
// between (13:00 expects about 43 %).
const val EATING_WINDOW_START_H = 7.0
const val EATING_WINDOW_END_H = 21.0

// On track while at least this share of what the hour expects is in.
const val ON_TRACK_TOLERANCE = 0.8

// Over once the whole day's target is passed by more than this, when no upper bound of its own is given.
const val OVER_MARGIN = 1.10

data class DayProgressItem(
    val label: String,
    val unit: String,
    val have: Double,
    val target: Double?,
    // What the hour of the day expects by now (null without a target).
    val expectedNow: Double?,
    val mark: ProgressMark,
) {
    // What is left to reach the day's target (0 once reached); null without a target.
    val toGo: Double? get() = target?.let { (it - have).coerceAtLeast(0.0) }
}

fun expectedShareAt(time: LocalTime): Double {
    val h = time.hour + time.minute / 60.0
    return ((h - EATING_WINDOW_START_H) / (EATING_WINDOW_END_H - EATING_WINDOW_START_H)).coerceIn(0.0, 1.0)
}

/** [upper] is the top of a target range (water); without it the day is over at [target] × [OVER_MARGIN]. */
fun progressItem(label: String, unit: String, have: Double, target: Double?, now: LocalTime, upper: Double? = null): DayProgressItem {
    if (target == null || target <= 0.0) return DayProgressItem(label, unit, have, null, null, ProgressMark.NO_TARGET)
    val expected = target * expectedShareAt(now)
    val mark = when {
        have > (upper ?: (target * OVER_MARGIN)) -> ProgressMark.OVER
        have >= expected * ON_TRACK_TOLERANCE -> ProgressMark.ON_TRACK
        else -> ProgressMark.SHORT
    }
    return DayProgressItem(label, unit, have, target, expected, mark)
}

/** The items behind the hour's pace, in the order given: what the card lists as "to go". */
fun shortfalls(items: List<DayProgressItem>): List<DayProgressItem> = items.filter { it.mark == ProgressMark.SHORT }
