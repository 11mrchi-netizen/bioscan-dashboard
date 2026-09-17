package com.bioscan.fieldterminal.domain

// DAV-58 (Analysis Layer 2, milestone 2). Parallel endurance load models --
// TRIMP, sRPE, pace/speed, power, threshold-relative intensity -- kept as
// independent named functions per DAV-53/55's "never collapse dimensions
// prematurely" principle, not combined into one score. See
// docs/analysis-layer-2/04-endurance-metric-adapters.md for the full design
// this implements; duration/zone exposure from that doc is skipped here too
// (only a sparse daily aggregate exists, no per-session number to compute).

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

// Banister TRIMP. Real limitation found while implementing this (not in the
// original design doc): this account has no age/birthdate anywhere in the
// schema, so the age-estimated max-HR fallback the design doc sketched
// isn't actually buildable right now -- TRIMP only computes when the
// session's own measured max_hr is present (HC-sourced sessions, ~88-98% of
// the time depending on type; never present on manual entries). No
// fallback estimate is substituted; the session simply has no TRIMP value,
// same as any other missing-input case per DAV-66.
//
// Male coefficients (0.64, 1.92) per the original Banister formula -- this
// account has no sex/gender field either, so a female-specific coefficient
// pair (0.86, 1.67) isn't selectable yet. A documented, versioned default,
// not a claim of correctness for every real physiology.
private const val TRIMP_COEFFICIENT = 0.64
private const val TRIMP_EXPONENT = 1.92

fun trimp(session: EnduranceSessionInput, restingHr: Double?): Double? {
    val duration = session.durationMin ?: return null
    val avg = session.avgHr ?: return null
    val max = session.maxHr ?: return null
    val rest = restingHr ?: return null
    if (max <= rest) return null
    val hrRatio = (avg - rest) / (max - rest)
    if (hrRatio <= 0) return null
    return duration * hrRatio * TRIMP_COEFFICIENT * Math.exp(TRIMP_EXPONENT * hrRatio)
}

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
