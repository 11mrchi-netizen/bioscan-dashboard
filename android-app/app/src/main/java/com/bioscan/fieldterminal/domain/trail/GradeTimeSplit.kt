package com.bioscan.fieldterminal.domain.trail

import com.bioscan.fieldterminal.domain.RoutePoint

// Run session page, TRAIL: how the moving time split between climbing, flat and descending. The track is cut into
// stretches of at least GRADE_SPLIT_MIN_M (one GPS point to the next is too noisy for a grade), each stretch's grade from
// the smoothed elevation, flat being GradeBand.FLAT (-3 % to +3 %). A step longer than GRADE_SPLIT_MAX_STEP_SEC is a
// pause or a lost fix and counts no time.
const val GRADE_SPLIT_MIN_M = 25.0
const val GRADE_SPLIT_MAX_STEP_SEC = 30L

data class GradeTimeSplit(val uphillSec: Long, val flatSec: Long, val downhillSec: Long) {
    val totalSec: Long get() = uphillSec + flatSec + downhillSec
    fun share(sec: Long): Double = if (totalSec > 0) sec.toDouble() / totalSec else 0.0
}

fun gradeTimeSplit(points: List<RoutePoint>): GradeTimeSplit? {
    val track = smoothElevation(trackpointsFromRoutePoints(points)).filter { it.smoothedElevationM != null }
    if (track.size < 2) return null
    var up = 0L
    var flat = 0L
    var down = 0L
    var start = 0
    var movingSec = 0L
    for (i in 1 until track.size) {
        val step = track[i].trackpoint.offsetSeconds - track[i - 1].trackpoint.offsetSeconds
        if (step in 1..GRADE_SPLIT_MAX_STEP_SEC) movingSec += step
        val meters = track[i].trackpoint.cumulativeDistanceM - track[start].trackpoint.cumulativeDistanceM
        if (meters < GRADE_SPLIT_MIN_M) continue
        val grade = (track[i].smoothedElevationM!! - track[start].smoothedElevationM!!) / meters * 100
        when (gradeBandFor(grade)) {
            GradeBand.FLAT -> flat += movingSec
            GradeBand.GENTLE_UP, GradeBand.MODERATE_UP, GradeBand.STEEP_UP -> up += movingSec
            else -> down += movingSec
        }
        start = i
        movingSec = 0
    }
    return GradeTimeSplit(up, flat, down).takeIf { it.totalSec > 0 }
}
