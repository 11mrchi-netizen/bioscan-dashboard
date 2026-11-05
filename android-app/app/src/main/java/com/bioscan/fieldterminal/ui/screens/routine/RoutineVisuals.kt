package com.bioscan.fieldterminal.ui.screens.routine

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.domain.routine.ModeGroup
import com.bioscan.fieldterminal.domain.routine.PlaceCategory
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// The day map's and the commute's shared colours and marks. Modes are a categorical set, in stack order, checked with the
// dataviz validator against the dark surface (#11161A): lightness band, chroma, adjacent colour-blind separation (worst
// ΔE 8.4, protan) and contrast all pass. Waiting and unknown are neutral greys: they are "not moving" and "not known",
// not categories to tell apart by hue. A legend always sits beside the bars, so no mode is told by colour alone.
val ModeColors: Map<ModeGroup, Color> = mapOf(
    ModeGroup.ON_FOOT to Color(0xFF199E70),
    ModeGroup.BICYCLE to Color(0xFFC98500),
    ModeGroup.VEHICLE to Color(0xFF3987E5),
    ModeGroup.STOP to Color(0xFFD95926),
    ModeGroup.WAITING to Color(0xFF6B7178),
    ModeGroup.UNKNOWN to Color(0xFF3A3F45),
)

// A place's colour by category. Unlabelled places use the warning amber: they need a name, and always carry the text
// "Unlabelled" beside the colour.
fun placeColor(category: PlaceCategory?, labelled: Boolean): Color = when {
    category == PlaceCategory.HOME -> FT.Emerald
    category == PlaceCategory.WORK -> FT.DomainMap
    category != null || labelled -> FT.DomainLabs
    else -> FT.Warning
}

private val clockFmt = DateTimeFormatter.ofPattern("HH:mm")

fun clock(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String = Instant.ofEpochMilli(ms).atZone(zone).format(clockFmt)

fun clockOfMinute(minOfDay: Double): String {
    val m = minOfDay.toInt()
    return "%02d:%02d".format(m / 60, m % 60)
}

fun hm(minutes: Double): String {
    val m = Math.round(minutes).toInt()
    return if (m >= 60) "${m / 60} h ${m % 60} min" else "$m min"
}

// Minutes by mode as one horizontal stacked bar: 2 dp surface gaps between segments, rounded ends, widths in proportion
// to [scaleMin] so bars in one list compare (the longest commute fills the row).
@Composable
fun ModeBar(byMode: Map<ModeGroup, Double>, scaleMin: Double, modifier: Modifier = Modifier) {
    val total = byMode.values.sum()
    if (total <= 0 || scaleMin <= 0) return
    Row(modifier.fillMaxWidth().height(10.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight((total / scaleMin).toFloat().coerceIn(0.01f, 1f)).height(10.dp).clip(RoundedCornerShape(4.dp)),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            ModeGroup.entries.forEach { g ->
                val m = byMode[g] ?: 0.0
                if (m > 0) Box(Modifier.weight(m.toFloat()).height(10.dp).background(ModeColors.getValue(g)))
            }
        }
        val rest = (1.0 - total / scaleMin).toFloat()
        if (rest > 0.001f) Spacer(Modifier.weight(rest))
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModeLegend(groups: Collection<ModeGroup> = ModeGroup.entries) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        groups.forEach { g ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(ModeColors.getValue(g)))
                Text(g.label, style = FTType.Label, color = FT.TextSecondary)
            }
        }
    }
}

// "on foot 12 min · vehicle 15 min": the bar's numbers as text, in the bar's order.
fun modeLine(byMode: Map<ModeGroup, Double>): String =
    ModeGroup.entries.mapNotNull { g -> byMode[g]?.takeIf { it >= 0.5 }?.let { "${g.label.lowercase()} ${hm(it)}" } }.joinToString(" · ")

@Composable
fun Dot(color: Color) {
    Box(Modifier.width(10.dp).height(10.dp).clip(RoundedCornerShape(5.dp)).background(color))
}
