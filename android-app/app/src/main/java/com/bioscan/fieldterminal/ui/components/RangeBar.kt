package com.bioscan.fieldterminal.ui.components

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// A generic fill bar -- deliberately NOT a "percent of personal target" bar,
// and never given an invented ceiling: `max` must be a real bound of the
// value (a percentile's 100, a block's total days), not a guessed "typical"
// number. Renders through FTBar so every bar in the app looks the same;
// `showThumb` (default on) adds the round thumb at the fill's end.
@Composable
fun RangeBar(
    value: Double,
    max: Double,
    watchBelow: Double?,
    color: Color,
    height: Dp = 8.dp,
    topPadding: Dp = 8.dp,
    showThumb: Boolean = true,
) {
    val fraction = (value / max).coerceIn(0.0, 1.0).toFloat()
    val fillColor = if (watchBelow != null && value < watchBelow) FT.Critical else color
    FTBar(
        fraction = fraction,
        color = fillColor,
        modifier = Modifier.padding(top = topPadding), // true top margin -- applied outside the track's own size
        height = height,
        showThumb = showThumb,
    )
}
