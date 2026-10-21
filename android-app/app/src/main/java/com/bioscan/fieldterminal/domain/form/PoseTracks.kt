package com.bioscan.fieldterminal.domain.form

import com.bioscan.fieldterminal.domain.median
import kotlin.math.PI
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.math.tan

// From MediaPipe's frames to clean per-point tracks: left and right put back where MediaPipe swapped them (it does, in a
// pure side view, when the legs cross), hidden points dropped, short gaps filled, and every track low-pass filtered
// forward and backward so filtering does not shift events in time.

/** A point below this visibility is treated as not seen. */
const val MISSING_VISIBILITY = 0.2
/** Gaps up to this long are filled by a straight line; longer ones stay empty. */
const val MAX_GAP_MS = 100.0
/** Low-pass cut-off: 12 Hz, or a fifth of the frame rate when that is lower (Winter 2009 for running kinematics). */
fun formCutoffHz(fps: Double): Double = min(12.0, fps / 5.0)

class PoseTracks(
    val timesMs: DoubleArray,
    /** x[landmark][frame], NaN where the point was not seen. */
    val x: Array<DoubleArray>,
    val y: Array<DoubleArray>,
    val visibility: Array<DoubleArray>,
    val detected: BooleanArray,
    val fps: Double,
    /** Frames whose left and right were swapped back. */
    val swapsFixed: Int,
    /** The runner's leg length in pixels (hip to knee plus knee to ankle, median over the clip). */
    val legLengthPx: Double,
    val widthPx: Int,
    val heightPx: Int,
) {
    val size: Int get() = timesMs.size
}

/** The clip's frame rate from the median gap between frames. */
fun clipFps(frames: List<PoseFrame>): Double {
    val gaps = frames.zipWithNext { a, b -> b.timeMs - a.timeMs }.filter { it > 0 }
    return if (gaps.isEmpty()) 0.0 else 1000.0 / median(gaps)
}

/** Median hip-to-knee plus knee-to-ankle length over both legs and every frame where all three points are seen. */
fun legLengthPx(frames: List<PoseFrame>): Double {
    val lengths = frames.flatMap { f ->
        val p = f.points ?: return@flatMap emptyList()
        BodySide.entries.mapNotNull { side ->
            val leg = legOf(side)
            val (h, k, a) = Triple(p[leg.hip], p[leg.knee], p[leg.ankle])
            if (minOf(h.visibility, k.visibility, a.visibility) < MISSING_VISIBILITY) null
            else hypot(h.x - k.x, h.y - k.y) + hypot(k.x - a.x, k.y - a.y)
        }
    }
    return if (lengths.isEmpty()) 0.0 else median(lengths)
}

// Arms and legs are judged apart: MediaPipe swaps the legs alone when they cross, or the whole body when it takes the
// runner to face the other way.
private val LEG_PAIRS = Landmark.PAIRS.filter { it.first >= Landmark.LEFT_HIP }
private val ARM_PAIRS = Landmark.PAIRS.filter { it.first < Landmark.LEFT_HIP }

/**
 * The frames with left and right put back where MediaPipe swapped them, and how many frames changed. Each frame is compared
 * with where the last two frames say each point should be now; a group (legs, arms) is swapped back when the swapped
 * reading is much closer to that prediction. The first frame could itself be the swapped one, so the clip is tracked from
 * it both ways and the reading that needs fewer corrections is kept.
 */
fun unswapLeftRight(frames: List<PoseFrame>, legLengthPx: Double): Pair<List<PoseFrame>, Int> {
    val first = frames.indexOfFirst { it.points != null }
    if (first < 0) return frames to 0
    val asIs = trackLabels(frames, legLengthPx, flipFirst = false)
    val flipped = trackLabels(frames, legLengthPx, flipFirst = true)
    return if (flipped.second < asIs.second) flipped else asIs
}

private fun trackLabels(frames: List<PoseFrame>, legLengthPx: Double, flipFirst: Boolean): Pair<List<PoseFrame>, Int> {
    val minGain = 0.15 * legLengthPx
    val out = ArrayList<PoseFrame>(frames.size)
    var prev: PoseFrame? = null
    var prev2: PoseFrame? = null
    var changed = 0
    for (frame in frames) {
        val pts = frame.points
        if (pts == null) { out += frame; continue }
        val p1 = prev
        if (p1 == null) {
            val start = if (flipFirst) PoseFrame(frame.timeMs, swapPairs(swapPairs(pts, LEG_PAIRS), ARM_PAIRS)) else frame
            if (flipFirst) changed++
            out += start; prev = start
            continue
        }
        val p0 = prev2
        fun predicted(j: Int): Pair<Double, Double> {
            val a = p1.points!![j]
            if (p0 == null) return a.x to a.y
            val b = p0.points!![j]
            val dtPrev = p1.timeMs - p0.timeMs
            val f = if (dtPrev > 0) (frame.timeMs - p1.timeMs) / dtPrev else 0.0
            return (a.x + (a.x - b.x) * f) to (a.y + (a.y - b.y) * f)
        }
        var fixed: List<PosePoint> = pts
        var any = false
        for (group in listOf(LEG_PAIRS, ARM_PAIRS)) {
            var keep = 0.0
            var swap = 0.0
            for ((l, r) in group) {
                val (plx, ply) = predicted(l)
                val (prx, pry) = predicted(r)
                val cl = fixed[l]; val cr = fixed[r]
                keep += hypot(cl.x - plx, cl.y - ply) + hypot(cr.x - prx, cr.y - pry)
                swap += hypot(cr.x - plx, cr.y - ply) + hypot(cl.x - prx, cl.y - pry)
            }
            if (swap < 0.5 * keep && keep - swap > minGain * group.size / 2) {
                fixed = swapPairs(fixed, group)
                any = true
            }
        }
        if (any) changed++
        val next = PoseFrame(frame.timeMs, fixed)
        out += next
        prev2 = p1
        prev = next
    }
    return out to changed
}

