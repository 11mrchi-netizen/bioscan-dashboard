package com.bioscan.fieldterminal.domain.form

import com.bioscan.fieldterminal.domain.median
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sign

// When each foot lands and leaves the ground, from a side view. A foot is on the ground while its lowest point (heel or
// toe, whichever is lower in the picture) stays at the floor line. Landing and toe-off are found where the foot crosses two
// levels just above the floor measured under that step (interpolated between frames), extrapolated to the floor itself:
// a single level above the floor would put every landing early and every toe-off late, and lengthen contact time. Each contact is checked against the
// coordinate method of Zeni et al. (2008): the foot is ahead of the pelvis at landing and behind it at toe-off.

/** Coarse on-the-ground band and the fine level for landing and toe-off, as shares of leg length. */
const val CONTACT_COARSE_TOLERANCE = 0.06
const val CONTACT_FINE_TOLERANCE = 0.015
/** Window of the moving floor line (long enough for a stride), ms. */
const val FLOOR_WINDOW_MS = 1000.0
/** Plausible contact lengths, ms. */
const val MIN_CONTACT_MS = 60.0
const val MAX_CONTACT_MS = 700.0
/** Two runs of "on the ground" closer than this are one contact (a flicker, not a step), ms. */
const val MERGE_GAP_MS = 30.0
/** How far beyond the coarse run landing and toe-off are looked for, ms. */
const val EDGE_SEARCH_MS = 60.0

data class FootContact(
    val side: BodySide,
    /** Landing and toe-off, ms from the clip's start (interpolated between frames). */
    val landingMs: Double,
    val toeOffMs: Double,
    /** The first frame on the ground and the last one. */
    val landingIndex: Int,
    val toeOffIndex: Int,
) {
    val contactMs: Double get() = toeOffMs - landingMs
}

/** The leg nearer the camera: the one MediaPipe sees better (knee, ankle, heel and toe). */
fun nearSide(tracks: PoseTracks): BodySide {
    fun seen(side: BodySide): Double {
        val leg = legOf(side)
        return listOf(leg.knee, leg.ankle, leg.heel, leg.toe).sumOf { j -> tracks.visibility[j].filterIndexed { i, _ -> tracks.detected[i] }.average().takeIf { !it.isNaN() } ?: 0.0 }
    }
    return if (seen(BodySide.LEFT) >= seen(BodySide.RIGHT)) BodySide.LEFT else BodySide.RIGHT
}

/** +1 when the runner faces right in the picture, -1 when left: the nose is ahead of the ears. */
fun travelDirection(tracks: PoseTracks): Int {
    val ahead = (0 until tracks.size).mapNotNull { i ->
        val nose = tracks.x[Landmark.NOSE][i]
        val ears = listOf(tracks.x[Landmark.LEFT_EAR][i], tracks.x[Landmark.RIGHT_EAR][i]).filter { !it.isNaN() }
        if (nose.isNaN() || ears.isEmpty()) null else nose - ears.average()
    }
    if (ahead.isNotEmpty() && median(ahead) != 0.0) return median(ahead).sign.toInt()
    // No face: the foot swings forward relative to the hips while it is off the ground.
    val leg = legOf(nearSide(tracks))
    var sum = 0.0
    for (i in 1 until tracks.size) {
        val rel0 = tracks.x[leg.ankle][i - 1] - hipMidX(tracks, i - 1)
        val rel1 = tracks.x[leg.ankle][i] - hipMidX(tracks, i)
        val lift = (tracks.y[leg.hip][i] - tracks.y[leg.ankle][i])
        if (!rel0.isNaN() && !rel1.isNaN() && !lift.isNaN()) sum += (rel1 - rel0) * lift
    }
    return if (sum >= 0) 1 else -1
}

internal fun hipMidX(t: PoseTracks, i: Int) = (t.x[Landmark.LEFT_HIP][i] + t.x[Landmark.RIGHT_HIP][i]) / 2
internal fun hipMidY(t: PoseTracks, i: Int) = (t.y[Landmark.LEFT_HIP][i] + t.y[Landmark.RIGHT_HIP][i]) / 2

/** The lowest point of a foot in the picture (largest y) per frame: heel or toe. */
fun footLowY(tracks: PoseTracks, side: BodySide): DoubleArray {
    val leg = legOf(side)
    return DoubleArray(tracks.size) { i ->
        val h = tracks.y[leg.heel][i]; val t = tracks.y[leg.toe][i]
        when {
            h.isNaN() -> t
            t.isNaN() -> h
            else -> max(h, t)
        }
    }
}

/** The floor line under a foot: the 90th percentile of its low point over a window around each frame. */
fun floorLine(low: DoubleArray, timesMs: DoubleArray, windowMs: Double = FLOOR_WINDOW_MS): DoubleArray {
    val out = DoubleArray(low.size) { Double.NaN }
    var lo = 0
    var hi = 0
    for (i in low.indices) {
        while (timesMs[lo] < timesMs[i] - windowMs / 2) lo++
        while (hi + 1 < low.size && timesMs[hi + 1] <= timesMs[i] + windowMs / 2) hi++
        val window = (lo..hi).map { low[it] }.filter { !it.isNaN() }.sorted()
        if (window.size >= 3) out[i] = window[((window.size - 1) * 0.9).toInt()]
    }
    return out
}

