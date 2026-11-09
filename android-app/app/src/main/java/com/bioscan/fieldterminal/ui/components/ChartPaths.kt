package com.bioscan.fieldterminal.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path

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
