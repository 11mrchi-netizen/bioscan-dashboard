package com.bioscan.fieldterminal.domain

import java.time.LocalDate

// DAV-58 (Analysis Layer 2, milestone 2). Parallel endurance load models --
// TRIMP, sRPE, pace/speed, power, threshold-relative intensity -- kept as
// independent named functions per DAV-53/55's "never collapse dimensions
// prematurely" principle, not combined into one score. See
// docs/analysis-layer-2/04-endurance-metric-adapters.md for the full design
// this implements. Zone exposure lives in HeartRateZones.kt and
// IntensityDistribution.kt, from the per-second heart rate of Zepp workouts.

// Deliberately decoupled from any specific Supabase row shape (unlike
// TrainingRepository's ExerciseSessionRow, which is that screen's own
// concern) -- a caller maps whatever it fetched into this before calling
// anything below.
data class EnduranceSessionInput(
    val durationMin: Double?,
    val distanceKm: Double?,
    val avgHr: Double?,
    val maxHr: Double?,
    val avgSpeedKmh: Double?,
    val avgPowerW: Double?,
    val rpe: Int?,
)

// Banister TRIMP: minutes x heart-rate-reserve fraction x a weighting that rises steeply with intensity. The fraction is
// (average - resting) / (maximum - resting) with the PERSON's maximum and resting heart rate as they were on the
// session's date (TrimpAnchors below), not the session's own maximum: dividing by the session's own peak made an easy
// hour at 148 bpm that peaked at 160 score about twice what it should, and every hard session look easy. The fraction is
// capped at 1 (an average above the person's maximum is a bad reading). Banister's male coefficients (0.64, 1.92), a
// documented convention rather than a fit to this person; the female pair (0.86, 1.67) is not wired to the profile yet.
// Server and console carry the same formula (analysis-core/trainingLoad.ts, console src/lib/training.ts).
private const val TRIMP_COEFFICIENT = 0.64
private const val TRIMP_EXPONENT = 1.92

fun trimp(session: EnduranceSessionInput, restingHr: Double?, personalMaxHr: Double?): Double? {
    val duration = session.durationMin?.takeIf { it > 0 } ?: return null
    val avg = session.avgHr ?: return null
    val max = personalMaxHr ?: return null
    val rest = restingHr ?: return null
    if (max <= rest) return null
    val hrRatio = ((avg - rest) / (max - rest)).coerceAtMost(1.0)
    if (hrRatio <= 0) return null
    return duration * hrRatio * TRIMP_COEFFICIENT * Math.exp(TRIMP_EXPONENT * hrRatio)
}

/**
 * TRIMP's anchors per date, from rows a load computation already has: the robust maximum (second-highest session
 * maximum of the 24 months up to the date, HeartRateZones.kt) and the 30-day median resting heart rate up to it. A
 * session older than the first resting reading uses the median of the first 30 days that have one, so a short resting
 * series does not drop older sessions. Looked up once per date.
 */
class TrimpAnchors(
    private val sessionMaxima: List<Pair<LocalDate, Double>>,
    private val restingDaily: List<Pair<LocalDate, Double>>,
) {
    private val firstResting: Double? = restingDaily.filter { it.second in 25.0..120.0 }.minOfOrNull { it.first }
        ?.let { first -> restingHrAsOf(restingDaily.filter { !it.first.isAfter(first.plusDays(29)) }, first.plusDays(29)) }
    private val maxCache = HashMap<LocalDate, Double?>()
    private val restCache = HashMap<LocalDate, Double?>()

    fun maxOn(date: LocalDate): Double? = maxCache.getOrPut(date) { robustMaxHr(sessionMaxima, date) }
    fun restingOn(date: LocalDate): Double? = restCache.getOrPut(date) { restingHrAsOf(restingDaily, date) ?: firstResting }

    fun trimpOn(date: LocalDate, session: EnduranceSessionInput): Double? = trimp(session, restingOn(date), maxOn(date))
}

/**
 * Provisional: RPE x minutes per unit of TRIMP, the median over the sessions that have both (live account, 17 sessions,
 * 2026-10-29; spread 1.5 to 15). It brings a session scored by RPE alone onto the TRIMP scale; keep it equal to the
 * analysis frame's SRPE_PER_TRIMP (supabase/functions/_shared/analysis-core/trainingLoad.ts).
 */
const val SRPE_PER_TRIMP = 3.8

// The universal fallback when HR/power/pace are all unusable -- always
// computable from what manual entry already asks for (duration + RPE).
fun sRpeLoad(session: EnduranceSessionInput): Double? {
    val rpe = session.rpe ?: return null
    val duration = session.durationMin ?: return null
    return rpe * duration
}

// Pace derived at query time when not natively stored (manual sessions
// never carry avg_speed_kmh -- see the data inventory) rather than requiring
// it to be backfilled into the row.
fun derivedPaceMinPerKm(session: EnduranceSessionInput): Double? {
    val distance = session.distanceKm ?: return null
    val duration = session.durationMin ?: return null
    if (distance <= 0) return null
    return duration / distance
}

fun effectiveSpeedKmh(session: EnduranceSessionInput): Double? {
    session.avgSpeedKmh?.let { return it }
    val distance = session.distanceKm ?: return null
    val duration = session.durationMin ?: return null
    if (duration <= 0) return null
    return distance / (duration / 60.0)
}

// Power relative to FTP -- spec'd now despite avg_power_w being populated
// on 0 of 6,558 real sessions (per the data inventory), so it activates
// automatically the day any source populates the column. ftpWatts comes
// from DAV-126's training_thresholds table (not fetched here -- this
// function's job is the math, not the lookup).
fun powerPercentOfFtp(session: EnduranceSessionInput, ftpWatts: Double?): Double? {
    val power = session.avgPowerW ?: return null
    val ftp = ftpWatts ?: return null
    if (ftp <= 0) return null
    return power / ftp * 100.0
}

// Threshold-relative intensity -- HR-based takes precedence over
// pace-based when both are available (HR is a direct physiological
// signal; pace is confounded by terrain/weather in a way HR isn't).
// Blocked in practice today since DAV-126's threshold table has no real
// rows yet -- this is the math half of that adapter, ready the moment a
// profile exists.
fun thresholdRelativeIntensityPercent(
    session: EnduranceSessionInput,
    thresholdHr: Double?,
    thresholdPaceMinPerKm: Double?,
): Double? {
    val avg = session.avgHr
    if (avg != null && thresholdHr != null && thresholdHr > 0) {
        return avg / thresholdHr * 100.0
    }
    val pace = derivedPaceMinPerKm(session) ?: session.avgSpeedKmh?.let { if (it > 0) 60.0 / it else null }
    if (pace != null && thresholdPaceMinPerKm != null && thresholdPaceMinPerKm > 0) {
        // Faster (lower) pace than threshold means >100% relative intensity --
        // inverted vs. the HR case, where higher HR means >100% too.
        return thresholdPaceMinPerKm / pace * 100.0
    }
    return null
}