private fun swapPairs(points: List<PosePoint>, pairs: List<Pair<Int, Int>>): List<PosePoint> {
    val m = points.toMutableList()
    for ((l, r) in pairs) { val t = m[l]; m[l] = m[r]; m[r] = t }
    return m
}

/** Clean tracks from a clip: un-swapped, hidden points dropped, gaps up to MAX_GAP_MS filled, then low-pass filtered. */
fun buildTracks(clip: PoseClip): PoseTracks {
    val fps = clipFps(clip.frames)
    val leg = legLengthPx(clip.frames)
    val (frames, swaps) = unswapLeftRight(clip.frames, leg)
    val n = frames.size
    val times = DoubleArray(n) { frames[it].timeMs }
    val detected = BooleanArray(n) { frames[it].points != null }
    val xs = Array(Landmark.COUNT) { DoubleArray(n) { Double.NaN } }
    val ys = Array(Landmark.COUNT) { DoubleArray(n) { Double.NaN } }
    val vis = Array(Landmark.COUNT) { DoubleArray(n) }
    for (i in 0 until n) {
        val p = frames[i].points ?: continue
        for (j in 0 until Landmark.COUNT) {
            vis[j][i] = p[j].visibility
            if (p[j].visibility >= MISSING_VISIBILITY) { xs[j][i] = p[j].x; ys[j][i] = p[j].y }
        }
    }
    val fc = formCutoffHz(fps)
    for (j in 0 until Landmark.COUNT) {
        xs[j] = lowPassZeroPhase(fillGaps(xs[j], times, MAX_GAP_MS), fps, fc)
        ys[j] = lowPassZeroPhase(fillGaps(ys[j], times, MAX_GAP_MS), fps, fc)
    }
    return PoseTracks(times, xs, ys, vis, detected, fps, swaps, leg, clip.widthPx, clip.heightPx)
}

/** Gaps (NaN) of at most maxGapMs between two seen values filled by a straight line in time. */
fun fillGaps(values: DoubleArray, timesMs: DoubleArray, maxGapMs: Double): DoubleArray {
    val out = values.copyOf()
    var last = -1
    for (i in out.indices) {
        if (out[i].isNaN()) continue
        if (last >= 0 && i - last > 1 && timesMs[i] - timesMs[last] <= maxGapMs) {
            for (k in last + 1 until i) {
                val f = (timesMs[k] - timesMs[last]) / (timesMs[i] - timesMs[last])
                out[k] = out[last] + (out[i] - out[last]) * f
            }
        }
        last = i
    }
    return out
}

/**
 * A second-order Butterworth low-pass run forward and backward (zero phase, fourth order overall), on each unbroken run
 * of values separately. The cut-off is raised by Winter's factor (0.802) so the two passes together still sit at fcHz.
 * Runs too short to filter are left as they are.
 */
fun lowPassZeroPhase(values: DoubleArray, fs: Double, fcHz: Double): DoubleArray {
    if (fs <= 0 || fcHz <= 0) return values.copyOf()
    val fc = min(fcHz / 0.802, 0.45 * fs)
    val k = tan(PI * fc / fs)
    val norm = 1.0 / (1.0 + sqrt(2.0) * k + k * k)
    val b0 = k * k * norm
    val b1 = 2 * b0
    val a1 = 2 * (k * k - 1) * norm
    val a2 = (1 - sqrt(2.0) * k + k * k) * norm
    fun pass(x: DoubleArray): DoubleArray {
        val y = DoubleArray(x.size)
        var x1 = x[0]; var x2 = x[0]; var y1 = x[0]; var y2 = x[0]
        for (i in x.indices) {
            val v = b0 * x[i] + b1 * x1 + b0 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x[i]; y2 = y1; y1 = v
            y[i] = v
        }
        return y
    }
    val out = values.copyOf()
    var i = 0
    while (i < values.size) {
        if (values[i].isNaN()) { i++; continue }
        var j = i
        while (j < values.size && !values[j].isNaN()) j++
        val run = values.copyOfRange(i, j)
        if (run.size >= 7) {
            // Odd reflection at both ends keeps the ends from being pulled toward zero.
            val pad = min(run.size - 1, max(3, (1.5 * fs / fcHz).roundToInt()))
            val ext = DoubleArray(run.size + 2 * pad)
            for (p in 0 until pad) {
                ext[p] = 2 * run[0] - run[pad - p]
                ext[pad + run.size + p] = 2 * run.last() - run[run.size - 2 - p]
            }
            run.copyInto(ext, pad)
            val filtered = pass(pass(ext).reversedArray()).reversedArray()
            filtered.copyInto(out, i, pad, pad + run.size)
        }
        i = j
    }
    return out
}

internal fun DoubleArray.finite(): List<Double> = filter { !it.isNaN() }
