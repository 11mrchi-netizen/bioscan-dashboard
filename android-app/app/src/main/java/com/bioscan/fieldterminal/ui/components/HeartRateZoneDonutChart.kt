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
import com.bioscan.fieldterminal.domain.HeartRateZone
import com.bioscan.fieldterminal.domain.HeartRateZoneBreakdown
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import kotlin.math.roundToInt

// Session detail's HR zone breakdown. Its own fixed ordinal palette (low to
// high effort), not a reuse of FT's Warning/Critical -- those are this app's
// reserved data-quality/alert semantics elsewhere, and reusing them for
// "zone 3"/"zone 5" would conflate a status color with a series identity,
// same mixup MacroDonutChart.kt's own dedicated MACRO_COLORS already avoids.
private val HR_ZONE_COLORS: Map<HeartRateZone, Color> = mapOf(
    HeartRateZone.BelowZ1 to FT.TextMuted,
    HeartRateZone.Z1 to Color(0xFF60A5FA),
    HeartRateZone.Z2 to Color(0xFF34D399),
    HeartRateZone.Z3 to Color(0xFFFBBF24),
    HeartRateZone.Z4 to Color(0xFFFB923C),
    HeartRateZone.Z5 to Color(0xFFF87171),
)

fun heartRateZoneColor(zone: HeartRateZone): Color = HR_ZONE_COLORS.getValue(zone)

@Composable
fun HeartRateZoneDonutChart(breakdown: List<HeartRateZoneBreakdown>, modifier: Modifier = Modifier) {
    val nonZero = breakdown.filter { it.seconds > 0 }
    val totalSeconds = nonZero.sumOf { it.seconds }
    if (totalSeconds <= 0) return

    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Canvas(modifier = Modifier.size(110.dp)) {
            val strokeWidth = size.minDimension * 0.22f
            val arcSize = Size(size.width - strokeWidth, size.height - strokeWidth)
            val topLeft = Offset(strokeWidth / 2, strokeWidth / 2)
            var startAngle = -90f
            nonZero.forEach { entry ->
                val sweep = (entry.seconds.toFloat() / totalSeconds * 360.0).toFloat()
                drawArc(
                    color = heartRateZoneColor(entry.zone),
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
            nonZero.forEach { entry -> HeartRateZoneLegendRow(entry, totalSeconds) }
        }
    }
}

@Composable
private fun HeartRateZoneLegendRow(entry: HeartRateZoneBreakdown, totalSeconds: Long) {
    val pct = (entry.seconds.toDouble() / totalSeconds * 100).roundToInt()
    val minutes = entry.seconds / 60
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(modifier = Modifier.size(10.dp).background(heartRateZoneColor(entry.zone), CircleShape))
        Text("${entry.zone.label} — ${minutes}min ($pct%)", style = FTType.BodySmall, color = FT.TextSecondary)
    }
}
