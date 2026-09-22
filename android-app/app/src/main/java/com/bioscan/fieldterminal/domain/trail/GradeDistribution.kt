package com.bioscan.fieldterminal.domain.trail

// DAV-136. Two independent views of the same terrain, per doc 04: grade-band
// distribution is a per-interval classification of the whole smoothed
// profile (every real interval counts, whatever its length -- doc 04's
// "structural" tier), while climb/descent structure summarizes DAV-134's
// already-segmented climbs/descents (only those pass through the hysteresis
// walk in the first place). Neither depends on the other.

const val GRADE_DISTRIBUTION_MODEL_VERSION = "1"

data class GradeBandTotals(val distanceM: Double, val movingDurationSeconds: Long)

// Grade per point-to-point interval, not per segment -- doc 04's own
// definition. An interval with either endpoint missing smoothed elevation
// contributes nothing (never guessed). Distance always counts toward its
// band even during a "stopped" interval (doc 04: only *time* is excluded);
// movingDurationSeconds is the doc 04 STOPPED_SPEED_MPS-gated portion.
fun gradeBandDistribution(smoothed: List<SmoothedPoint>): Map<GradeBand, GradeBandTotals> {
    val totals = mutableMapOf<GradeBand, GradeBandTotals>()
    for (i in 1 until smoothed.size) {
        val a = smoothed[i - 1]
        val b = smoothed[i]
        val elevationA = a.smoothedElevationM ?: continue
        val elevationB = b.smoothedElevationM ?: continue
        val distanceDelta = b.trackpoint.cumulativeDistanceM - a.trackpoint.cumulativeDistanceM
        if (distanceDelta <= 0) continue

        val grade = (elevationB - elevationA) / distanceDelta * 100.0
        val band = gradeBandFor(grade)
        val timeDelta = b.trackpoint.offsetSeconds - a.trackpoint.offsetSeconds
        val isMoving = timeDelta > 0 && distanceDelta / timeDelta >= STOPPED_SPEED_MPS
        val movingSeconds = if (isMoving) timeDelta else 0L

        val existing = totals[band] ?: GradeBandTotals(0.0, 0L)
        totals[band] = GradeBandTotals(existing.distanceM + distanceDelta, existing.movingDurationSeconds + movingSeconds)
    }
    return totals
}

// Aggregate stats for one direction (UPHILL or DOWNHILL)'s segments --
// "climb structure" and "descent equivalents" are the same shape, just
// filtered to opposite directions, per the registry's own pairing of the two.
data class DirectionStats(
    val segmentCount: Int,
    val totalDistanceM: Double,
    val averageDistanceM: Double?,
    val maxDistanceM: Double?,
    val totalElevationChangeM: Double,
    val averageElevationChangeM: Double?,
    // "Climb/descent concentration" -- how much of the whole session this
    // direction accounts for. Null when the session has no real distance.
    val distanceShareOfTotal: Double?,
)

data class ClimbDescentStructure(val uphill: DirectionStats, val downhill: DirectionStats)

fun computeClimbDescentStructure(segments: List<TerrainSegment>, totalDistanceM: Double): ClimbDescentStructure {
    fun statsFor(direction: SegmentDirection): DirectionStats {
        val matching = segments.filter { it.direction == direction }
        val distances = matching.map { it.endDistanceM - it.startDistanceM }
        val totalDistance = distances.sum()
        return DirectionStats(
            segmentCount = matching.size,
            totalDistanceM = totalDistance,
            averageDistanceM = distances.takeIf { it.isNotEmpty() }?.average(),
            maxDistanceM = distances.maxOrNull(),
            totalElevationChangeM = matching.sumOf { it.elevationChangeM },
            averageElevationChangeM = matching.map { it.elevationChangeM }.takeIf { it.isNotEmpty() }?.average(),
            distanceShareOfTotal = if (totalDistanceM > 0) totalDistance / totalDistanceM else null,
        )
    }
    return ClimbDescentStructure(uphill = statsFor(SegmentDirection.UPHILL), downhill = statsFor(SegmentDirection.DOWNHILL))
}
