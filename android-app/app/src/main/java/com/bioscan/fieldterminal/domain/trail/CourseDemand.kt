package com.bioscan.fieldterminal.domain.trail

// DAV-135, formulas from docs/trail-intelligence/02-trail-metric-conventions.md
// and docs/trail-intelligence/03-trail-metric-registry.md. Course demand is
// "how hard is this route," independent of how it was actually run -- these
// are the metrics shared between Phase 1 (a completed session's Health
// Connect route) and Phase 2 (a planned Drive GPX preview), per doc 03.

const val COURSE_DEMAND_MODEL_VERSION = "1"

data class CourseDemand(
    val totalDistanceM: Double,
    // Null when there's no real route (< 2 trackpoints) to sum, never a
    // fabricated 0.0 -- totalElevationGainM/LossM's own sum-of-empty-list
    // would otherwise silently read the same as "a real, flat course."
    val elevationGainM: Double?,
    val elevationLossM: Double?,
    // "Total vertical" -- the full vertical relief covered, up and down.
    val totalVerticalM: Double?,
    // m/km. Also known as "vertical density" (doc 03's registry aliases it
    // rather than treating it as a second metric) -- null when there's no
    // real distance to divide by, never a fabricated 0.
    val mountainIndex: Double?,
    // Distance-in-km-equivalent, FFA/ITRA formula (doc 02): distance +
    // gain/100. Same null-on-zero-distance rule as mountainIndex.
    val kmEffort: Double?,
)

fun computeCourseDemand(points: List<Trackpoint>, segments: List<TerrainSegment>): CourseDemand {
    val totalDistanceM = points.lastOrNull()?.cumulativeDistanceM ?: 0.0
    val hasRoute = points.size >= 2
    val gain = if (hasRoute) totalElevationGainM(segments) else null
    val loss = if (hasRoute) totalElevationLossM(segments) else null
    val distanceKm = totalDistanceM / 1000.0

    return CourseDemand(
        totalDistanceM = totalDistanceM,
        elevationGainM = gain,
        elevationLossM = loss,
        totalVerticalM = if (gain != null && loss != null) gain + loss else null,
        mountainIndex = if (distanceKm > 0 && gain != null) gain / distanceKm else null,
        kmEffort = if (distanceKm > 0 && gain != null) distanceKm + gain / 100.0 else null,
    )
}
