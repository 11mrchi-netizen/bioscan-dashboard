package com.bioscan.fieldterminal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import kotlin.math.roundToInt

data class FTSegment(val label: String, val weight: Double, val color: Color)

// One stacked proportion bar for composition ("where does it go") data:
// sleep phases, stress levels, stool pattern. Segments are drawn at their
// share of the total; the optional legend prints label + percent next to a
// color dot, so the bar never relies on color alone. Use a single hue in
// lightness/opacity steps for ordinal parts (see Category tokens).
@Composable
fun FTSegmentBar(
    segments: List<FTSegment>,
    modifier: Modifier = Modifier,
    height: Dp = 10.dp,
    showLegend: Boolean = true,
) {
    val total = segments.sumOf { it.weight }
    if (total <= 0.0) return
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(modifier = Modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(height / 2))) {
            segments.filter { it.weight > 0.0 }.forEach { seg ->
                Box(Modifier.weight((seg.weight / total).toFloat()).fillMaxHeight().background(seg.color))
            }
        }
        if (showLegend) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                segments.forEach { seg ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(seg.color))
                        Text("${seg.label} ${(seg.weight / total * 100).roundToInt()}%", style = FTType.Label, color = FT.TextSecondary)
                    }
                }
            }
        }
    }
}
