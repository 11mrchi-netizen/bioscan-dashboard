package com.bioscan.fieldterminal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.domain.HeartRateZone
import com.bioscan.fieldterminal.domain.HeartRateZoneBreakdown
import com.bioscan.fieldterminal.domain.ZoneBounds
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import kotlin.math.roundToInt

// Run session page: time in zones as horizontal bars (was a donut). Z0 (below zone 1) to Z5 on the left, each zone's
// bpm range written on its bar, the share of the time at the bar's end. Every zone has a row, so an empty one reads
// as 0 %, and the order is the effort order.
fun zoneShortLabel(zone: HeartRateZone): String = when (zone) {
    HeartRateZone.BelowZ1 -> "Z0"
    HeartRateZone.Z1 -> "Z1"
    HeartRateZone.Z2 -> "Z2"
    HeartRateZone.Z3 -> "Z3"
    HeartRateZone.Z4 -> "Z4"
    HeartRateZone.Z5 -> "Z5"
}

@Composable
fun HeartRateZoneBars(breakdown: List<HeartRateZoneBreakdown>, bounds: ZoneBounds, modifier: Modifier = Modifier) {
    val total = breakdown.sumOf { it.seconds }
    if (total <= 0) return
    val byZone = breakdown.associate { it.zone to it.seconds }
    val top = byZone.values.maxOrNull()?.coerceAtLeast(1) ?: 1
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HeartRateZone.entries.forEach { zone ->
            val secs = byZone[zone] ?: 0L
            val pct = (secs.toDouble() / total * 100).roundToInt()
            val shape = RoundedCornerShape(4.dp)
            Row(
                Modifier.fillMaxWidth().semantics { contentDescription = "${zoneShortLabel(zone)}, ${bounds.rangeText(zone)} bpm, $pct percent, ${secs / 60} minutes" },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(zoneShortLabel(zone), style = FTType.MonoCaption, color = FT.TextSecondary, modifier = Modifier.width(22.dp))
                Box(Modifier.weight(1f).height(22.dp), contentAlignment = Alignment.CenterStart) {
                    Box(Modifier.fillMaxWidth().height(22.dp).clip(shape).background(FT.GlassFill, shape))
                    Box(Modifier.fillMaxWidth((secs.toFloat() / top).coerceIn(0f, 1f)).height(22.dp).clip(shape).background(heartRateZoneColor(zone).copy(alpha = 0.75f), shape))
                    Text("${bounds.rangeText(zone)} bpm", style = FTType.Micro, color = FT.TextPrimary, modifier = Modifier.padding(start = 8.dp))
                }
                Text("$pct%", style = FTType.MonoCaption, color = FT.TextPrimary, modifier = Modifier.width(38.dp), textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
        }
    }
}
