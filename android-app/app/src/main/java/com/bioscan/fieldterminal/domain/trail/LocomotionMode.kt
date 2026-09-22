package com.bioscan.fieldterminal.domain.trail

// DAV-140. Run/hike classification and transitions -- the actual uphill
// hiking *efficiency ratio* (vertical speed / HR) is DAV-141's job per doc
// 03's registry, same non-duplication split as DAV-139/downhill; this file
// only produces the run-vs-hike split and percentages.

const val RUN_HIKE_MODEL_VERSION = "1"

// ponytail: a single flat-ground threshold, not grade-adjusted. 2.1 m/s is
// the real preferred walk-run transition speed on level ground (Diedrich &
// Warren 1995). Real research (Jeffers et al. 2021, J Exp Biol) shows this
// transition speed drops on steep inclines -- a "power hike" up a steep
// climb can sit well below 2.1 m/s while still being the athlete's own
// natural gait choice, not a sign of struggling. Upgrade path: a
// grade-adjusted threshold once that relationship is worth the added
// complexity; until then this reads correctly on typical rolling terrain
// but may over-count "hiking" on runnable-but-slow steep climbs.
const val WALK_RUN_TRANSITION_SPEED_MPS = 2.1

enum class LocomotionMode { RUN, HIKE }

data class LocomotionSplit(
    val runDistanceM: Double,
    val runDurationSeconds: Long,
    val hikeDistanceM: Double,
    val hikeDurationSeconds: Long,
    val uphillRunDistanceM: Double,
    val uphillHikeDistanceM: Double,
    val transitionCount: Int,
) {
    val uphillRunPercent: Double? get() {
        val total = uphillRunDistanceM + uphillHikeDistanceM
        return if (total > 0) uphillRunDistanceM / total * 100.0 else null
    }
    val uphillHikePercent: Double? get() = uphillRunPercent?.let { 100.0 - it }
}

fun classifyLocomotion(smoothed: List<SmoothedPoint>): LocomotionSplit {
    var runDistance = 0.0
    var runDuration = 0L
    var hikeDistance = 0.0
    var hikeDuration = 0L
    var uphillRunDistance = 0.0
    var uphillHikeDistance = 0.0
    var transitions = 0
    var previousMode: LocomotionMode? = null

    for (i in 1 until smoothed.size) {
        val a = smoothed[i - 1]
        val b = smoothed[i]
        val distanceDelta = b.trackpoint.cumulativeDistanceM - a.trackpoint.cumulativeDistanceM
        val timeDelta = b.trackpoint.offsetSeconds - a.trackpoint.offsetSeconds
        if (distanceDelta <= 0 || timeDelta <= 0) continue

        val speed = distanceDelta / timeDelta
        val mode = if (speed >= WALK_RUN_TRANSITION_SPEED_MPS) LocomotionMode.RUN else LocomotionMode.HIKE

        if (mode == LocomotionMode.RUN) {
            runDistance += distanceDelta
            runDuration += timeDelta
        } else {
            hikeDistance += distanceDelta
            hikeDuration += timeDelta
        }

        val elevationA = a.smoothedElevationM
        val elevationB = b.smoothedElevationM
        val isUphill = elevationA != null && elevationB != null && elevationB > elevationA
        if (isUphill) {
            if (mode == LocomotionMode.RUN) uphillRunDistance += distanceDelta else uphillHikeDistance += distanceDelta
        }

        if (previousMode != null && previousMode != mode) transitions++
        previousMode = mode
    }

    return LocomotionSplit(runDistance, runDuration, hikeDistance, hikeDuration, uphillRunDistance, uphillHikeDistance, transitions)
}
