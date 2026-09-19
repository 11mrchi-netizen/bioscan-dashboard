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
// GAP (Grade Adjusted Pace) and Efficiency Factor are NOT built in this
// pass, and not because of a null check that happened to fail: real GAP
// needs a continuous elevation-vs-distance profile to grade-adjust each
// segment (the Minetti polynomial), and this app stores only a session's
// total elevation_gain_m (itself unpopulated on every real row today) --
// even fully populated, a single total-ascent number can't reconstruct a
// per-point gradient. That data only exists as Health Connect's per-point
// ExerciseRoute, fetched on demand for one session's own detail screen
// (see data/SessionDetailRepository.kt) and never persisted back into daily
// training-load data. Flagged here as a real, structural gap -- not solved
// by approximating from a number that doesn't tell you what it needs to.
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
        tsb < -30 -> EvalState.Unstable // "heavily loaded" -- the spec's own flagged band
        tsb < -10 -> EvalState.ShiftDown // loaded
        tsb <= 5 -> EvalState.Stable // neutral
        else -> EvalState.ShiftUp // freshened or detrained/very fresh -- both "more rested than built-up"
    }

    return TrainingLoadEvaluation(state, Confidence(daysOfHistory, CTL_GATE_DAYS), ctl, atl, tsb, tsbBandLabel(tsb))
}

// TrainingPeaks' published conventions, framed descriptively per the spec --
// not norms, not medical thresholds.
private fun tsbBandLabel(tsb: Double): String = when {
    tsb > 25 -> "Detrained / very fresh"
    tsb >= 5 -> "Freshened"
    tsb >= -10 -> "Neutral"
    tsb >= -30 -> "Loaded"
    else -> "Heavily loaded"
}
