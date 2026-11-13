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
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.domain.StackedWeek
import com.bioscan.fieldterminal.ui.theme.FTType
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

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
    // This week is still filling: faded and read as "so far", so it is not taken for a drop.
    val partial = !weeks.last().weekStart.plusDays(6).isBefore(LocalDate.now())

    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("WEEK OF ${WEEK_FMT.format(weeks[shown].weekStart).uppercase()}" + if (partial && shown == weeks.lastIndex) " · SO FAR" else "", style = FTType.Label, color = FT.TextMuted, modifier = Modifier.weight(1f))
            Text("${valueFormat(weeks[shown].total)} $unit", style = FTType.Value, color = FT.TextPrimary)
        }
        Text(
            parts.indices.reversed().joinToString("  ·  ") { "${parts[it].label} ${valueFormat(weeks[shown].parts.getOrElse(it) { 0.0 })}" },
            style = FTType.Micro, color = FT.TextSecondary,
        )
        Row(Modifier.fillMaxWidth()) {
        BarYAxis(top, valueFormat, height)
        Canvas(
            modifier = Modifier
                .weight(1f)
                .height(height)
                .pointerInput(weeks) { detectTapGestures { selected = BarLayout(size.width.toFloat(), weeks.size, 0f).indexAt(it.x) } }
                .pointerInput(weeks) { detectHorizontalDragGestures { change, _ -> selected = BarLayout(size.width.toFloat(), weeks.size, 0f).indexAt(change.position.x) } },
        ) {
            val layout = barLayout(weeks.size)
            val usable = size.height - FTBarSpec.TopPad.toPx()
            weeks.forEachIndexed { i, w ->
                val x = layout.left(i)
                if (w.total <= 0) {
                    drawBarStub(x, layout.barWidth)
                    return@forEachIndexed
                }
                drawStackedBar(w.parts, parts.map { it.color }, x, layout.barWidth, { v -> (v / top).toFloat() * usable },
                    alpha = barAlpha(i, selected, projected = partial && i == weeks.lastIndex && selected != i), cap = i == shown)
            }
            if (average != null && average.size == weeks.size) {
                val path = Path()
                average.forEachIndexed { i, v ->
                    val pt = Offset(layout.center(i), size.height - (v / top).toFloat() * usable)
                    if (i == 0) path.moveTo(pt.x, pt.y) else path.lineTo(pt.x, pt.y)
                }
                drawPath(path, FT.TextPrimary.copy(alpha = 0.85f), style = Stroke(width = 1.5.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(start = 40.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(WEEK_FMT.format(weeks.first().weekStart).uppercase(), style = FTType.Micro, color = FT.TextMuted)
            Text(
                unit.uppercase() + (if (average != null) "  ·  LINE: ${averageLabel.uppercase()}" else "") + if (partial) "  ·  FADED: SO FAR" else "",
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

