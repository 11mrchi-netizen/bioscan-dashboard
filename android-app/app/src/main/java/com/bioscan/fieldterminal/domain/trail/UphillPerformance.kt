package com.bioscan.fieldterminal.domain.trail

import com.bioscan.fieldterminal.domain.TimePoint
import kotlin.math.pow
import kotlin.math.sqrt

// DAV-138. Per-climb performance -- only ever computed for a performance-
// eligible segment (doc 04's >=100m/>=60s bar); a real but short climb still
// counts structurally (DAV-136) but never gets a VAM/GAP number here.

const val UPHILL_PERFORMANCE_MODEL_VERSION = "1"

// Minetti et al. 2002 (J Appl Physiol) metabolic cost of transport, J/kg/m,
// fit across -45% to +45% grade -- doc 02's canonical choice over
// reverse-engineering Strava's/TrainingPeaks' proprietary GAP/NGP.
// gradeFraction is positive uphill, negative downhill (0.05 = 5% climb).
fun minettiCostOfTransport(gradeFraction: Double): Double {
    val i = gradeFraction
    return 155.4 * i.pow(5) - 30.4 * i.pow(4) - 43.3 * i.pow(3) + 46.3 * i.pow(2) + 19.5 * i + 3.6
}

// Actual pace divided by the grade's cost multiplier relative to flat
// ground -- doc 02's own worked example: an 8:00/mi climb at 5% grade reads
// as roughly a 6:12/mi flat-equivalent effort.
fun gradeAdjustedPaceMinPerKm(actualPaceMinPerKm: Double, gradePercent: Double): Double {
    val flatCost = minettiCostOfTransport(0.0)
    val gradeCost = minettiCostOfTransport(gradePercent / 100.0)
    return actualPaceMinPerKm * (flatCost / gradeCost)
}

data class ClimbPerformance(
    val segment: TerrainSegment,
    val vamMetersPerHour: Double,
    val averageGradeAdjustedPaceMinPerKm: Double?,
    val averageHeartRateBpm: Double?,
    val averagePowerWatts: Double?,
)

fun computeUphillPerformance(
    segments: List<TerrainSegment>,
    heartRate: List<TimePoint>,
    powerW: List<TimePoint>,
    speedKmh: List<TimePoint>,
): List<ClimbPerformance> {
    fun averageInRange(series: List<TimePoint>, segment: TerrainSegment): Double? =
        series.filter { it.offsetSeconds in segment.startOffsetSeconds..segment.endOffsetSeconds }
            .map { it.value }
            .takeIf { it.isNotEmpty() }
            ?.average()

    return segments
        .filter { it.direction == SegmentDirection.UPHILL && it.isPerformanceEligible() }
        .map { segment ->
            val vam = segment.elevationChangeM / (segment.movingDurationSeconds / 3600.0)
            val avgSpeedKmh = averageInRange(speedKmh, segment)
            val avgPaceMinPerKm = avgSpeedKmh?.takeIf { it > 0 }?.let { 60.0 / it }
            val gap = avgPaceMinPerKm?.let { gradeAdjustedPaceMinPerKm(it, segment.averageGradePercent) }

            ClimbPerformance(
                segment = segment,
                vamMetersPerHour = vam,
                averageGradeAdjustedPaceMinPerKm = gap,
                averageHeartRateBpm = averageInRange(heartRate, segment),
                averagePowerWatts = averageInRange(powerW, segment),
            )
        }
}

// Coefficient of variation of VAM across a session's eligible climbs -- lower
// means more consistent effort climb to climb. Null with fewer than two
// climbs, since there's nothing real to compare.
fun climbConsistency(climbs: List<ClimbPerformance>): Double? {
    if (climbs.size < 2) return null
    val vams = climbs.map { it.vamMetersPerHour }
    val mean = vams.average()
    if (mean == 0.0) return null
    val variance = vams.sumOf { (it - mean).pow(2) } / vams.size
    return sqrt(variance) / mean
}
