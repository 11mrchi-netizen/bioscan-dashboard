package com.bioscan.fieldterminal.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import kotlin.math.roundToInt

// DAV-288: carbs/protein/fat as a share of macro grams (not calories -- grams
// is what MACROS already shows elsewhere on this page, so the donut reads
// consistently). Fixed categorical hue order, never cycled or reassigned when
// a slice happens to be zero (dataviz skill's color-formula: identity, not rank).
private val MACRO_COLORS = listOf(FT.DomainFuel, FT.Emerald, FT.Warning) // carbs, protein, fat, in that fixed order

@Composable
fun MacroDonutChart(carbsG: Double, proteinG: Double, fatG: Double, modifier: Modifier = Modifier) {
    val values = listOf(carbsG.coerceAtLeast(0.0), proteinG.coerceAtLeast(0.0), fatG.coerceAtLeast(0.0))
    val total = values.sum()
    if (total <= 0.0) return

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Canvas(modifier = Modifier.size(110.dp)) {
            val strokeWidth = size.minDimension * 0.22f
            val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
            val topLeft = Offset(strokeWidth / 2, strokeWidth / 2)
            var startAngle = -90f
            values.forEachIndexed { i, v ->
                val sweep = (v / total * 360.0).toFloat()
                drawArc(
                    color = MACRO_COLORS[i],
                    startAngle = startAngle,
                    sweepAngle = sweep,
                    useCenter = false,
                    style = Stroke(width = strokeWidth),
                    topLeft = topLeft,
                    size = arcSize,
                )
                startAngle += sweep
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MacroLegendRow("Carbs", values[0], total, MACRO_COLORS[0])
            MacroLegendRow("Protein", values[1], total, MACRO_COLORS[1])
            MacroLegendRow("Fat", values[2], total, MACRO_COLORS[2])
        }
    }
}

@Composable
private fun MacroLegendRow(label: String, grams: Double, total: Double, color: Color) {
    val pct = if (total > 0) (grams / total * 100).roundToInt() else 0
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(modifier = Modifier.size(10.dp).background(color, CircleShape))
        Text("$label — ${grams.roundToInt()}g ($pct%)", style = FTType.BodySmall, color = FT.TextSecondary)
    }
}
