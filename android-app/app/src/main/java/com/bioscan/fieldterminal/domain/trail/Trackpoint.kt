package com.bioscan.fieldterminal.domain.trail

import com.bioscan.fieldterminal.domain.RoutePoint
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// DAV-133, part 1. The normalized shape milestone 07's shared terrain engine
// (DAV-130/134/135/136) reads, regardless of which of the two real route
// sources produced it -- Health Connect's ExerciseRoute (this file's own
// trackpointsFromRoutePoints, Phase 1) or a Drive GPX file (a second adapter
// added in Phase 2, reusing haversineMeters below rather than a second copy
// of the formula).
//
// Quality is represented by nullability, not a separate synthetic flag:
// elevationM already tells a caller exactly what's missing, matching
// RoutePoint's own established convention (domain/SessionRoute.kt) -- adding
// a redundant "hasElevation" boolean would just restate what null already
// says. lat/lon can't be null coming out of Health Connect, so there is no
// second quality dimension to represent for this adapter.
data class Trackpoint(
    val offsetSeconds: Long,
    val lat: Double,
    val lon: Double,
    val elevationM: Double?,
    val cumulativeDistanceM: Double,
)

const val TRACKPOINT_MODEL_VERSION = "1"

// Health Connect's RoutePoint has no cumulative distance -- this is the one
// real thing this adapter adds, via the same haversine math
// domain/NextSession.kt's GPX path already uses (extracted here so both
// adapters share one formula instead of two copies).
fun trackpointsFromRoutePoints(points: List<RoutePoint>): List<Trackpoint> {
    var cumulative = 0.0
    return points.mapIndexed { index, point ->
        if (index > 0) {
            val prev = points[index - 1]
            cumulative += haversineMeters(prev.lat, prev.lon, point.lat, point.lon)
        }
        Trackpoint(
            offsetSeconds = point.offsetSeconds,
            lat = point.lat,
            lon = point.lon,
            elevationM = point.elevationM,
            cumulativeDistanceM = cumulative,
        )
    }
}

fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val earthRadiusM = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a1 = Math.toRadians(lat1)
    val a2 = Math.toRadians(lat2)
    val h = sin(dLat / 2).pow(2) + cos(a1) * cos(a2) * sin(dLon / 2).pow(2)
    return 2 * earthRadiusM * asin(sqrt(h))
}
