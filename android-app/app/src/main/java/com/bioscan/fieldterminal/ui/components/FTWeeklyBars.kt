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
            Text("${valueFormat(buckets[shown].value)} $unit", style = FTType.Value, color = FT.TextPrimary)
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .pointerInput(buckets) {
                    detectTapGestures { selected = indexAt(it.x, size.width.toFloat(), buckets.size) }
                }
                .pointerInput(buckets) {
                    detectHorizontalDragGestures { change, _ -> selected = indexAt(change.position.x, size.width.toFloat(), buckets.size) }
                },
        ) {
            val slot = size.width / buckets.size
            val barW = (slot * 0.62f).coerceAtMost(26.dp.toPx())
            val base = size.height
            buckets.forEachIndexed { i, b ->
                val x = i * slot + (slot - barW) / 2f
                val h = (b.value / top).toFloat() * (size.height - 4.dp.toPx())
                val alpha = if (i == shown) 1f else if (i == buckets.lastIndex) 0.85f else 0.5f
                if (b.value > 0) {
                    drawRoundRect(color.copy(alpha = alpha), Offset(x, base - h), Size(barW, h), CornerRadius(barW / 3f))
                } else {
                    drawRoundRect(FT.GlassTrack, Offset(x, base - 2.dp.toPx()), Size(barW, 2.dp.toPx()), CornerRadius(1.dp.toPx()))
                }
            }
            if (average != null && average.size == buckets.size) {
                val path = Path()
                average.forEachIndexed { i, v ->
                    val p = Offset(i * slot + slot / 2f, base - (v / top).toFloat() * (size.height - 4.dp.toPx()))
                    if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                drawPath(path, FT.TextPrimary.copy(alpha = 0.85f), style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round))
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

private fun indexAt(x: Float, width: Float, count: Int): Int =
    ((x / width) * count).toInt().coerceIn(0, count - 1)
