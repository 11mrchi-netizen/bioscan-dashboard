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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import com.bioscan.fieldterminal.ui.theme.RobotoMono
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

// Phase A5, Part 1 (Category 9). Isolated real draws plotted against a
// reference band -- deliberately NOT a line chart. The spec is explicit
// that ~1 draw/year doesn't support the continuity a connecting line would
// imply, so points never connect here, however many real draws exist.
// Shares LineChart.kt's own Canvas + bounding-box + local xFor/yFor-closure
// technique (Phase G5), adapted to a calendar-date x-axis instead of
// elapsed-session-seconds -- a genuinely different domain, not a
// generalization of that file.
private val DOT_DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM")

@Composable
fun DotPlot(
    points: List<Pair<LocalDate, Double>>,
    refLow: Double?,
    refHigh: Double?,
    dotColor: (Double) -> Color,
    modifier: Modifier = Modifier,
) {
    if (points.isEmpty()) return
    val sorted = points.sortedBy { it.first }

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(modifier = Modifier.fillMaxWidth().height(72.dp)) {
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
            // A little headroom above/below so a boundary point (or the
            // band's own edge) never draws flush against the chart edge.
            val ySpan = (yMax - yMin) * 1.15
            val yCenter = (yMax + yMin) / 2
            val yLo = yCenter - ySpan / 2
            val yHi = yCenter + ySpan / 2

            fun xFor(day: LocalDate): Float {
                if (sorted.size == 1) return size.width / 2f
                val offset = ChronoUnit.DAYS.between(dayMin, day).toFloat()
                return padX + offset / daySpan * (size.width - padX * 2)
            }
            fun yFor(value: Double): Float =
                padY + (1f - ((value - yLo) / (yHi - yLo)).toFloat()) * (size.height - padY * 2)

            if (refLow != null && refHigh != null) {
                drawRect(
                    color = FT.TextSecondary.copy(alpha = 0.14f),
                    topLeft = androidx.compose.ui.geometry.Offset(0f, yFor(refHigh)),
                    size = androidx.compose.ui.geometry.Size(size.width, yFor(refLow) - yFor(refHigh)),
                )
            }

            sorted.forEach { (day, value) ->
                drawCircle(color = dotColor(value), radius = 7f, center = androidx.compose.ui.geometry.Offset(xFor(day), yFor(value)))
            }
        }
        if (sorted.size > 1) {
            Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(sorted.first().first.format(DOT_DATE_FORMAT), style = TextStyle(fontFamily = RobotoMono, fontSize = 11.sp), color = FT.TextSecondary)
                Text(sorted.last().first.format(DOT_DATE_FORMAT), style = TextStyle(fontFamily = RobotoMono, fontSize = 11.sp), color = FT.TextSecondary)
            }
        }
    }
}
