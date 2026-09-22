package com.bioscan.fieldterminal.domain.trail

import com.bioscan.fieldterminal.domain.TimePoint

// DAV-142. Early-vs-late comparisons, per doc 04's comparability rule
// (matched grade within +-3pp, length within 2x) so a "degradation" number
// never compares a short steep pitch against a long gentle grade. v1 picks
// the first and last eligible segment of a direction rather than searching
// all pairs for the best match -- the simplest thing that satisfies "a
// session with no two comparable segments has no durability metric," not
// an exhaustive optimal-pairing search.

const val DURABILITY_MODEL_VERSION = "1"
const val COMPARABLE_GRADE_TOLERANCE_PP = 3.0
const val COMPARABLE_LENGTH_RATIO_MAX = 2.0

fun findComparableSegmentPair(segments: List<TerrainSegment>, direction: SegmentDirection): Pair<TerrainSegment, TerrainSegment>? {
    val eligible = segments.filter { it.direction == direction && it.isPerformanceEligible() }
    if (eligible.size < 2) return null
    val early = eligible.first()
    val late = eligible.last()
    if (early === late) return null

    val earlyLength = early.endDistanceM - early.startDistanceM
    val lateLength = late.endDistanceM - late.startDistanceM
    val gradeDiff = kotlin.math.abs(early.averageGradePercent - late.averageGradePercent)
    val lengthRatio = maxOf(earlyLength, lateLength) / minOf(earlyLength, lateLength)

    return if (gradeDiff <= COMPARABLE_GRADE_TOLERANCE_PP && lengthRatio <= COMPARABLE_LENGTH_RATIO_MAX) early to late else null
}

// Positive = faster/stronger late climb; negative = degradation. Null when
// no comparable pair of climbs exists, never a forced comparison.
fun computeVamDegradationPercent(segments: List<TerrainSegment>): Double? {
    val (early, late) = findComparableSegmentPair(segments, SegmentDirection.UPHILL) ?: return null
    val earlyVam = early.elevationChangeM / (early.movingDurationSeconds / 3600.0)
    val lateVam = late.elevationChangeM / (late.movingDurationSeconds / 3600.0)
    if (earlyVam == 0.0) return null
    return (lateVam - earlyVam) / earlyVam * 100.0
}

// Positive = slower (degraded) grade-adjusted pace late in the session.
fun computeUphillPaceDegradationPercent(
    segments: List<TerrainSegment>,
    heartRate: List<TimePoint>,
    powerW: List<TimePoint>,
    speedKmh: List<TimePoint>,
): Double? {
    val (early, late) = findComparableSegmentPair(segments, SegmentDirection.UPHILL) ?: return null
    val climbs = computeUphillPerformance(listOf(early, late), heartRate, powerW, speedKmh)
    val earlyGap = climbs.find { it.segment === early }?.averageGradeAdjustedPaceMinPerKm ?: return null
    val lateGap = climbs.find { it.segment === late }?.averageGradeAdjustedPaceMinPerKm ?: return null
    if (earlyGap == 0.0) return null
    return (lateGap - earlyGap) / earlyGap * 100.0
}

// Classic aerobic-decoupling shape (speed:HR ratio, first half of the
// session's own recorded duration vs second half) -- positive = efficiency
// dropped (more HR for the same speed, or less speed for the same HR) in
// the second half, the same direction convention as pace degradation above.
fun computeHrDecouplingPercent(heartRate: List<TimePoint>, speedKmh: List<TimePoint>): Double? {
    if (heartRate.isEmpty() || speedKmh.isEmpty()) return null
    val maxOffset = maxOf(heartRate.maxOf { it.offsetSeconds }, speedKmh.maxOf { it.offsetSeconds })
    val midpoint = maxOffset / 2

    fun averageIn(series: List<TimePoint>, range: LongRange) =
        series.filter { it.offsetSeconds in range }.map { it.value }.takeIf { it.isNotEmpty() }?.average()

    val hr1 = averageIn(heartRate, 0..midpoint) ?: return null
    val hr2 = averageIn(heartRate, midpoint..maxOffset) ?: return null
    val speed1 = averageIn(speedKmh, 0..midpoint) ?: return null
    val speed2 = averageIn(speedKmh, midpoint..maxOffset) ?: return null
    if (hr1 <= 0 || hr2 <= 0) return null

    val ratio1 = speed1 / hr1
    if (ratio1 == 0.0) return null
    val ratio2 = speed2 / hr2
    return (ratio1 - ratio2) / ratio1 * 100.0
}
