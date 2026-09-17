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
import com.bioscan.fieldterminal.ui.theme.FieldColors
import com.bioscan.fieldterminal.ui.theme.JetBrainsMono
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

@Composable
fun DateTrendLine(
    points: List<Pair<LocalDate, Double>>,
    color: Color,
    refLow: Double? = null,
    refHigh: Double? = null,
    modifier: Modifier = Modifier,
) {
    if (points.size < 2) return
    val sorted = points.sortedBy { it.first }

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(modifier = Modifier.fillMaxWidth().height(90.dp)) {
            val padX = 12f
            val padY = 10f

            val dayMin = sorted.first().first
            val dayMax = sorted.last().first
            val daySpan = ChronoUnit.DAYS.between(dayMin, dayMax).toFloat().let { if (it < 1f) 1f else it }

            val values = sorted.map { it.second }
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
                    color = FieldColors.InkMuted.copy(alpha = 0.14f),
                    topLeft = Offset(0f, yFor(refHigh)),
                    size = Size(size.width, yFor(refLow) - yFor(refHigh)),
                )
            }

            val line = Path().apply {
                sorted.forEachIndexed { i, (day, value) ->
                    val x = xFor(day)
                    val y = yFor(value)
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
            }
            drawPath(line, color = color, style = Stroke(width = 4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(sorted.first().first.format(TREND_DATE_FORMAT), style = TextStyle(fontFamily = JetBrainsMono, fontSize = 11.sp), color = FieldColors.InkMuted)
            Text(sorted.last().first.format(TREND_DATE_FORMAT), style = TextStyle(fontFamily = JetBrainsMono, fontSize = 11.sp), color = FieldColors.InkMuted)
        }
    }
}
