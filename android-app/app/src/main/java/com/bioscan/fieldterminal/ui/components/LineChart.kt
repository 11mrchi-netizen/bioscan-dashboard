package com.bioscan.fieldterminal.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bioscan.fieldterminal.domain.TimePoint
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import com.bioscan.fieldterminal.ui.theme.RobotoMono

// Phase G5. This app's first general-purpose line/area chart primitive --
// generalizes the old Step-14 RouteCanvas's bounding-box -> single-scale ->
// local xFor/yFor-closure pattern (previously specific to lat/lon) to a
// plain (offsetSeconds, value) domain, so any Health Connect time series can
// reuse it. One series per chart, deliberately: SessionDetailScreen renders
// HR/speed/power/elevation/calories as separate stacked charts on a shared
// elapsed-time axis rather than overlaying series with different units on
// one plot.
//
// Live check: with no xRange given, the x-axis defaults to THIS series' own
// first/last sample -- fine for a standalone series (TrailCard's elevation
// profile), but misleading when a caller switches between several signals
// of the same session (PERFORMANCE's HR/PACE/POWER tabs): a sensor that only
// reported for part of the run stretched to fill the exact same full-width
// canvas as one spanning the whole session, just with a different, easy-to-
// miss start/end label -- looking exactly as "complete" either way. Passing
// a shared xRange (the widest real coverage across every signal being
// compared) makes a partial series visibly short against that shared axis
// instead.
// 25/9 rework: tap or horizontally drag to inspect a sample (elapsed time +
// value in an overlaid box; `valueFormat` lets pace charts print m:ss).
@Composable
fun LineChart(
    points: List<TimePoint>,
    color: Color,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    xRange: Pair<Long, Long>? = null,
    valueFormat: (Double) -> String = { if (kotlin.math.abs(it) >= 100) "%.0f".format(it) else "%.1f".format(it) },
    // Session detail's HR zone toggle: when set, each segment between
    // consecutive points is drawn in segmentColor(startValue) instead of one
    // solid `color` -- every other caller leaves this null and gets today's
    // unchanged single-color line.
    segmentColor: ((Double) -> Color)? = null,
) {
    if (points.size < 2) return

    val pad = 6f
    val xs = points.map { it.offsetSeconds.toFloat() }
    val ys = points.map { it.value.toFloat() }
    val xMin = xRange?.first?.toFloat() ?: xs.min()
    val xMax = (xRange?.second?.toFloat() ?: xs.max()).let { if (it - xMin < 1f) it + 1f else it }
    val yMin = ys.min()
    val yMax = ys.max().let { if (it - yMin < 0.001f) it + 1f else it }
    val xSpan = xMax - xMin
    val ySpan = yMax - yMin

    fun xFor(x: Float, width: Float) = pad + (x - xMin) / xSpan * (width - pad * 2)
    fun yFor(y: Float, height: Float) = pad + (1f - (y - yMin) / ySpan) * (height - pad * 2)

    var selected by remember { mutableStateOf<Int?>(null) }
    val measurer = rememberTextMeasurer()
    val active = selected?.takeIf { it in points.indices }

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp)
                .chartInspect(Triple(points, xMin, xMax)) { x, width, isTap ->
                    val nearest = points.indices.minBy { kotlin.math.abs(xFor(points[it].offsetSeconds.toFloat(), width) - x) }
                    selected = if (isTap && selected == nearest) null else nearest
                },
        ) {
            val line = Path().apply {
                points.forEachIndexed { i, p ->
                    val x = xFor(p.offsetSeconds.toFloat(), size.width)
                    val y = yFor(p.value.toFloat(), size.height)
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
            }

            if (filled) {
                val fill = Path().apply {
                    addPath(line)
                    lineTo(xFor(xMax, size.width), size.height - pad)
                    lineTo(xFor(xMin, size.width), size.height - pad)
                    close()
                }
                drawPath(fill, color = color.copy(alpha = 0.16f))
            }

            if (segmentColor != null) {
                points.zipWithNext().forEach { (a, b) ->
                    drawLine(
                        color = segmentColor(a.value),
                        start = Offset(xFor(a.offsetSeconds.toFloat(), size.width), yFor(a.value.toFloat(), size.height)),
                        end = Offset(xFor(b.offsetSeconds.toFloat(), size.width), yFor(b.value.toFloat(), size.height)),
                        strokeWidth = 4f,
                        cap = StrokeCap.Round,
                    )
                }
            } else {
                drawPath(line, color = color, style = Stroke(width = 4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }

            active?.let { i ->
                val p = points[i]
                val x = xFor(p.offsetSeconds.toFloat(), size.width)
                drawInspectOverlay(
                    measurer = measurer,
                    x = x,
                    dots = listOf(Offset(x, yFor(p.value.toFloat(), size.height))),
                    header = formatElapsed(p.offsetSeconds),
                    rows = listOf(InspectRow(null, valueFormat(p.value), color)),
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatElapsed(xRange?.first ?: points.first().offsetSeconds), style = TextStyle(fontFamily = RobotoMono, fontSize = 11.sp), color = FT.TextSecondary)
            Text(formatElapsed(xRange?.second ?: points.last().offsetSeconds), style = TextStyle(fontFamily = RobotoMono, fontSize = 11.sp), color = FT.TextSecondary)
        }
    }
}

private fun formatElapsed(seconds: Long): String {
    val totalMinutes = seconds / 60
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
