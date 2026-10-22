package com.bioscan.fieldterminal.domain

// Phase G4. Plain, framework-free time-series shape for a single exercise
// session's on-demand Health Connect detail -- offsetSeconds is elapsed time
// from the session's own start, not a wall-clock timestamp, so it plots
// directly against a shared "minutes into the session" axis regardless of
// when the session happened. Never persisted to Supabase (same principle
// Step 14's GPX route already established for Drive: fetch fresh on demand,
// store nothing) -- see data/SessionDetailRepository.kt.
data class TimePoint(val offsetSeconds: Long, val value: Double)

// caloriesKcal (Phase G5) is a running sum, not a raw sample series like the
// other three -- ActiveCaloriesBurnedRecord only reports kcal per interval
// (same interval-not-sample shape as ElevationGainedRecord), so
// SessionDetailRepository sums successive intervals into a stepped
// cumulative curve rather than reading anything HC calls a "sample."
//
// distanceKm (DAV-106) is the same interval-accumulation shape as
// caloriesKcal, from DistanceRecord -- read only to derive computeKmSplits()
// below, never shown as its own chart signal (the ticket's switchable chart
// is HR/PACE/POWER/CAL only).
data class SessionDetail(
    val heartRate: List<TimePoint>,
    val speedKmh: List<TimePoint>,
    val powerW: List<TimePoint>,
    val caloriesKcal: List<TimePoint>,
    val distanceKm: List<TimePoint>,
    // DAV-115/123: real per-second cadence, decoded from Zepp's detail.json
    // (docs/zepp-integration/03-workout-detail-field-decode.md) -- Health
    // Connect has no equivalent source for this, so it's always empty unless
    // a matched Zepp workout exists. Defaulted so the one positional-args
    // call site (SessionDetailRepository.loadTimeSeries) doesn't need touching.
    val cadenceSpm: List<TimePoint> = emptyList(),
    val verticalRatioPct: List<TimePoint> = emptyList(),
    // Run session page: the workout's pauses, from Zepp (seconds from the start); empty without a Zepp workout.
    val pauses: List<SessionPause> = emptyList(),
)

data class SessionPause(val startOffset: Long, val durationSec: Long) {
    val endOffset: Long get() = startOffset + durationSec
}

/** Seconds of [pauses] that fall between offsets [from] and [to]. */
fun pausedSecondsBetween(pauses: List<SessionPause>, from: Long, to: Long): Long =
    pauses.sumOf { p -> (minOf(to, p.endOffset) - maxOf(from, p.startOffset)).coerceAtLeast(0L) }

/**
 * An elapsed offset on the moving-time axis: the pause time before it taken out, so a chart runs on through a stop
 * instead of leaving it as an empty stretch. A moment inside a pause sits at the pause's start. [pauses] are
 * (start, end) offsets.
 */
fun movingOffset(offset: Long, pauses: List<Pair<Long, Long>>): Long =
    offset - pauses.sumOf { (from, to) -> (minOf(offset, to) - from).coerceAtLeast(0L) }

// DAV-115/123: prefer Zepp's real per-second series (heartRate/speedKmh/
// distanceKm) over Health Connect's when a matched Zepp workout exists --
// real recorded data over Health Connect's own reconstruction/estimate,
// per-series rather than all-or-nothing since Zepp's decode doesn't cover
// power or calories at all. Falls back to Health Connect's own series
// wherever Zepp has none (e.g. an indoor session with no GPS/pace/speed).
fun mergePreferZepp(zepp: SessionDetail?, healthConnect: SessionDetail): SessionDetail {
    if (zepp == null) return healthConnect
    return SessionDetail(
        heartRate = zepp.heartRate.ifEmpty { healthConnect.heartRate },
        speedKmh = zepp.speedKmh.ifEmpty { healthConnect.speedKmh },
        powerW = healthConnect.powerW,
        caloriesKcal = healthConnect.caloriesKcal,
        distanceKm = zepp.distanceKm.ifEmpty { healthConnect.distanceKm },
        cadenceSpm = zepp.cadenceSpm,
        verticalRatioPct = zepp.verticalRatioPct,
        pauses = zepp.pauses,
    )
}

