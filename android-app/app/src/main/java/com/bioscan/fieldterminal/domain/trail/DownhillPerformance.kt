package com.bioscan.fieldterminal.domain.trail

import com.bioscan.fieldterminal.domain.TimePoint
import kotlin.math.pow
import kotlin.math.sqrt

// DAV-139. Mirrors DAV-138's shape for descents: eligible-segment filtering
// (doc 04) and the same GAP formula (Minetti's polynomial already handles
// negative grade correctly, so no separate downhill formula is needed) --
// but speed, not VAM, is the headline descent figure, since "vertical speed"
// isn't how descending performance is judged the way climbing rate is.
// The actual output-per-physiological-cost *efficiency* ratio (vertical
// speed / HR or power, for both directions) is DAV-141's own job, per doc
// 03's registry -- this file only produces the speed/HR/power signals it
// needs as inputs, not a duplicate efficiency formula.

const val DOWNHILL_PERFORMANCE_MODEL_VERSION = "1"

data class DescentPerformance(
    val segment: TerrainSegment,
    val averageSpeedKmh: Double?,
    val averageGradeAdjustedPaceMinPerKm: Double?,
    val averageHeartRateBpm: Double?,
    val averagePowerWatts: Double?,
)

fun computeDownhillPerformance(
    segments: List<TerrainSegment>,
    heartRate: List<TimePoint>,
    powerW: List<TimePoint>,
    speedKmh: List<TimePoint>,
): List<DescentPerformance> {
    fun averageInRange(series: List<TimePoint>, segment: TerrainSegment): Double? =
        series.filter { it.offsetSeconds in segment.startOffsetSeconds..segment.endOffsetSeconds }
            .map { it.value }
            .takeIf { it.isNotEmpty() }
            ?.average()

    return segments
        .filter { it.direction == SegmentDirection.DOWNHILL && it.isPerformanceEligible() }
        .map { segment ->
            val avgSpeed = averageInRange(speedKmh, segment)
            val avgPace = avgSpeed?.takeIf { it > 0 }?.let { 60.0 / it }
            val gap = avgPace?.let { gradeAdjustedPaceMinPerKm(it, segment.averageGradePercent) }

            DescentPerformance(
                segment = segment,
                averageSpeedKmh = avgSpeed,
                averageGradeAdjustedPaceMinPerKm = gap,
                averageHeartRateBpm = averageInRange(heartRate, segment),
                averagePowerWatts = averageInRange(powerW, segment),
            )
        }
}

// Coefficient of variation of descent speed across a session's eligible
// descents -- same construction as climbConsistency(), null with fewer than
// two real descents to compare or when average speed data is missing.
fun descentConsistency(descents: List<DescentPerformance>): Double? {
    val speeds = descents.mapNotNull { it.averageSpeedKmh }
    if (speeds.size < 2) return null
    val mean = speeds.average()
    if (mean == 0.0) return null
    val variance = speeds.sumOf { (it - mean).pow(2) } / speeds.size
    return sqrt(variance) / mean
}
