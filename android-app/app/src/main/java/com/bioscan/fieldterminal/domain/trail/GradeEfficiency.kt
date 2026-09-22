package com.bioscan.fieldterminal.domain.trail

import com.bioscan.fieldterminal.domain.TimePoint

// DAV-141. The actual output-per-physiological-cost ratio DAV-138/139 both
// deferred here, per doc 03's registry ("same family" for regular climbing,
// hiking-only climbing, and descending -- one formula, three input filters).
// HR is the only denominator implemented for v1, per doc 02's own finding
// that no real standard exists yet for this metric; power is the documented
// upgrade path once this app has a real running-power source.

const val GRADE_EFFICIENCY_MODEL_VERSION = "1"

data class GradeEfficiency(
    // Signed: positive for a climb, negative for a descent -- matches
    // TerrainSegment.elevationChangeM's own convention.
    val verticalSpeedMetersPerHour: Double,
    val averageHeartRateBpm: Double,
    val metersPerHourPerBpm: Double,
)

// Never fabricates a ratio for a climb missing HR -- that climb just isn't
// in the returned list, not a `0` or an assumed value.
fun computeUphillEfficiency(climbs: List<ClimbPerformance>): List<GradeEfficiency> =
    climbs.mapNotNull { climb ->
        val hr = climb.averageHeartRateBpm?.takeIf { it > 0 } ?: return@mapNotNull null
        GradeEfficiency(climb.vamMetersPerHour, hr, climb.vamMetersPerHour / hr)
    }

fun computeDownhillEfficiency(descents: List<DescentPerformance>): List<GradeEfficiency> =
    descents.mapNotNull { descent ->
        val hr = descent.averageHeartRateBpm?.takeIf { it > 0 } ?: return@mapNotNull null
        // DescentPerformance's own headline figure is speed, not vertical
        // speed (DAV-139) -- derived directly from the segment here, same
        // definition VAM uses for climbs.
        val verticalSpeed = descent.segment.elevationChangeM / (descent.segment.movingDurationSeconds / 3600.0)
        GradeEfficiency(verticalSpeed, hr, verticalSpeed / hr)
    }

// A climb segment counts as hike-dominant when more of its own distance was
// hiked than run, per DAV-140's classifier re-applied to just this segment's
// time range.
fun isHikeDominant(segment: TerrainSegment, smoothed: List<SmoothedPoint>): Boolean {
    val inRange = smoothed.filter { it.trackpoint.offsetSeconds in segment.startOffsetSeconds..segment.endOffsetSeconds }
    val split = classifyLocomotion(inRange)
    return split.hikeDistanceM > split.runDistanceM
}

fun computeUphillHikingEfficiency(
    segments: List<TerrainSegment>,
    smoothed: List<SmoothedPoint>,
    heartRate: List<TimePoint>,
): List<GradeEfficiency> {
    val hikeDominantClimbs = computeUphillPerformance(segments, heartRate, powerW = emptyList(), speedKmh = emptyList())
        .filter { isHikeDominant(it.segment, smoothed) }
    return computeUphillEfficiency(hikeDominantClimbs)
}
