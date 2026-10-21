package com.bioscan.fieldterminal.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.bioscan.fieldterminal.domain.StackedWeek
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.format.DateTimeFormatter

private val WEEK_FMT = DateTimeFormatter.ofPattern("d MMM")

/** One part of a stacked bar: its name (legend and readout) and colour. */
data class StackPart(val label: String, val color: Color)

// Weekly bars split into parts (the Running page's TRAIL / EASY / QUALITY kilometres), stacked from the first part at
// the bottom, with an optional trailing-average line of the totals. Parts sit 2 dp apart and only the top of the stack
// is rounded; the readout above names every part of the shown week, so the split never rests on colour alone. Quiet
// weeks draw a thin baseline tick, as FTWeeklyBars does.
@Composable
fun FTStackedWeeklyBars(
    weeks: List<StackedWeek>,
    parts: List<StackPart>,
    unit: String,
    modifier: Modifier = Modifier,
    average: List<Double>? = null,
    averageLabel: String = "4-wk avg",
    valueFormat: (Double) -> String = { "%.1f".format(it) },
    height: Dp = 120.dp,
) {
    if (weeks.isEmpty()) return
    var selected by remember(weeks) { mutableStateOf<Int?>(null) }
    val shown = selected ?: weeks.lastIndex
    val top = maxOf(weeks.maxOf { it.total }, average?.maxOrNull() ?: 0.0).takeIf { it > 0 } ?: 1.0

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("WEEK OF ${WEEK_FMT.format(weeks[shown].weekStart).uppercase()}", style = FTType.Label, color = FT.TextMuted, modifier = Modifier.weight(1f))
            Text("${valueFormat(weeks[shown].total)} $unit", style = FTType.Value, color = FT.TextPrimary)
        }
        Text(
            parts.indices.reversed().joinToString("  ·  ") { "${parts[it].label} ${valueFormat(weeks[shown].parts.getOrElse(it) { 0.0 })}" },
            style = FTType.Micro, color = FT.TextSecondary,
        )
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(height)
                .pointerInput(weeks) { detectTapGestures { selected = indexAt(it.x, size.width.toFloat(), weeks.size) } }
                .pointerInput(weeks) { detectHorizontalDragGestures { change, _ -> selected = indexAt(change.position.x, size.width.toFloat(), weeks.size) } },
        ) {
            val slot = size.width / weeks.size
            val barW = (slot * 0.62f).coerceAtMost(26.dp.toPx())
            val usable = size.height - 4.dp.toPx()
            val gap = 2.dp.toPx()
            val radius = minOf(4.dp.toPx(), barW / 2f)
            weeks.forEachIndexed { i, w ->
                val x = i * slot + (slot - barW) / 2f
                val alpha = if (i == shown) 1f else if (i == weeks.lastIndex) 0.85f else 0.5f
                if (w.total <= 0) {
                    drawRoundRect(FT.GlassTrack, Offset(x, size.height - 2.dp.toPx()), Size(barW, 2.dp.toPx()), CornerRadius(1.dp.toPx()))
                    return@forEachIndexed
                }
                val topPart = w.parts.indexOfLast { it > 0 }
                var y = size.height
                w.parts.forEachIndexed { p, v ->
                    if (v <= 0) return@forEachIndexed
                    val h = (v / top).toFloat() * usable
                    val drawn = if (p == topPart) h else (h - gap).coerceAtLeast(1f)
                    val color = parts[p].color.copy(alpha = alpha)
                    if (p == topPart) {
                        // Rounded at the top only: a round rect, its lower corners squared off by a plain rect.
                        drawRoundRect(color, Offset(x, y - drawn), Size(barW, drawn), CornerRadius(radius))
                        val square = minOf(radius, drawn / 2f)
                        drawRect(color, Offset(x, y - square), Size(barW, square))
                    } else {
                        drawRect(color, Offset(x, y - drawn), Size(barW, drawn))
                    }
                    y -= h
                }
            }
            if (average != null && average.size == weeks.size) {
                val path = Path()
                average.forEachIndexed { i, v ->
                    val pt = Offset(i * slot + slot / 2f, size.height - (v / top).toFloat() * usable)
                    if (i == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
                }
                drawPath(path, FT.TextPrimary.copy(alpha = 0.85f), style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(WEEK_FMT.format(weeks.first().weekStart).uppercase(), style = FTType.Micro, color = FT.TextMuted)
            Text(
                "SCALE 0-${valueFormat(top)} $unit" + if (average != null) "  ·  LINE: ${averageLabel.uppercase()}" else "",
                style = FTType.Micro, color = FT.TextMuted,
            )
            Text(WEEK_FMT.format(weeks.last().weekStart).uppercase(), style = FTType.Micro, color = FT.TextMuted)
        }
    }
}

/** A legend row: a swatch and its words (the label, plus whatever the caller adds, e.g. kilometres and share). */
@Composable
fun StackLegendRow(part: StackPart, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(10.dp).background(part.color, RoundedCornerShape(2.dp)))
        Text(part.label, style = FTType.LabelCaps, color = FT.TextPrimary, modifier = Modifier.weight(1f))
        Text(text, style = FTType.Value, color = FT.TextSecondary)
    }
}

private fun indexAt(x: Float, width: Float, count: Int): Int =
    ((x / width) * count).toInt().coerceIn(0, count - 1)
