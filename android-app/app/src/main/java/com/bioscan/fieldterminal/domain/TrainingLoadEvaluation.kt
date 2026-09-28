package com.bioscan.fieldterminal.domain

import java.time.LocalDate
import java.time.temporal.ChronoUnit

// Phase A4 (Analysis Layer). Evaluation Method Spec, Category 6 (Training
// load & running performance): retires ACWR in favor of the Banister-
// derived CTL/ATL/TSB Performance Management Chart. This app's own Android
// TrainingRepository never had ACWR to begin with -- only index.html's web
// dashboard carries the old metric, untouched by this phase (see ROADMAP.md's
// Phase A context: this effort is mobile-first, the web dashboard stays as-is).
//
// session_load = duration_minutes * sRPE, per the spec's own formula. The
// offered alternative (HR-based TRIMP) needs per-minute heart-rate data this
// app doesn't persist for logged sessions (only avg_hr per session survives
// to Supabase), so sRPE is the only one actually computable from stored
// data. A session with no logged RPE contributes nothing to that day's
// load -- never impute, same convention every other category already uses.
//
// GAP (Grade Adjusted Pace) and Efficiency Factor are not computed here
// (this file only does load). For Zepp-synced runs they are computed in the
// zepp-extract decoder from the persisted per-second altitude/speed/HR and
// stored on zepp_workout_detail.decoded.summary (DAV-272). Health-Connect-only
// runs still have no per-point elevation persisted, so they get neither.
data class TrainingLoadEvaluation(
    val state: EvalState,
    val confidence: Confidence,
    val ctl: Double?,
    val atl: Double?,
    val tsb: Double?,
    val tsbBand: String?,
)

private const val CTL_TAU_DAYS = 42.0
private const val ATL_TAU_DAYS = 7.0
private const val CTL_GATE_DAYS = 42

// Shared by Category 7 (domain/RestCadenceEvaluation.kt), so both categories
// walk the identical daily total rather than two slightly-different ones.
// Same-day sessions (this account's real data has one exact duplicate --
// two identical rows, same timestamp/duration/rpe -- flagged during Phase A4
// verification, not silently deduplicated here) are summed into one daily
// total.
fun dailySessionLoadMap(sessionLoads: List<Pair<LocalDate, Double>>): Map<LocalDate, Double> =
    sessionLoads.groupBy { it.first }.mapValues { (_, v) -> v.sumOf { it.second } }

// sessionLoads: one (date, session_load) pair per exercise session that has
// both duration_min and rpe logged -- multiple sessions on the same day are
// summed into that day's total load before the EWMA walk.
fun evaluateTrainingLoad(sessionLoads: List<Pair<LocalDate, Double>>, asOf: LocalDate = LocalDate.now()): TrainingLoadEvaluation {
    if (sessionLoads.isEmpty()) return TrainingLoadEvaluation(EvalState.NoData, Confidence(0, CTL_GATE_DAYS), null, null, null, null)

    val earliest = sessionLoads.minOf { it.first }
    val daysOfHistory = (ChronoUnit.DAYS.between(earliest, asOf) + 1).toInt().coerceAtMost(CTL_GATE_DAYS)
    if (ChronoUnit.DAYS.between(earliest, asOf) < CTL_GATE_DAYS - 1) {
        return TrainingLoadEvaluation(EvalState.Building, Confidence(daysOfHistory, CTL_GATE_DAYS), null, null, null, null)
    }

    // CTL/ATL are EWMAs over a continuous daily series -- a rest day is a
    // real 0 to walk through, not a gap to skip, unlike the gap-tolerant
    // date-filtered windows Categories 1/2/5 use. dualEwma() (domain/Stats.kt,
    // DAV-59) is this same walk generalized for reuse across load dimensions.
    val dailyLoad = dailySessionLoadMap(sessionLoads)
    val (ctl, atl) = dualEwma(dailyLoad, earliest, asOf, ATL_TAU_DAYS, CTL_TAU_DAYS)

    // TSB = CTL_yesterday - ATL_yesterday, per the spec's own formula --
    // "yesterday" because today's own session (if any) hasn't yet been
    // absorbed into the fitness/fatigue trend it's being judged against.
    val (ctlYesterday, atlYesterday) = dualEwma(dailyLoad, earliest, asOf.minusDays(1), ATL_TAU_DAYS, CTL_TAU_DAYS)
    val tsb = ctlYesterday - atlYesterday
    val state = when {
        tsb < TSB_HEAVILY_LOADED_BELOW -> EvalState.Unstable // "heavily loaded" -- the spec's own flagged band
        tsb < TSB_LOADED_BELOW -> EvalState.ShiftDown // loaded
        tsb <= TSB_FRESHENED_FROM -> EvalState.Stable // neutral
        else -> EvalState.ShiftUp // freshened or detrained/very fresh -- both "more rested than built-up"
    }

    return TrainingLoadEvaluation(state, Confidence(daysOfHistory, CTL_GATE_DAYS), ctl, atl, tsb, tsbBandLabel(tsb))
}

// TrainingPeaks' published conventions, framed descriptively per the spec --
// not norms, not medical thresholds.
private fun tsbBandLabel(tsb: Double): String = when {
    tsb > TSB_DETRAINED_ABOVE -> "Detrained / very fresh"
    tsb >= TSB_FRESHENED_FROM -> "Freshened"
    tsb >= TSB_LOADED_BELOW -> "Neutral"
    tsb >= TSB_HEAVILY_LOADED_BELOW -> "Loaded"
    else -> "Heavily loaded"
}

// Shared by tsbBandLabel(), the state mapping above and the Training tab's
// banded gauge, so the gauge's colored bands can't drift from the label.
const val TSB_HEAVILY_LOADED_BELOW = -30.0
const val TSB_LOADED_BELOW = -10.0
const val TSB_FRESHENED_FROM = 5.0
const val TSB_DETRAINED_ABOVE = 25.0

data class TrainingLoadPoint(val date: LocalDate, val ctl: Double, val atl: Double, val tsb: Double)

// One daily point per day from the first session to asOf, for the Load tab's
// history charts. Same EWMA as dualEwma() but recording every day instead of
// only the final pair; TSB(d) = CTL(d-1) - ATL(d-1) keeps evaluateTrainingLoad's
// "yesterday" convention, so the last point's tsb equals its tsb.
fun trainingLoadSeries(sessionLoads: List<Pair<LocalDate, Double>>, asOf: LocalDate = LocalDate.now()): List<TrainingLoadPoint> {
    if (sessionLoads.isEmpty()) return emptyList()
    val dailyLoad = dailySessionLoadMap(sessionLoads)
    val alphaAtl = 2.0 / (ATL_TAU_DAYS + 1.0)
    val alphaCtl = 2.0 / (CTL_TAU_DAYS + 1.0)
    val earliest = sessionLoads.minOf { it.first }
    // Same seeding as dualEwma() (domain/Stats.kt): start from the first
    // observed load, not 0, so this walk doesn't drift from evaluateTrainingLoad's.
    val firstObserved = dailyLoad[earliest] ?: 0.0
    var ctl = firstObserved
    var atl = firstObserved
    var date = earliest
    val out = mutableListOf<TrainingLoadPoint>()
    while (!date.isAfter(asOf)) {
        val tsb = ctl - atl // still yesterday's values here
        val load = dailyLoad[date] ?: 0.0
        atl += alphaAtl * (load - atl)
        ctl += alphaCtl * (load - ctl)
        out += TrainingLoadPoint(date, ctl, atl, tsb)
        date = date.plusDays(1)
    }
    return out
}
