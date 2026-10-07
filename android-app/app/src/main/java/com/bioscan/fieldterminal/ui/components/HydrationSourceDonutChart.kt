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

// Daily Readiness page: hydration source split. BeverageItemRow has no
// drink-type field (description/volume/caffeine/effective-hydration only),
// so Water vs Beverages (modeled) is the one real categorical split this
// data supports -- not a fabricated coffee/tea/soda breakdown. Same fixed
// categorical order + Canvas drawArc pattern as MacroDonutChart.kt.
private val HYDRATION_SOURCE_COLORS = listOf(FT.Info, FT.DomainFuel) // water, beverages, in that fixed order

@Composable
fun HydrationSourceDonutChart(waterMl: Double, beverageMl: Double, modifier: Modifier = Modifier) {
    val values = listOf(waterMl.coerceAtLeast(0.0), beverageMl.coerceAtLeast(0.0))
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
                    color = HYDRATION_SOURCE_COLORS[i],
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
            HydrationSourceLegendRow("Water", values[0], total, HYDRATION_SOURCE_COLORS[0])
            HydrationSourceLegendRow("Beverages (modeled)", values[1], total, HYDRATION_SOURCE_COLORS[1])
        }
    }
}

@Composable
private fun HydrationSourceLegendRow(label: String, ml: Double, total: Double, color: Color) {
    val pct = if (total > 0) (ml / total * 100).roundToInt() else 0
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(modifier = Modifier.size(10.dp).background(color, CircleShape))
        Text("$label — ${ml.roundToInt()}ml ($pct%)", style = FTType.BodySmall, color = FT.TextSecondary)
    }
}