// DAV-106. One completed km per split -- the current, not-yet-finished km is
// deliberately left off rather than shown as a fake short "split", the same
// live-tracking convention Strava/Garmin use. durationSec is the real time
// between this split's distance boundary and the previous one, found by
// linearly interpolating between the two real cumulative-distance samples
// that bracket it (DistanceRecord's own interval granularity, typically
// under a minute, bounds the interpolation error). avgHr is the mean of
// this session's real heart-rate samples whose offset falls in that window.
// Run session page: durationSec is moving time (a pause inside the km left out); elapsedSec is the km's clock time,
// set only when a pause fell inside it.
data class SessionSplit(val km: Int, val durationSec: Long, val avgHr: Double?, val elapsedSec: Long? = null)

fun computeKmSplits(distanceKm: List<TimePoint>, heartRate: List<TimePoint>, pauses: List<SessionPause> = emptyList()): List<SessionSplit> {
    val totalKm = distanceKm.lastOrNull()?.value?.toInt() ?: 0
    if (distanceKm.size < 2 || totalKm < 1) return emptyList()

    fun timeAtDistance(target: Double): Long {
        val idx = distanceKm.indexOfFirst { it.value >= target }
        if (idx <= 0) return distanceKm.first().offsetSeconds
        val a = distanceKm[idx - 1]
        val b = distanceKm[idx]
        if (b.value == a.value) return b.offsetSeconds
        val frac = (target - a.value) / (b.value - a.value)
        return a.offsetSeconds + (frac * (b.offsetSeconds - a.offsetSeconds)).toLong()
    }

    var prevSec = 0L
    return (1..totalKm).map { km ->
        val sec = timeAtDistance(km.toDouble())
        val avgHr = heartRate.filter { it.offsetSeconds in prevSec..sec }
            .takeIf { it.isNotEmpty() }
            ?.let { samples -> samples.map { it.value }.average() }
        val paused = pausedSecondsBetween(pauses, prevSec, sec)
        SessionSplit(km, sec - prevSec - paused, avgHr, elapsedSec = if (paused > 0) sec - prevSec else null).also { prevSec = sec }
    }
}

// The stored avg_speed_kmh is not a pace to show: the Health Connect sync writes a plain mean of the speed samples, and
// Zepp's avg_pace (seconds per metre; read as seconds per km until Oct 2026, which stored thousands of km/h) does not
// match distance over time on some interval and hill sessions. A session's average speed is its distance over moving
// time: the shorter of the stored duration and the Zepp workout's elapsed time less its pauses (overlap-matched rows
// already store moving time; the rest store elapsed). The stored value, when believable for the type (the sync's own
// limits, zepp-extract/workoutDistance.ts), is only the fallback without a distance or a duration.
private val MAX_AVG_SPEED_KMH = mapOf("run" to 30.0, "walk" to 12.0, "hike" to 15.0, "ride" to 90.0)

fun sessionAvgSpeedKmh(type: String?, storedKmh: Double?, distanceKm: Double?, durationMin: Double?, movingMin: Double? = null): Double? {
    val max = MAX_AVG_SPEED_KMH[type] ?: 120.0
    val minutes = listOfNotNull(durationMin, movingMin).filter { it > 0 }.minOrNull()
    if (distanceKm != null && distanceKm > 0 && minutes != null) {
        (distanceKm / (minutes / 60.0)).takeIf { it <= max }?.let { return it }
    }
    return storedKmh?.takeIf { it > 0 && it <= max }
}

/** Moving minutes of a session with a time series: its elapsed span less the pauses in it; null without a series. */
fun SessionDetail.movingMinutes(): Double? {
    val elapsed = listOf(distanceKm, speedKmh, heartRate).mapNotNull { it.lastOrNull()?.offsetSeconds }.maxOrNull() ?: return null
    return ((elapsed - pausedSecondsBetween(pauses, 0, elapsed)) / 60.0).takeIf { it > 0 }
}