/** The contacts of one foot, in time order. */
fun detectContacts(tracks: PoseTracks, side: BodySide, direction: Int): List<FootContact> {
    val leg = tracks.legLengthPx
    if (leg <= 0 || tracks.size < 3) return emptyList()
    val t = tracks.timesMs
    val low = footLowY(tracks, side)
    val floor = floorLine(low, t)
    val on = BooleanArray(tracks.size) { i -> !low[i].isNaN() && !floor[i].isNaN() && low[i] >= floor[i] - CONTACT_COARSE_TOLERANCE * leg }

    // Runs of frames on the ground, flickers merged.
    val runs = mutableListOf<IntRange>()
    var i = 0
    while (i < on.size) {
        if (!on[i]) { i++; continue }
        var j = i
        while (j + 1 < on.size && on[j + 1]) j++
        val last = runs.lastOrNull()
        if (last != null && t[i] - t[last.last] <= MERGE_GAP_MS) runs[runs.lastIndex] = last.first..j else runs += i..j
        i = j + 1
    }

    val ankle = legOf(side).ankle
    fun aheadOfHips(k: Int) = (tracks.x[ankle][k] - hipMidX(tracks, k)) * direction
    val contacts = mutableListOf<FootContact>()
    for (run in runs) {
        // The floor under this step: the low point while the foot is flat, the middle half of the run.
        val mid = run.first + run.count() / 4..run.last - run.count() / 4
        val flat = mid.map { low[it] }.filter { !it.isNaN() }
        if (flat.isEmpty()) continue
        val floorHere = median(flat)
        val near = floorHere - CONTACT_FINE_TOLERANCE * leg
        val farther = floorHere - 3 * CONTACT_FINE_TOLERANCE * leg
        val searchFrom = indexAtOrAfter(t, t[run.first] - EDGE_SEARCH_MS)
        val searchTo = indexAtOrBefore(t, t[run.last] + EDGE_SEARCH_MS)
        val landing = (searchFrom..run.last).firstOrNull { !low[it].isNaN() && low[it] >= near } ?: continue
        val toeOff = (searchTo downTo run.first).firstOrNull { !low[it].isNaN() && low[it] >= near } ?: continue
        if (toeOff <= landing) continue
        val frameMs = 1000.0 / tracks.fps
        val landingMs = extrapolatedToFloor(
            crossingDown(t, low, searchFrom, landing, farther), crossingTime(t, low, landing - 1, landing, near), frameMs, later = true,
        )
        val toeOffMs = extrapolatedToFloor(
            crossingUp(t, low, toeOff, searchTo, farther), crossingTime(t, low, toeOff, toeOff + 1, near), frameMs, later = false,
        )
        val length = toeOffMs - landingMs
        if (length < MIN_CONTACT_MS || length > MAX_CONTACT_MS) continue
        // Zeni et al. (2008): the foot moves from ahead of the pelvis to behind it while on the ground.
        val a = aheadOfHips(landing); val b = aheadOfHips(toeOff)
        if (!a.isNaN() && !b.isNaN() && a <= b) continue
        contacts += FootContact(side, landingMs, toeOffMs, landing, toeOff)
    }
    return contacts
}

/**
 * The time the foot meets the floor, from where it crossed the farther level (3 tolerances up) and the nearer one (1 up):
 * straight on by half that interval, capped at a frame. `later` is true for a landing (the floor comes after the nearer
 * level), false for a toe-off.
 */
private fun extrapolatedToFloor(fartherMs: Double?, nearMs: Double, frameMs: Double, later: Boolean): Double {
    if (fartherMs == null) return nearMs
    val step = ((nearMs - fartherMs) / 2).let { if (later) it else -it }
    if (step < 0) return nearMs
    return if (later) nearMs + minOf(step, frameMs) else nearMs - minOf(step, frameMs)
}

/** The last time before frame `upTo` that the descending foot crossed `level`. */
private fun crossingDown(t: DoubleArray, low: DoubleArray, from: Int, upTo: Int, level: Double): Double? {
    val k = (upTo downTo maxOf(from, 1)).firstOrNull { !low[it].isNaN() && !low[it - 1].isNaN() && low[it - 1] < level && low[it] >= level } ?: return null
    return crossingTime(t, low, k - 1, k, level)
}

/** The first time after frame `from` that the lifting foot crossed `level`. */
private fun crossingUp(t: DoubleArray, low: DoubleArray, from: Int, upTo: Int, level: Double): Double? {
    val k = (from until minOf(upTo, low.size - 1)).firstOrNull { !low[it].isNaN() && !low[it + 1].isNaN() && low[it] >= level && low[it + 1] < level } ?: return null
    return crossingTime(t, low, k, k + 1, level)
}

/** Where the low point crosses `level` between frames a and b, by linear interpolation; a frame's own time at the clip's ends. */
private fun crossingTime(t: DoubleArray, low: DoubleArray, a: Int, b: Int, level: Double): Double {
    if (a < 0) return t[b]
    if (b >= t.size) return t[a]
    val ya = low[a]; val yb = low[b]
    if (ya.isNaN() || yb.isNaN() || ya == yb) return if (a < 0) t[b] else (t[a] + t[b]) / 2
    val f = ((level - ya) / (yb - ya)).coerceIn(0.0, 1.0)
    return t[a] + (t[b] - t[a]) * f
}

private fun indexAtOrAfter(t: DoubleArray, ms: Double): Int = t.indexOfFirst { it >= ms }.let { if (it < 0) t.lastIndex else it }
private fun indexAtOrBefore(t: DoubleArray, ms: Double): Int = t.indexOfLast { it <= ms }.let { if (it < 0) 0 else min(it, t.lastIndex) }
