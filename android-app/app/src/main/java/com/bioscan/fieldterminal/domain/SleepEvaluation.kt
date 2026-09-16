package com.bioscan.fieldterminal.domain

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs

// Phase A2 (Analysis Layer). Evaluation Method Spec, Category 2 (Sleep):
// three separate sub-metrics, deliberately never combined into one sleep
// score (proprietary sleep scores are unauditable -- this app doesn't clone
// one). Duration reuses Category 1's SWC-band machinery verbatim (the spec's
// own "same machinery as Category 1, reused" instruction); SRI and
// respiratory-rate anomaly are their own real algorithms below.

// Duration: 7-day rolling mean vs personal 60-day baseline +/- SWC.
fun evaluateSleepDuration(hoursDaily: List<Pair<LocalDate, Double>>, asOf: LocalDate = LocalDate.now()): SwcEvaluation =
    evaluateSwcStream(hoursDaily, asOf)

// One real night's sleep window, as absolute instants (not local-date-only)
// since the SRI algorithm needs real clock-time-of-day, and a night's
// bedtime/wake_time can straddle midnight.
data class SleepNight(val date: LocalDate, val bedtime: Instant, val wakeTime: Instant)

private const val SRI_BIN_MINUTES = 5
private const val SRI_BINS_PER_DAY = 1440 / SRI_BIN_MINUTES // 288
private const val SRI_MIN_NIGHTS = 14
private const val SRI_MAX_GAP_DAYS = 2L

data class SriEvaluation(val value: Double?, val confidence: Confidence)

// Sleep Regularity Index: the probability that sleep/wake state at any given
// clock minute matches the state 24h later, over a rolling window of
// consecutive-ish nights. Formula (spec):
//   SRI = -100 + (200 / (M*(N-1))) * sum-over-days sum-over-bins delta(s[i,j], s[i+1,j])
// Binned to 5-minute epochs (288 bins/day) per the spec's own "practical
// implementation" note. Gaps > 2 nights degrade the index badly per the
// spec -- rather than compute a misleading number, this returns BUILDING.
fun evaluateSri(nights: List<SleepNight>, zone: ZoneId = ZoneId.systemDefault(), asOf: LocalDate = LocalDate.now()): SriEvaluation {
    val recent = nights.filter { !it.date.isAfter(asOf) }.sortedBy { it.date }
    if (recent.size < SRI_MIN_NIGHTS) return SriEvaluation(null, Confidence(recent.size, SRI_MIN_NIGHTS))

    val windowed = recent.filter { ChronoUnit.DAYS.between(it.date, asOf) < 60 } // generous cap, not a hard requirement
    for (i in 1 until windowed.size) {
        if (ChronoUnit.DAYS.between(windowed[i - 1].date, windowed[i].date) > SRI_MAX_GAP_DAYS) {
            return SriEvaluation(null, Confidence(windowed.size, SRI_MIN_NIGHTS))
        }
    }
    if (windowed.size < SRI_MIN_NIGHTS) return SriEvaluation(null, Confidence(windowed.size, SRI_MIN_NIGHTS))

    fun isAsleep(instant: Instant): Boolean = windowed.any { instant >= it.bedtime && instant < it.wakeTime }
    fun binsFor(date: LocalDate): BooleanArray {
        val midnight = date.atStartOfDay(zone).toInstant()
        return BooleanArray(SRI_BINS_PER_DAY) { j -> isAsleep(midnight.plusSeconds(j * SRI_BIN_MINUTES * 60L)) }
    }

    val first = windowed.first().date
    val last = windowed.last().date
    val allDates = generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.toList()
    val binsByDate = allDates.associateWith { binsFor(it) }

    var matchSum = 0L
    val n = allDates.size
    for (i in 0 until n - 1) {
        val a = binsByDate.getValue(allDates[i])
        val b = binsByDate.getValue(allDates[i + 1])
        for (j in 0 until SRI_BINS_PER_DAY) if (a[j] == b[j]) matchSum++
    }

    val sri = -100.0 + (200.0 / (SRI_BINS_PER_DAY * (n - 1))) * matchSum
    return SriEvaluation(sri, Confidence(windowed.size, SRI_MIN_NIGHTS))
}

private const val RR_WINDOW_DAYS = 14

data class RespiratoryAnomalyEvaluation(val flagged: Boolean, val baseline: Double?, val confidence: Confidence)

// Flags only when BOTH of the last two nights sit outside the personal
// 14-night baseline +/- 2 SD -- a single anomalous night is noise, per the
// spec's own two-consecutive-night rule (and its explicit non-medical guard:
// this is a "physiological anomaly" label, never "illness").
fun evaluateRespiratoryAnomaly(rrDaily: List<Pair<LocalDate, Double>>, asOf: LocalDate = LocalDate.now()): RespiratoryAnomalyEvaluation {
    val window = rrDaily.filter { ChronoUnit.DAYS.between(it.first, asOf) < RR_WINDOW_DAYS && !it.first.isAfter(asOf) }
    if (window.size < RR_WINDOW_DAYS) return RespiratoryAnomalyEvaluation(false, null, Confidence(window.size, RR_WINDOW_DAYS))

    val baseline = mean(window.map { it.second })
    val sd = populationStdDev(window.map { it.second })
    val tonight = window.firstOrNull { it.first == asOf }?.second
    val lastNight = window.firstOrNull { it.first == asOf.minusDays(1) }?.second
    val flagged = tonight != null && lastNight != null && sd > 0 &&
        abs(tonight - baseline) > 2 * sd && abs(lastNight - baseline) > 2 * sd

    return RespiratoryAnomalyEvaluation(flagged, baseline, Confidence(window.size, RR_WINDOW_DAYS))
}
