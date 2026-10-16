package com.bioscan.fieldterminal.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.LocalDate
import java.time.temporal.ChronoUnit

// One cell of a small-multiples grid: a metric's own sparkline on its own scale, with its latest value.
// Fewer than two points draws a "Building" cell instead of a line (never a line through one observation).
data class SmallMultiple(
    val label: String,
    val unit: String,
    val points: List<Pair<LocalDate, Double>>,
    val latestText: String?,
    val color: Color,
    val note: String? = null,
)

@Composable
fun FTSmallMultiples(
    items: List<SmallMultiple>,
    modifier: Modifier = Modifier,
    columns: Int = 2,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.chunked(columns).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { SmallMultipleCell(it, Modifier.weight(1f)) }
                repeat(columns - row.size) { Box(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun SmallMultipleCell(item: SmallMultiple, modifier: Modifier) {
    Column(
        modifier = modifier
            .background(FT.GlassFill, RoundedCornerShape(FT.RadiusModule))
            .border(FT.BorderWidth, FT.GlassBorder, RoundedCornerShape(FT.RadiusModule))
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(item.label.uppercase(), style = FTType.Label, color = FT.TextMuted, maxLines = 1)
        Row(verticalAlignment = androidx.compose.ui.Alignment.Bottom) {
            Text(item.latestText ?: "—", style = FTType.MetricMedium, color = FT.TextPrimary)
            if (item.latestText != null && item.unit.isNotEmpty()) {
                Text(" ${item.unit}", style = FTType.Caption, color = FT.TextSecondary, modifier = Modifier.padding(bottom = 3.dp))
            }
        }
        if (item.points.size >= 2) {
            Sparkline(item.points, item.color)
        } else {
            Text(item.note ?: "Building — needs more runs with this signal", style = FTType.Caption, color = FT.TextMuted)
        }
    }
}

@Composable
private fun Sparkline(points: List<Pair<LocalDate, Double>>, color: Color) {
    val sorted = points.sortedBy { it.first }
    Canvas(modifier = Modifier.fillMaxWidth().height(36.dp)) {
        val minV = sorted.minOf { it.second }
        val maxV = sorted.maxOf { it.second }
        val span = (maxV - minV).takeIf { it > 0 } ?: 1.0
        val first = sorted.first().first
        val days = ChronoUnit.DAYS.between(first, sorted.last().first).coerceAtLeast(1)
        val pad = 3.dp.toPx()
        fun at(p: Pair<LocalDate, Double>) = Offset(
            pad + (ChronoUnit.DAYS.between(first, p.first).toFloat() / days) * (size.width - 2 * pad),
            pad + (1f - ((p.second - minV) / span).toFloat()) * (size.height - 2 * pad),
        )
        val path = Path()
        sorted.forEachIndexed { i, p -> at(p).let { if (i == 0) path.moveTo(it.x, it.y) else path.lineTo(it.x, it.y) } }
        drawPath(path, color, style = Stroke(width = 1.75.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        val end = at(sorted.last())
        drawCircle(FT.Surface, 4.5.dp.toPx(), end)
        drawCircle(color, 3.dp.toPx(), end)
    }
}
