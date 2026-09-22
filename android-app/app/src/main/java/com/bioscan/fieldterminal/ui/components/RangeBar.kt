package com.bioscan.fieldterminal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// A generic sanity-range bar -- deliberately NOT a "percent of personal
// target" bar. First built for Step 6 (Nutrition/Hydration) once it became
// clear no daily targets exist anywhere in this project's real data; reused
// as-is for Step 7 rather than re-solving the same problem per screen.
@Composable
fun RangeBar(
    value: Double,
    max: Double,
    watchBelow: Double?,
    color: Color,
    height: Dp = 8.dp,
    topPadding: Dp = 8.dp,
) {
    val fraction = (value / max).coerceIn(0.0, 1.0).toFloat()
    val fillColor = if (watchBelow != null && value < watchBelow) FT.Critical else color
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topPadding) // true top margin -- applied outside the track's own size
            .height(height)
            .background(FT.GlassFill),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
                .height(height)
                .background(fillColor),
        )
    }
}
