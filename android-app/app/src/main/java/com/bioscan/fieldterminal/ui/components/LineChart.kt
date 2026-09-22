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
@Composable
fun LineChart(points: List<TimePoint>, color: Color, modifier: Modifier = Modifier, filled: Boolean = false) {
    if (points.size < 2) return

    Column(modifier = modifier.fillMaxWidth()) {
        Canvas(modifier = Modifier.fillMaxWidth().height(96.dp)) {
            val pad = 6f
            val xs = points.map { it.offsetSeconds.toFloat() }
            val ys = points.map { it.value.toFloat() }
            val xMin = xs.min()
            val xMax = xs.max().let { if (it - xMin < 1f) it + 1f else it }
            val yMin = ys.min()
            val yMax = ys.max().let { if (it - yMin < 0.001f) it + 1f else it }
            val xSpan = xMax - xMin
            val ySpan = yMax - yMin

            fun xFor(x: Float) = pad + (x - xMin) / xSpan * (size.width - pad * 2)
            fun yFor(y: Float) = pad + (1f - (y - yMin) / ySpan) * (size.height - pad * 2)

            val line = Path().apply {
                points.forEachIndexed { i, p ->
                    val x = xFor(p.offsetSeconds.toFloat())
                    val y = yFor(p.value.toFloat())
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
            }

            if (filled) {
                val fill = Path().apply {
                    addPath(line)
                    lineTo(xFor(xMax), size.height - pad)
                    lineTo(xFor(xMin), size.height - pad)
                    close()
                }
                drawPath(fill, color = color.copy(alpha = 0.16f))
            }

            drawPath(line, color = color, style = Stroke(width = 4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatElapsed(points.first().offsetSeconds), style = TextStyle(fontFamily = RobotoMono, fontSize = 11.sp), color = FT.TextSecondary)
            Text(formatElapsed(points.last().offsetSeconds), style = TextStyle(fontFamily = RobotoMono, fontSize = 11.sp), color = FT.TextSecondary)
        }
    }
}

private fun formatElapsed(seconds: Long): String {
    val totalMinutes = seconds / 60
    val h = totalMinutes / 60
    val m = totalMinutes % 60
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
