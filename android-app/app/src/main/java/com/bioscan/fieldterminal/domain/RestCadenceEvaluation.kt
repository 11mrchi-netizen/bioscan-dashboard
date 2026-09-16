package com.bioscan.fieldterminal.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit

// Phase A4 (Analysis Layer). Evaluation Method Spec, Category 7 (Rest days &
// recovery cadence): replaces the untraceable "14-day rule" with two
// periodization-grounded signals, built on the exact same daily session_load
// map Category 6's CTL/ATL/TSB already walks (see
// domain/TrainingLoadEvaluation.kt's dailySessionLoadMap()) -- not a second,
// slightly-different derivation of the same underlying number.
//
// Signal A (acute): consecutive_days_without_rest >= 9 AND TSB < -20. TSB and
// its own 42-day gate come straight from Category 6's own
// TrainingLoadEvaluation -- this category never recomputes it.
// Signal B (cadence): weeks_since_deload, soft flag at 4 weeks, firm at 6.
// Needs 8 weeks (56 days) of load history to gate on, independent of
// Signal A's 42-day TSB gate.
//
// consecutiveDaysWithoutRest itself is NOT gated -- it's a simple, always-
// real count straight off the daily load map, and the spec's own display
// section calls for it as a standing counter independent of whether either
// flag can fire yet.
data class RestCadenceEvaluation(
    val consecutiveDaysWithoutRest: Int,
    val gateAMet: Boolean,
    val signalAFlagged: Boolean,
    val gateBMet: Boolean,
    val weeksSinceDeload: Int?,
    val deloadCadenceFlag: DeloadCadenceFlag,
    val confidence: Confidence,
)

enum class DeloadCadenceFlag { InsufficientHistory, None, Soft, Firm }

private const val SIGNAL_A_CONSECUTIVE_DAYS = 9
private const val SIGNAL_A_TSB_THRESHOLD = -20.0
private const val DELOAD_LOAD_THRESHOLD_PCT = 0.6
private const val DELOAD_SOFT_WEEKS = 4
private const val DELOAD_FIRM_WEEKS = 6
private const val SIGNAL_B_GATE_WEEKS = 8

fun evaluateRestCadence(
    sessionLoads: List<Pair<LocalDate, Double>>,
    tsb: Double?,
    tsbGateMet: Boolean,
    asOf: LocalDate = LocalDate.now(),
): RestCadenceEvaluation {
    val dailyLoad = dailySessionLoadMap(sessionLoads)

    var consecutiveDays = 0
    var walkDate = asOf
    while ((dailyLoad[walkDate] ?: 0.0) > 0.0) {
        consecutiveDays++
        walkDate = walkDate.minusDays(1)
    }

    val signalAFlagged = tsbGateMet && tsb != null &&
        consecutiveDays >= SIGNAL_A_CONSECUTIVE_DAYS && tsb < SIGNAL_A_TSB_THRESHOLD

    val earliestLoad = sessionLoads.minOfOrNull { it.first }
    val weeksOfHistory = earliestLoad?.let { (ChronoUnit.DAYS.between(it, asOf) / 7) + 1 } ?: 0L
    val gateBMet = weeksOfHistory >= SIGNAL_B_GATE_WEEKS

    // Rolling 7-day-ending-at-date grid (week 0 = the 7 days ending asOf,
    // week 1 = the 7 days before that, ...) -- this project has no ISO-week
    // concept anywhere else, so a fixed calendar-week boundary would be a new
    // convention introduced just for this one signal.
    fun weeklyLoad(weekIndex: Int): Double {
        val end = asOf.minusDays((weekIndex * 7).toLong())
        val start = end.minusDays(6)
        return dailyLoad.entries.filter { !it.key.isBefore(start) && !it.key.isAfter(end) }.sumOf { it.value }
    }

    var weeksSinceDeload: Int? = null
    var deloadFlag = DeloadCadenceFlag.InsufficientHistory

    if (gateBMet) {
        // Only search candidate weeks whose own 4-week trailing average stays
        // entirely within real tracked history -- a candidate at
        // weekIndex needs weeklyLoad(weekIndex+1..weekIndex+4), so the
        // furthest searchable week is weeksOfHistory-4.
        val maxWeekIndex = (weeksOfHistory - 4).toInt().coerceAtLeast(0)
        var found: Int? = null
        for (weekIndex in 0 until maxWeekIndex) {
            val trailing4WeekAvg = (1..4).map { weeklyLoad(weekIndex + it) }.average()
            if (trailing4WeekAvg > 0 && weeklyLoad(weekIndex) <= DELOAD_LOAD_THRESHOLD_PCT * trailing4WeekAvg) {
                found = weekIndex
                break
            }
        }
        // No qualifying week found anywhere in the searchable history is a
        // real, distinct finding from "not enough history to search" (that
        // case never reaches this branch) -- report it as "at least
        // maxWeekIndex weeks since any detectable deload," which naturally
        // resolves to Firm once maxWeekIndex clears the firm threshold.
        val weeks = found ?: maxWeekIndex
        weeksSinceDeload = weeks
        deloadFlag = when {
            weeks >= DELOAD_FIRM_WEEKS -> DeloadCadenceFlag.Firm
            weeks >= DELOAD_SOFT_WEEKS -> DeloadCadenceFlag.Soft
            else -> DeloadCadenceFlag.None
        }
    }

    return RestCadenceEvaluation(
        consecutiveDaysWithoutRest = consecutiveDays,
        gateAMet = tsbGateMet,
        signalAFlagged = signalAFlagged,
        gateBMet = gateBMet,
        weeksSinceDeload = weeksSinceDeload,
        deloadCadenceFlag = deloadFlag,
        confidence = Confidence(weeksOfHistory.toInt().coerceAtMost(SIGNAL_B_GATE_WEEKS), SIGNAL_B_GATE_WEEKS),
    )
}
