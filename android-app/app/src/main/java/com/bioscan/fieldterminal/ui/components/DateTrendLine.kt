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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import com.bioscan.fieldterminal.ui.theme.RobotoMono
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

// DAV-76. Shares DotPlot.kt's own Canvas + bounding-box + calendar-date
// xFor/yFor technique, but connects points with a line -- unlike bloodwork's
// ~1 draw/year (where a line would falsely imply continuity DotPlot's own
// header comment explains), a daily weight EMA or daily nutrition series is
// genuinely continuous enough for one. An optional reference band (e.g. the
// AMDR protein range) reuses DotPlot's exact band-drawing rectangle.
private val TREND_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")

data class TrendSeries(val points: List<Pair<LocalDate, Double>>, val color: Color)

@Composable
fun DateTrendLine(
    points: List<Pair<LocalDate, Double>>,
    color: Color,
    refLow: Double? = null,
    refHigh: Double? = null,
    modifier: Modifier = Modifier,
) = DateTrendLine(series = listOf(TrendSeries(points, color)), refLow = refLow, refHigh = refHigh, modifier = modifier)

// DAV-80: multi-series overload -- raw/7-day-avg/28-day-avg VO2max all share
// one y-axis (same unit, just different smoothing), unlike weight vs.
// nutrition's genuinely different units, which stay on separate stacked
// charts instead of one dual-axis plot. All series share one bounding box
// so they read as directly comparable, not independently scaled.
@Composable
fun DateTrendLine(
    series: List<TrendSeries>,
    refLow: Double? = null,
    refHigh: Double? = null,
    modifier: Modifier = Modifier,
) {
    val sortedSeries = series.map { it.copy(points = it.points.sortedBy { p -> p.first }) }.filter { it.points.size >= 2 }
    if (sortedSeries.isEmpty()) return
    val allPoints = sortedSeries.flatMap { it.points }

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(modifier = Modifier.fillMaxWidth().height(90.dp)) {
            val padX = 12f
            val padY = 10f

            val dayMin = allPoints.minOf { it.first }
            val dayMax = allPoints.maxOf { it.first }
            val daySpan = ChronoUnit.DAYS.between(dayMin, dayMax).toFloat().let { if (it < 1f) 1f else it }

            val values = allPoints.map { it.second }
            var yMin = values.min()
            var yMax = values.max()
            if (refLow != null) yMin = minOf(yMin, refLow)
            if (refHigh != null) yMax = maxOf(yMax, refHigh)
            if (yMax - yMin < 0.001) {
                yMin -= 1.0
                yMax += 1.0
            }
            val ySpan = (yMax - yMin) * 1.15
            val yCenter = (yMax + yMin) / 2
            val yLo = yCenter - ySpan / 2
            val yHi = yCenter + ySpan / 2

            fun xFor(day: LocalDate): Float {
                val offset = ChronoUnit.DAYS.between(dayMin, day).toFloat()
                return padX + offset / daySpan * (size.width - padX * 2)
            }
            fun yFor(value: Double): Float =
                padY + (1f - ((value - yLo) / (yHi - yLo)).toFloat()) * (size.height - padY * 2)

            if (refLow != null && refHigh != null) {
                drawRect(
                    color = FT.TextSecondary.copy(alpha = 0.14f),
                    topLeft = Offset(0f, yFor(refHigh)),
                    size = Size(size.width, yFor(refLow) - yFor(refHigh)),
                )
            }

            sortedSeries.forEach { s ->
                val line = Path().apply {
                    s.points.forEachIndexed { i, (day, value) ->
                        val x = xFor(day)
                        val y = yFor(value)
                        if (i == 0) moveTo(x, y) else lineTo(x, y)
                    }
                }
                drawPath(line, color = s.color, style = Stroke(width = 4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(allPoints.minOf { it.first }.format(TREND_DATE_FORMAT), style = TextStyle(fontFamily = RobotoMono, fontSize = 11.sp), color = FT.TextSecondary)
            Text(allPoints.maxOf { it.first }.format(TREND_DATE_FORMAT), style = TextStyle(fontFamily = RobotoMono, fontSize = 11.sp), color = FT.TextSecondary)
        }
    }
}
