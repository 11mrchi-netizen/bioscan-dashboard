package com.bioscan.fieldterminal.domain.trail

// The compact course an event keeps (athlete_events.course_profile): the smoothed elevation every [COURSE_PROFILE_STEP_M]
// metres along the route. Enough to cut the course into climbs and descents again and to time it, without keeping the
// GPX file; a 100 km course is about a thousand points.

const val COURSE_PROFILE_STEP_M = 100.0
const val COURSE_PROFILE_MODEL_VERSION = "1"

data class CoursePoint(val distanceM: Double, val elevationM: Double?)

data class CourseProfile(
    val points: List<CoursePoint>,
    val distanceM: Double,
    val gainM: Double?,
    val lossM: Double?,
    val startLat: Double?,
    val startLon: Double?,
    val startElevationM: Double?,
)

/** The profile of a GPX route: smoothed with the terrain engine's rules, then sampled every [stepM] metres. */
fun courseProfileFromTrackpoints(points: List<Trackpoint>, stepM: Double = COURSE_PROFILE_STEP_M): CourseProfile {
    val smoothed = smoothElevation(points)
    val segments = segmentClimbsAndDescents(smoothed)
    val demand = computeCourseDemand(points, segments)
    val sampled = resample(smoothed, stepM)
    return CourseProfile(
        points = sampled,
        distanceM = demand.totalDistanceM,
        gainM = demand.elevationGainM,
        lossM = demand.elevationLossM,
        startLat = points.firstOrNull()?.lat,
        startLon = points.firstOrNull()?.lon,
        startElevationM = sampled.firstOrNull()?.elevationM,
    )
}

fun courseProfileFromGpx(gpxText: String, stepM: Double = COURSE_PROFILE_STEP_M): CourseProfile =
    courseProfileFromTrackpoints(trackpointsFromGpx(gpxText), stepM)

// Linear interpolation of the smoothed elevation at every step, plus the last point, so the profile ends where the
// course does.
private fun resample(smoothed: List<SmoothedPoint>, stepM: Double): List<CoursePoint> {
    if (smoothed.isEmpty()) return emptyList()
    val total = smoothed.last().trackpoint.cumulativeDistanceM
    val out = mutableListOf<CoursePoint>()
    var i = 0
    var d = 0.0
    while (d <= total) {
        while (i < smoothed.lastIndex && smoothed[i + 1].trackpoint.cumulativeDistanceM < d) i++
        out += CoursePoint(d, elevationAt(smoothed, i, d))
        d += stepM
    }
    if (out.last().distanceM < total) out += CoursePoint(total, smoothed.last().smoothedElevationM)
    return out
}

private fun elevationAt(smoothed: List<SmoothedPoint>, i: Int, d: Double): Double? {
    val a = smoothed[i]
    val b = smoothed.getOrNull(i + 1) ?: return a.smoothedElevationM
    val ea = a.smoothedElevationM
    val eb = b.smoothedElevationM
    if (ea == null || eb == null) return ea ?: eb
    val span = b.trackpoint.cumulativeDistanceM - a.trackpoint.cumulativeDistanceM
    if (span <= 0) return ea
    val f = ((d - a.trackpoint.cumulativeDistanceM) / span).coerceIn(0.0, 1.0)
    return ea + (eb - ea) * f
}

/** Course points rebuilt as trackpoints for the terrain engine (no position or time: distance and elevation only). */
fun trackpointsFromCourse(points: List<CoursePoint>): List<Trackpoint> =
    points.mapIndexed { index, p -> Trackpoint(offsetSeconds = index.toLong(), lat = 0.0, lon = 0.0, elevationM = p.elevationM, cumulativeDistanceM = p.distanceM) }
