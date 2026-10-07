package com.bioscan.fieldterminal.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bioscan.fieldterminal.domain.trail.TrailChartPoint
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import com.bioscan.fieldterminal.ui.theme.RobotoMono

// 28/9. Same colors SessionDetailScreen's own PerfSignal already uses for
// these three signals -- kept as a small local enum rather than exporting
// PerfSignal (a private screen-level concern) across files for one shared
// palette.
enum class OverlaySignal(val label: String, val color: Color) {
    HR("HR", FT.DomainHeart),
    PACE("PACE", FT.Category.Activity.c500),
    CADENCE("CADENCE", FT.TextSecondary),
}

private data class OverlayLine(val signal: OverlaySignal, val values: List<Float?>, val min: Float, val max: Float)

private fun OverlaySignal.valueOf(p: TrailChartPoint): Float? = when (this) {
    OverlaySignal.HR -> p.heartRate?.toFloat()
    OverlaySignal.PACE -> p.paceMinPerKm?.toFloat()
    OverlaySignal.CADENCE -> p.cadenceSpm?.toFloat()
}

private fun OverlaySignal.format(v: Float): String = when (this) {
    OverlaySignal.HR -> "${v.toInt()} bpm"
    OverlaySignal.PACE -> "%d:%02d /km".format(v.toInt(), ((v - v.toInt()) * 60).toInt())
    OverlaySignal.CADENCE -> "${v.toInt()} spm"
}

// The TRAIL card's elevation profile (distance-keyed, unchanged) with an
// optional HR/PACE/CADENCE overlay per toggled pill below the chart --
// multi-select, so more than one can show at once. Each overlay is
// normalized to its own [min, max] over the plot's height rather than a
// second (dual) y-axis -- a chart with two differently-scaled y-axes is the
// one thing to avoid (see the dataviz skill); elevation keeps the real
// meters axis, everything else is relative shape only, which is exactly what
// "does my HR climb with the hill" or "where did my cadence drop" need.
// Tap/drag to inspect (chartInspect/drawInspectOverlay, same as LineChart)
// shows elevation plus every active overlay's real value at that point.
@Composable
fun TrailElevationChart(points: List<TrailChartPoint>, modifier: Modifier = Modifier) {
    if (points.size < 2) return

    var active by remember(points) { mutableStateOf(setOf<OverlaySignal>()) }
    var selected by remember(points) { mutableStateOf<Int?>(null) }
    val measurer = rememberTextMeasurer()

    val pad = 6f
    val xs = points.map { it.distanceM.toFloat() }
    val elevations = points.map { it.elevationM.toFloat() }
    val xMin = xs.min()
    val xMax = xs.max().let { if (it - xMin < 1f) it + 1f else it }
    val eMin = elevations.min()
    val eMax = elevations.max().let { if (it - eMin < 0.001f) it + 1f else it }

    fun xFor(x: Float, width: Float) = pad + (x - xMin) / (xMax - xMin) * (width - pad * 2)
    fun yForElevation(e: Float, height: Float) = pad + (1f - (e - eMin) / (eMax - eMin)) * (height - pad * 2)

    val overlays: List<OverlayLine> = active.mapNotNull { signal ->
        val values = points.map { signal.valueOf(it) }
        val real = values.filterNotNull()
        if (real.size < 2) return@mapNotNull null
        val min = real.min()
        val max = real.max().let { if (it - min < 0.001f) it + 1f else it }
        OverlayLine(signal, values, min, max)
    }

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .chartInspect(Triple(points, active, xMin)) { x, width, isTap ->
                    val nearest = points.indices.minBy { kotlin.math.abs(xFor(xs[it], width) - x) }
                    selected = if (isTap && selected == nearest) null else nearest
                },
        ) {
            fun yForOverlay(line: OverlayLine, v: Float) =
                pad + (1f - (v - line.min) / (line.max - line.min)) * (size.height - pad * 2)

            val elevationLine = Path().apply {
                points.forEachIndexed { i, p ->
                    val x = xFor(p.distanceM.toFloat(), size.width)
                    val y = yForElevation(p.elevationM.toFloat(), size.height)
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
            }
            val fill = Path().apply {
                addPath(elevationLine)
                lineTo(xFor(xMax, size.width), size.height - pad)
                lineTo(xFor(xMin, size.width), size.height - pad)
                close()
            }
            drawPath(fill, color = FT.DomainTraining.copy(alpha = 0.16f))
            drawPath(elevationLine, color = FT.DomainTraining, style = Stroke(4f, cap = StrokeCap.Round, join = StrokeJoin.Round))

            overlays.forEach { line ->
                val path = Path()
                var started = false
                points.forEachIndexed { i, p ->
                    val v = line.values[i] ?: run { started = false; return@forEachIndexed }
                    val x = xFor(p.distanceM.toFloat(), size.width)
                    val y = yForOverlay(line, v)
                    if (!started) {
                        path.moveTo(x, y)
                        started = true
                    } else {
                        path.lineTo(x, y)
                    }
                }
                drawPath(path, color = line.signal.color, style = Stroke(2.5f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }

            selected?.let { i ->
                val p = points[i]
                val x = xFor(p.distanceM.toFloat(), size.width)
                val dots = mutableListOf(Offset(x, yForElevation(p.elevationM.toFloat(), size.height)))
                val rows = mutableListOf(InspectRow("ELEV", "${p.elevationM.toInt()} m", FT.DomainTraining))
                overlays.forEach { line ->
                    val v = line.values[i] ?: return@forEach
                    dots += Offset(x, yForOverlay(line, v))
                    rows += InspectRow(line.signal.label, line.signal.format(v), line.signal.color)
                }
                drawInspectOverlay(
                    measurer = measurer,
                    x = x,
                    dots = dots,
                    header = "%.2f km".format(p.distanceM / 1000.0),
                    rows = rows,
                )
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("0.0 KM", style = TextStyle(fontFamily = RobotoMono, fontSize = 11.sp), color = FT.TextSecondary)
            Text("%.1f KM".format(xMax / 1000.0), style = TextStyle(fontFamily = RobotoMono, fontSize = 11.sp), color = FT.TextSecondary)
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OverlaySignal.entries.forEach { signal ->
                val isOn = signal in active
                Box(
                    modifier = Modifier
                        .border(FT.BorderWidth, if (isOn) signal.color else FT.GlassBorder, RoundedCornerShape(FT.RadiusSmall))
                        .background(if (isOn) signal.color.copy(alpha = 0.14f) else Color.Transparent, RoundedCornerShape(FT.RadiusSmall))
                        .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                            active = if (isOn) active - signal else active + signal
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(signal.label, style = TextStyle(fontFamily = RobotoMono, fontSize = 11.sp), color = if (isOn) signal.color else FT.TextSecondary)
                }
            }
        }
    }
}
