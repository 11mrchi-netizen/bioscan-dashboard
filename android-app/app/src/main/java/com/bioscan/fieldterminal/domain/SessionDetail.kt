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
)

// DAV-106. One completed km per split -- the current, not-yet-finished km is
// deliberately left off rather than shown as a fake short "split", the same
// live-tracking convention Strava/Garmin use. durationSec is the real time
// between this split's distance boundary and the previous one, found by
// linearly interpolating between the two real cumulative-distance samples
// that bracket it (DistanceRecord's own interval granularity, typically
// under a minute, bounds the interpolation error). avgHr is the mean of
// this session's real heart-rate samples whose offset falls in that window.
data class SessionSplit(val km: Int, val durationSec: Long, val avgHr: Double?)

fun computeKmSplits(distanceKm: List<TimePoint>, heartRate: List<TimePoint>): List<SessionSplit> {
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
        SessionSplit(km, sec - prevSec, avgHr).also { prevSec = sec }
    }
}
