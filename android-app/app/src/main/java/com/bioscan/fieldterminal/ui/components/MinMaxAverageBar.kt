package com.bioscan.fieldterminal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import com.bioscan.fieldterminal.ui.theme.RobotoMono

// Session Detail's PERFORMANCE card used to print Average/Min-Max as three
// plain StatLines -- this replaces them with one visual: a track spanning
// the observed [min, max] window with a marker at the average's real
// position in that window, labels underneath. Distinct from RangeBar.kt,
// which fills a single value against a fixed max rather than showing a
// floating min-max window with an internal marker.
@Composable
fun MinMaxAverageBar(
    min: Double,
    average: Double,
    max: Double,
    color: Color,
    format: (Double) -> String,
    modifier: Modifier = Modifier,
) {
    val range = (max - min).takeIf { it > 0 } ?: 1.0
    // Clamped away from the exact edges so both Spacer weights below always
    // stay positive -- a marker at the literal 0%/100% edge would need a
    // zero-weight Spacer, which Compose's Row doesn't accept.
    val fraction = ((average - min) / range).toFloat().coerceIn(0.02f, 0.98f)

    Column(modifier = modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(FT.GlassTrack)) {
            Box(Modifier.fillMaxWidth().height(8.dp).background(color.copy(alpha = 0.22f)))
            Row(Modifier.fillMaxWidth()) {
                Spacer(Modifier.weight(fraction))
                Box(Modifier.width(3.dp).height(8.dp).background(color))
                Spacer(Modifier.weight(1f - fraction))
            }
        }
        // Same fraction the marker above sits at, reused so the average
        // label roughly lines up underneath it rather than always sitting
        // dead center regardless of where the marker actually is.
        Row(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text(format(min), FT.TextMuted, Modifier)
            Spacer(Modifier.weight(fraction))
            Text(format(average), color, Modifier, bold = true)
            Spacer(Modifier.weight(1f - fraction))
            Text(format(max), FT.TextMuted, Modifier, alignEnd = true)
        }
    }
}

@Composable
private fun Text(value: String, color: Color, modifier: Modifier, bold: Boolean = false, alignEnd: Boolean = false) {
    androidx.compose.material3.Text(
        value,
        style = if (bold) FTType.Telemetry else FTType.MonoCaption,
        color = color,
        textAlign = if (alignEnd) androidx.compose.ui.text.style.TextAlign.End else androidx.compose.ui.text.style.TextAlign.Start,
        modifier = modifier,
    )
}
