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
        // TODO(human): find the most recent deload week and classify the
        // cadence flag.
        //
        // A "deload week" is any week whose own load is <= 60% of the
        // average of the 4 weeks immediately before it
        // (DELOAD_LOAD_THRESHOLD_PCT, weeklyLoad(weekIndex) vs. the mean of
        // weeklyLoad(weekIndex+1)..weeklyLoad(weekIndex+4)).
        //
        // Walk weekIndex outward from 0 (this week) for as many weeks as the
        // real history can support a full 4-week trailing average (i.e. stop
        // before weekIndex+4 would reach past weeksOfHistory -- don't let a
        // candidate's trailing average silently pull in weeks before this
        // account's own tracked history, where weeklyLoad() would return a
        // real 0 that isn't a real rest week, just missing data).
        //
        // Set weeksSinceDeload to the first (most recent) qualifying
        // weekIndex found. Decide what to do if none is found within that
        // search range -- there's a real, honest choice here about how to
        // represent "no deload visible in the account's whole history" that
        // isn't the same thing as "not enough history to check" (that case
        // is already handled by gateBMet above).
        //
        // Then set deloadFlag from weeksSinceDeload:
        //   >= DELOAD_FIRM_WEEKS -> Firm
        //   >= DELOAD_SOFT_WEEKS -> Soft
        //   else                 -> None
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
