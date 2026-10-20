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
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.domain.TimePoint
import com.bioscan.fieldterminal.domain.movingOffset
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

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
    // Run session page: colour each segment by its start time instead (pace coloured by the heart-rate zone at that
    // moment). Wins over segmentColor when both are set.
    segmentColorAt: ((Long) -> Color)? = null,
    // Pauses as (start, end) offsets. The axis becomes moving time: each pause is closed up, the line runs straight on,
    // and a dashed mark with the pause's length shows where it stopped (the old charts drew a long straight line across
    // the stop instead).
    pauses: List<Pair<Long, Long>> = emptyList(),
    // Lower-is-better series (pace in min/km): plot low values at the top so
    // "up" always means "better".
    invertY: Boolean = false,
    // Chart frame: unit header (see DateTrendLine).
    unit: String? = null,
) {
    val stops = pauses.filter { (from, to) -> to > from }.sortedBy { it.first }
    // A sample inside a pause would sit on the mark; leave it out.
    val shown = if (stops.isEmpty()) points else points.filterNot { p -> stops.any { (from, to) -> p.offsetSeconds > from && p.offsetSeconds < to } }
    if (shown.size < 2) return
    fun axis(t: Long): Float = movingOffset(t, stops).toFloat()

    val pad = 6f
    val xs = shown.map { axis(it.offsetSeconds) }
    val ys = shown.map { it.value.toFloat() }
    val xMin = xRange?.first?.let { axis(it) } ?: xs.min()
    val xMax = (xRange?.second?.let { axis(it) } ?: xs.max()).let { if (it - xMin < 1f) it + 1f else it }
    val yMin = ys.min()
    val yMax = ys.max().let { if (it - yMin < 0.001f) it + 1f else it }
    val xSpan = xMax - xMin
    val ySpan = yMax - yMin

    fun xFor(x: Float, width: Float) = pad + (x - xMin) / xSpan * (width - pad * 2)
    fun yFor(y: Float, height: Float): Float {
        val t = (y - yMin) / ySpan
        return pad + (if (invertY) t else 1f - t) * (height - pad * 2)
    }

    var selected by remember { mutableStateOf<Int?>(null) }
    val measurer = rememberTextMeasurer()
    val active = selected?.takeIf { it in shown.indices }

    Column(modifier = modifier.fillMaxWidth()) {
        unit?.let {
            Text(it.uppercase(), style = FTType.MonoCaption, color = FT.TextMuted, modifier = Modifier.padding(bottom = 4.dp))
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp)
                .chartInspect(Triple(shown, xMin, xMax)) { x, width, isTap ->
                    val nearest = shown.indices.minBy { kotlin.math.abs(xFor(xs[it], width) - x) }
                    selected = if (isTap && selected == nearest) null else nearest
                },
        ) {
            val line = Path().apply {
                shown.forEachIndexed { i, p ->
                    val x = xFor(xs[i], size.width)
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

            stops.forEach { (from, to) ->
                val x = xFor(axis(from), size.width)
                if (x < 0f || x > size.width) return@forEach
                drawLine(
                    FT.TextSecondary.copy(alpha = 0.6f), Offset(x, 0f), Offset(x, size.height), strokeWidth = 2f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)),
                )
                val tag = measurer.measure("PAUSE " + formatPause(to - from), pauseTagStyle)
                val left = if (x + 6f + tag.size.width <= size.width) x + 6f else x - 6f - tag.size.width
                drawText(tag, topLeft = Offset(left, size.height - tag.size.height - 2f))
            }
            if (segmentColor != null || segmentColorAt != null) {
                shown.indices.toList().zipWithNext().forEach { (i, j) ->
                    val a = shown[i]
                    val b = shown[j]
                    drawLine(
                        color = segmentColorAt?.invoke(a.offsetSeconds) ?: segmentColor?.invoke(a.value) ?: color,
                        start = Offset(xFor(xs[i], size.width), yFor(a.value.toFloat(), size.height)),
                        end = Offset(xFor(xs[j], size.width), yFor(b.value.toFloat(), size.height)),
                        strokeWidth = 4f,
                        cap = StrokeCap.Round,
                    )
                }
            } else {
                drawPath(line, color = color, style = Stroke(width = 4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }

            active?.let { i ->
                val p = shown[i]
                val x = xFor(xs[i], size.width)
                drawInspectOverlay(
                    measurer = measurer,
                    x = x,
                    dots = listOf(Offset(x, yFor(p.value.toFloat(), size.height))),
                    header = formatElapsed(xs[i].toLong()),
                    rows = listOf(InspectRow(null, valueFormat(p.value), color)),
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatElapsed(xMin.toLong()), style = FTType.MonoCaption, color = FT.TextSecondary)
            Text(formatElapsed(xMax.toLong()), style = FTType.MonoCaption, color = FT.TextSecondary)
        }
    }
}

private val pauseTagStyle = FTType.MonoCaption.copy(color = FT.TextSecondary)

private fun formatPause(seconds: Long): String = if (seconds < 60) "${seconds}s" else formatElapsed(seconds)

private fun formatElapsed(seconds: Long): String {
    val totalMinutes = seconds / 60
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
