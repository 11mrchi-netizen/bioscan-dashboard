package com.bioscan.fieldterminal.domain.trail

// DAV-134, per docs/trail-intelligence/04-terrain-segmentation-rules.md.
// smoothElevation() -> segmentClimbsAndDescents() is the whole pipeline:
// noise-reduce the raw profile, then walk it once with a hysteresis rule so
// GPS jitter can't fabricate extra climbs. Only UPHILL/DOWNHILL segments are
// emitted here -- there is no separate FLAT segment type, since every real
// consumer of this output (DAV-136's climb/descent structure, DAV-138/139's
// per-climb performance) only ever asks for actual climbs and descents;
// "flat" is a grade-band classification a caller applies per interval, not a
// segment this hysteresis walk needs to produce.

data class SmoothedPoint(val trackpoint: Trackpoint, val smoothedElevationM: Double?)

const val ELEVATION_SMOOTHING_WINDOW = 5
const val NOISE_THRESHOLD_M = 10.0
const val STOPPED_SPEED_MPS = 0.3
const val SEGMENTATION_MODEL_VERSION = "1"

// doc 04's two-tier bar: every hysteresis-closed segment counts structurally
// (DAV-136), but only a segment clearing both of these is stable enough for
// a per-segment performance number (VAM, GAP, efficiency -- DAV-138/139/141)
// or a durability comparison (DAV-142).
const val MIN_PERFORMANCE_SEGMENT_DISTANCE_M = 100.0
const val MIN_PERFORMANCE_SEGMENT_DURATION_S = 60L

fun TerrainSegment.isPerformanceEligible(): Boolean =
    (endDistanceM - startDistanceM) >= MIN_PERFORMANCE_SEGMENT_DISTANCE_M &&
        movingDurationSeconds >= MIN_PERFORMANCE_SEGMENT_DURATION_S

// 5-point moving average (doc 04). A point with no raw elevation gets no
// smoothed value either -- never filled in from a neighbor.
fun smoothElevation(points: List<Trackpoint>, windowSize: Int = ELEVATION_SMOOTHING_WINDOW): List<SmoothedPoint> {
    val half = windowSize / 2
    return points.mapIndexed { index, point ->
        val smoothed = if (point.elevationM == null) {
            null
        } else {
            points.subList(maxOf(0, index - half), minOf(points.size, index + half + 1))
                .mapNotNull { it.elevationM }
                .average()
        }
        SmoothedPoint(point, smoothed)
    }
}

enum class SegmentDirection { UPHILL, DOWNHILL }

enum class GradeBand { FLAT, GENTLE_UP, MODERATE_UP, STEEP_UP, GENTLE_DOWN, MODERATE_DOWN, STEEP_DOWN }

fun gradeBandFor(gradePercent: Double): GradeBand = when {
    gradePercent > 15.0 -> GradeBand.STEEP_UP
    gradePercent > 8.0 -> GradeBand.MODERATE_UP
    gradePercent > 3.0 -> GradeBand.GENTLE_UP
    gradePercent >= -3.0 -> GradeBand.FLAT
    gradePercent >= -8.0 -> GradeBand.GENTLE_DOWN
    gradePercent >= -15.0 -> GradeBand.MODERATE_DOWN
    else -> GradeBand.STEEP_DOWN
}

data class TerrainSegment(
    val direction: SegmentDirection,
    val startOffsetSeconds: Long,
    val endOffsetSeconds: Long,
    val startDistanceM: Double,
    val endDistanceM: Double,
    val elevationChangeM: Double,
    val movingDurationSeconds: Long,
    val averageGradePercent: Double,
    val gradeBand: GradeBand,
)

