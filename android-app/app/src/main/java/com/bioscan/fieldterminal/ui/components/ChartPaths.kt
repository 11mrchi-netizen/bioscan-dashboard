package com.bioscan.fieldterminal.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import kotlin.math.abs
import kotlin.math.exp

// Line charts read as trends, not survey plots, so the line is drawn as a soft curve instead of straight segments
// between the points -- "more curves, fewer angles". The curve still passes through every point; it only rounds the
// corners. A low tension keeps it from overshooting the data enough to mislead or to bulge past the chart's edge.

/**
 * A path through [pts] in order, with the corners rounded into a gentle curve (Catmull-Rom tangents at [smoothing]
 * tension). Two points draw a straight line; one point or none draws nothing. The line passes through every point.
 */
fun smoothedPath(pts: List<Offset>, smoothing: Float = 0.18f): Path = Path().apply {
    if (pts.isEmpty()) return@apply
    moveTo(pts[0].x, pts[0].y)
    if (pts.size < 3) {
        for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
        return@apply
    }
    for (i in 0 until pts.size - 1) {
        val p0 = pts[if (i == 0) 0 else i - 1]
        val p1 = pts[i]
        val p2 = pts[i + 1]
        val p3 = pts[if (i + 2 > pts.lastIndex) pts.lastIndex else i + 2]
        // Control points pull toward the neighbours' direction, scaled down by `smoothing` so the curve stays close to
        // the straight line through the points.
        val c1x = p1.x + (p2.x - p0.x) * smoothing
        val c1y = p1.y + (p2.y - p0.y) * smoothing
        val c2x = p2.x - (p3.x - p1.x) * smoothing
        val c2y = p2.y - (p3.y - p1.y) * smoothing
        cubicTo(c1x, c1y, c2x, c2y, p2.x, p2.y)
    }
}

// ---- Smoothing ------------------------------------------------------------------------------------------------------
// A noisy daily series (weight, body fat from a scale, resting HR) curved through every reading still zigzags. The trend
// line is instead a local linear fit (Gaussian weights over time, [bandwidth] days wide): it follows the readings without
// lagging the way a trailing average does, and keeps its slope at the ends. Readings stay on the chart as faint dots.

/**
 * The trend of ([xs], [ys]) at [at], each x in days: a weighted straight-line fit around [at] with Gaussian weights of
 * standard deviation [bandwidth] days. Falls back to the weighted mean when the weights sit on one day, and to the
 * nearest reading when none carry any weight.
 */
fun localTrendAt(xs: List<Double>, ys: List<Double>, at: Double, bandwidth: Double): Double {
    var sw = 0.0; var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0
    for (k in xs.indices) {
        val d = (xs[k] - at) / bandwidth
        val w = exp(-0.5 * d * d)
        if (w < 1e-12) continue
        val dx = xs[k] - at
        sw += w; sx += w * dx; sy += w * ys[k]; sxx += w * dx * dx; sxy += w * dx * ys[k]
    }
    if (sw < 1e-9) return ys[xs.indices.minBy { abs(xs[it] - at) }]
    val mx = sx / sw
    val my = sy / sw
    val varX = sxx / sw - mx * mx
    if (varX < 1e-9) return my
    val slope = (sxy / sw - mx * my) / varX
    return my - slope * mx // the fitted line at dx = 0
}

/**
 * The trend through one run of readings ([xs] in days, ascending), sampled every [step] days from the first reading to
 * the last, as (x, value) pairs ready for [smoothedPath]. One reading gives itself.
 */
fun trendCurve(xs: List<Double>, ys: List<Double>, bandwidth: Double, step: Double = (bandwidth / 3).coerceAtLeast(0.5)): List<Pair<Double, Double>> {
    if (xs.isEmpty()) return emptyList()
    if (xs.size == 1) return listOf(xs[0] to ys[0])
    val out = mutableListOf<Pair<Double, Double>>()
    var x = xs.first()
    while (x < xs.last()) {
        out += x to localTrendAt(xs, ys, x, bandwidth)
        x += step
    }
    out += xs.last() to localTrendAt(xs, ys, xs.last(), bandwidth)
    return out
}

/** Runs of readings split where more than [maxGap] days pass without one (a line never bridges a long silence). */
fun <T> splitAtGaps(points: List<T>, day: (T) -> Double, maxGap: Double): List<List<T>> {
    val runs = mutableListOf<MutableList<T>>()
    for (p in points) {
        val last = runs.lastOrNull()?.lastOrNull()
        if (last == null || day(p) - day(last) > maxGap) runs += mutableListOf(p) else runs.last() += p
    }
    return runs
}
