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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.model.HrPoint
import com.bioscan.fieldterminal.domain.minuteOfDayLabel
import com.bioscan.fieldterminal.domain.minuteSegments
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// One day of minute heart rate on a fixed 00:00-24:00 axis (chart rules, ChartRules.kt): a labelled bpm axis, the line
// broken wherever the band had no reading for more than a few minutes (a dashed stretch on the baseline marks each gap,
// so an evening off the wrist reads as missing, not as a flat line), and tap or drag to read a minute.
private const val DAY_MINUTES = 24 * 60

@Composable
fun MinuteHeartRateChart(
    points: List<HrPoint>,
    color: Color,
    modifier: Modifier = Modifier,
    chartHeight: Dp = 120.dp,
) {
    if (points.isEmpty()) return
    val segments = remember(points) { minuteSegments(points) }
    val lo = points.minOf { it.bpm }.toDouble()
    val hi = points.maxOf { it.bpm }.toDouble()
    val pad = ((hi - lo) * 0.08).coerceAtLeast(3.0)
    val yLo = lo - pad
    val yHi = hi + pad

    val measurer = rememberTextMeasurer()
    val ticks = remember(yLo, yHi) { niceTicks(yLo, yHi) }
    val tickLayouts = remember(ticks, measurer) {
        ticks.map { measurer.measure("%.0f".format(it), FTType.Micro.copy(color = FT.TextMuted)) }
    }
    val gutter = (tickLayouts.maxOfOrNull { it.size.width } ?: 0) + 6f
    val gutterDp = with(LocalDensity.current) { gutter.toDp() }
    val padY = 10f

    fun xFor(minute: Int, width: Float): Float = gutter + minute.toFloat() / DAY_MINUTES * (width - gutter)
    fun yFor(bpm: Double, height: Float): Float = padY + (1f - ((bpm - yLo) / (yHi - yLo)).toFloat()) * (height - padY * 2)

    var selected by remember(points) { mutableStateOf<HrPoint?>(null) }

    Column(modifier = modifier.fillMaxWidth()) {
        Text("BPM", style = FTType.MonoCaption, color = FT.TextMuted, modifier = Modifier.padding(bottom = 4.dp))
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(chartHeight)
                .chartInspect(Pair(points, gutter)) { x, width, isTap ->
                    val minute = ((x - gutter) / (width - gutter) * DAY_MINUTES).toInt()
                    val nearest = points.minBy { kotlin.math.abs(it.offsetMinutes - minute) }
                    selected = if (isTap && selected == nearest) null else nearest
                },
        ) {
            ticks.forEachIndexed { i, t ->
                val y = yFor(t, size.height)
                drawLine(FT.GlassBorder, Offset(gutter, y), Offset(size.width, y), strokeWidth = 1f)
                val layout = tickLayouts[i]
                drawText(layout, topLeft = Offset(gutter - 6f - layout.size.width, y - layout.size.height / 2f))
            }
            // Six-hour marks so a time of day can be read off the line.
            for (h in listOf(6, 12, 18)) {
                val x = xFor(h * 60, size.width)
                drawLine(FT.GlassBorder, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
            }
            segments.forEach { seg ->
                if (seg.size == 1) {
                    drawCircle(color, radius = 3f, center = Offset(xFor(seg[0].offsetMinutes, size.width), yFor(seg[0].bpm.toDouble(), size.height)))
                } else {
                    val line = Path().apply {
                        seg.forEachIndexed { i, p ->
                            val x = xFor(p.offsetMinutes, size.width)
                            val y = yFor(p.bpm.toDouble(), size.height)
                            if (i == 0) moveTo(x, y) else lineTo(x, y)
                        }
                    }
                    drawPath(line, color, style = Stroke(width = 2f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
            }
            // Minutes without a reading: a dashed stretch on the baseline, from midnight to the first reading, between
            // runs, and from the last reading to midnight.
            val bounds = listOf(-1 to -1) + segments.map { it.first().offsetMinutes to it.last().offsetMinutes } + listOf(DAY_MINUTES to DAY_MINUTES)
            bounds.zipWithNext().forEach { (before, after) ->
                val x0 = xFor(before.second, size.width) + 4f
                val x1 = xFor(after.first, size.width) - 4f
                if (after.first - before.second > 15 && x1 > x0) {
                    drawLine(FT.TextMuted, Offset(x0, size.height - 2f), Offset(x1, size.height - 2f), strokeWidth = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 5f)))
                }
            }
            selected?.let { p ->
                val x = xFor(p.offsetMinutes, size.width)
                drawInspectOverlay(
                    measurer = measurer,
                    x = x,
                    dots = listOf(Offset(x, yFor(p.bpm.toDouble(), size.height))),
                    header = minuteOfDayLabel(p.offsetMinutes),
                    rows = listOf(InspectRow(null, "${p.bpm} bpm", color)),
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(start = gutterDp, top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("00:00", "06:00", "12:00", "18:00", "24:00").forEach {
                Text(it, style = FTType.MonoCaption, color = FT.TextSecondary)
            }
        }
    }
}
