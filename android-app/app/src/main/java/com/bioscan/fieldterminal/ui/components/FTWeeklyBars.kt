package com.bioscan.fieldterminal.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.domain.WeekBucket
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.format.DateTimeFormatter

private val WEEK_FMT = DateTimeFormatter.ofPattern("d MMM")

// Weekly bucket bars with an optional trailing-average line. Quiet weeks are real zeros: they draw a thin
// baseline tick, never a gap that could be mistaken for missing data. The latest week is emphasised; tap or
// drag to inspect a week (the readout above the chart). The scale is the data's own maximum -- no invented
// ceiling -- and the top-of-scale value is printed so the bars can be read.
@Composable
fun FTWeeklyBars(
    buckets: List<WeekBucket>,
    color: Color,
    unit: String,
    modifier: Modifier = Modifier,
    average: List<Double>? = null,
    averageLabel: String = "4-wk avg",
    valueFormat: (Double) -> String = { "%.1f".format(it) },
    height: Dp = 120.dp,
) {
    if (buckets.isEmpty()) return
    var selected by remember(buckets) { mutableStateOf<Int?>(null) }
    val shown = selected ?: buckets.lastIndex
    val top = (maxOf(buckets.maxOf { it.value }, average?.maxOrNull() ?: 0.0)).takeIf { it > 0 } ?: 1.0

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("WEEK OF ${WEEK_FMT.format(buckets[shown].weekStart).uppercase()}", style = FTType.Label, color = FT.TextMuted, modifier = Modifier.weight(1f))
            Text("${valueFormat(buckets[shown].value)} $unit", style = FTType.Telemetry, color = color)
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .pointerInput(buckets) {
                    detectTapGestures { selected = BarLayout(size.width.toFloat(), buckets.size, 0f).indexAt(it.x) }
                }
                .pointerInput(buckets) {
                    detectHorizontalDragGestures { change, _ -> selected = BarLayout(size.width.toFloat(), buckets.size, 0f).indexAt(change.position.x) }
                },
        ) {
            val layout = barLayout(buckets.size)
            val base = size.height
            buckets.forEachIndexed { i, b ->
                val x = layout.left(i)
                val h = (b.value / top).toFloat() * (size.height - FTBarSpec.TopPad.toPx())
                if (b.value > 0) {
                    drawBar(color, x, base - h, layout.barWidth, alpha = barAlpha(i, selected), cap = i == shown)
                } else {
                    drawBarStub(x, layout.barWidth)
                }
            }
            if (average != null && average.size == buckets.size) {
                val path = Path()
                average.forEachIndexed { i, v ->
                    val p = Offset(layout.center(i), base - (v / top).toFloat() * (size.height - FTBarSpec.TopPad.toPx()))
                    if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                drawPath(path, androidx.compose.ui.graphics.lerp(color, androidx.compose.ui.graphics.Color.White, 0.5f).copy(alpha = 0.8f), style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(WEEK_FMT.format(buckets.first().weekStart).uppercase(), style = FTType.Micro, color = FT.TextMuted)
            Text(
                "SCALE 0-${valueFormat(top)} $unit" + if (average != null) "  ·  LINE: ${averageLabel.uppercase()}" else "",
                style = FTType.Micro, color = FT.TextMuted,
            )
            Text(WEEK_FMT.format(buckets.last().weekStart).uppercase(), style = FTType.Micro, color = FT.TextMuted)
        }
    }
}

