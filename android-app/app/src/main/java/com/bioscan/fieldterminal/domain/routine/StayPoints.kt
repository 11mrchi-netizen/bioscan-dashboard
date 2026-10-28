package com.bioscan.fieldterminal.domain.routine

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// Routine intelligence R2 (docs/routine-intelligence/01-research-and-plan.md, section 3.1): stays from location fixes.
// The stay-point rule: from a fix, take every following fix within maxDistanceM of it; if they span minDurationMs or more,
// that run is a stay. The collector takes fixes only during trips and once an hour while staying, so a stay usually
// shows as a trip's last fix, a few hourly fixes, and the next trip's first fix, all close together.
//
// A long silence is not a stay by itself: the collector may have been killed. Each stay carries its confidence, the
// share of its span where the collector was provably alive (fixes or heartbeats no more than maxAliveGapMs apart).

data class Fix(val ts: Long, val lat: Double, val lon: Double, val accuracyM: Double? = null)

data class Stay(
    val startTs: Long,
    val endTs: Long,
    val lat: Double,
    val lon: Double,
    val nFixes: Int,
    val confidence: Double,
    // The stay reaches the newest fix: the person may still be there.
    val open: Boolean,
)

data class StayParams(
    val maxDistanceM: Double = 150.0,
    val minDurationMs: Long = 10 * 60_000L,
    val maxAccuracyM: Double = 200.0,
    val maxAliveGapMs: Long = 90 * 60_000L,
)

private const val EARTH_RADIUS_M = 6_371_000.0

fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
    return 2 * EARTH_RADIUS_M * asin(sqrt(a.coerceIn(0.0, 1.0)))
}

// Share of [from, to] covered by alive evidence: every gap between consecutive evidence points (and the span's own ends)
// longer than maxGapMs counts as unknown beyond maxGapMs.
fun aliveShare(from: Long, to: Long, evidence: List<Long>, maxGapMs: Long): Double {
    if (to <= from) return 1.0
    val points = (evidence.filter { it in from..to } + from + to).sorted()
    var unknown = 0L
    for (i in 1 until points.size) {
        val gap = points[i] - points[i - 1]
        if (gap > maxGapMs) unknown += gap - maxGapMs
    }
    return (1.0 - unknown.toDouble() / (to - from)).coerceIn(0.0, 1.0)
}

fun detectStays(fixes: List<Fix>, heartbeats: List<Long> = emptyList(), params: StayParams = StayParams()): List<Stay> {
    val pts = fixes.filter { (it.accuracyM ?: 0.0) <= params.maxAccuracyM }.sortedBy { it.ts }.distinctBy { it.ts }
    val evidence = (pts.map { it.ts } + heartbeats).sorted()
    val stays = mutableListOf<Stay>()
    var i = 0
    while (i < pts.size) {
        var j = i + 1
        while (j < pts.size && haversineM(pts[i].lat, pts[i].lon, pts[j].lat, pts[j].lon) <= params.maxDistanceM) j++
        val run = pts.subList(i, j)
        if (run.size >= 2 && run.last().ts - run.first().ts >= params.minDurationMs) {
            stays += Stay(
                startTs = run.first().ts,
                endTs = run.last().ts,
                lat = run.sumOf { it.lat } / run.size,
                lon = run.sumOf { it.lon } / run.size,
                nFixes = run.size,
                confidence = aliveShare(run.first().ts, run.last().ts, evidence, params.maxAliveGapMs),
                open = j == pts.size,
            )
            i = j
        } else {
            i++
        }
    }
    return stays
}

// TripRecordingService's fallback end of a trip when no STILL transition arrives: the last windowMs of fixes (at least three,
// spanning most of the window) all lie within radiusM of the newest one.
fun isSettled(fixes: List<Fix>, now: Long, windowMs: Long = 10 * 60_000L, radiusM: Double = 100.0): Boolean {
    val recent = fixes.filter { now - it.ts in 0..windowMs }.sortedBy { it.ts }
    if (recent.size < 3 || recent.last().ts - recent.first().ts < windowMs * 4 / 5) return false
    val last = recent.last()
    return recent.all { haversineM(it.lat, it.lon, last.lat, last.lon) <= radiusM }
}