// Hysteresis walk (doc 04): extends the current segment while its extremum
// keeps moving further in the same direction; only closes and reverses once
// the pullback from that extremum clears NOISE_THRESHOLD_M. A point with no
// smoothed elevation can't anchor a pivot/extremum, so only usable points
// drive direction -- but moving-time exclusion (STOPPED_SPEED_MPS) still
// looks at every point in the closed range, elevation-null or not.
fun segmentClimbsAndDescents(smoothed: List<SmoothedPoint>): List<TerrainSegment> {
    val usable = smoothed.filter { it.smoothedElevationM != null }
    if (usable.size < 2) return emptyList()

    val segments = mutableListOf<TerrainSegment>()
    var segmentStart = usable[0]
    var extremum = usable[0]
    var direction: SegmentDirection? = null

    fun movingDurationSeconds(start: SmoothedPoint, end: SmoothedPoint): Long {
        val span = smoothed.filter { it.trackpoint.offsetSeconds in start.trackpoint.offsetSeconds..end.trackpoint.offsetSeconds }
        var moving = 0L
        for (i in 1 until span.size) {
            val a = span[i - 1].trackpoint
            val b = span[i].trackpoint
            val dt = b.offsetSeconds - a.offsetSeconds
            val dd = b.cumulativeDistanceM - a.cumulativeDistanceM
            if (dt > 0 && dd / dt >= STOPPED_SPEED_MPS) moving += dt
        }
        return moving
    }

    fun closeSegment(start: SmoothedPoint, end: SmoothedPoint, dir: SegmentDirection) {
        val elevationChange = end.smoothedElevationM!! - start.smoothedElevationM!!
        val distanceChange = end.trackpoint.cumulativeDistanceM - start.trackpoint.cumulativeDistanceM
        val grade = if (distanceChange > 0) elevationChange / distanceChange * 100.0 else 0.0
        segments += TerrainSegment(
            direction = dir,
            startOffsetSeconds = start.trackpoint.offsetSeconds,
            endOffsetSeconds = end.trackpoint.offsetSeconds,
            startDistanceM = start.trackpoint.cumulativeDistanceM,
            endDistanceM = end.trackpoint.cumulativeDistanceM,
            elevationChangeM = elevationChange,
            movingDurationSeconds = movingDurationSeconds(start, end),
            averageGradePercent = grade,
            gradeBand = gradeBandFor(grade),
        )
    }

    for (i in 1 until usable.size) {
        val point = usable[i]
        when (direction) {
            null -> {
                val delta = point.smoothedElevationM!! - segmentStart.smoothedElevationM!!
                when {
                    delta >= NOISE_THRESHOLD_M -> { direction = SegmentDirection.UPHILL; extremum = point }
                    delta <= -NOISE_THRESHOLD_M -> { direction = SegmentDirection.DOWNHILL; extremum = point }
                }
            }
            SegmentDirection.UPHILL -> {
                val pointElevation = point.smoothedElevationM!!
                val extremumElevation = extremum.smoothedElevationM!!
                when {
                    pointElevation >= extremumElevation -> extremum = point
                    extremumElevation - pointElevation >= NOISE_THRESHOLD_M -> {
                        closeSegment(segmentStart, extremum, SegmentDirection.UPHILL)
                        segmentStart = extremum
                        direction = SegmentDirection.DOWNHILL
                        extremum = point
                    }
                }
            }
            SegmentDirection.DOWNHILL -> {
                val pointElevation = point.smoothedElevationM!!
                val extremumElevation = extremum.smoothedElevationM!!
                when {
                    pointElevation <= extremumElevation -> extremum = point
                    pointElevation - extremumElevation >= NOISE_THRESHOLD_M -> {
                        closeSegment(segmentStart, extremum, SegmentDirection.DOWNHILL)
                        segmentStart = extremum
                        direction = SegmentDirection.UPHILL
                        extremum = point
                    }
                }
            }
        }
    }

    direction?.let { closeSegment(segmentStart, usable.last(), it) }
    return segments
}

fun totalElevationGainM(segments: List<TerrainSegment>): Double =
    segments.filter { it.direction == SegmentDirection.UPHILL }.sumOf { it.elevationChangeM }

fun totalElevationLossM(segments: List<TerrainSegment>): Double =
    -segments.filter { it.direction == SegmentDirection.DOWNHILL }.sumOf { it.elevationChangeM }
