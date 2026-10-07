package com.bioscan.fieldterminal.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// The one fill bar: fully rounded, GlassTrack track, one default height, and
// an optional thumb at the fill's end (the rounded-bar-with-thumb look the
// rest of the app's range bars use). progress / share / target-vs-actual bars
// all render through this so a bar looks the same on every screen. `fraction`
// is already normalised 0..1 by the caller -- this never decides what a value
// means.
@Composable
fun FTBar(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 8.dp,
    showThumb: Boolean = false,
) {
    val f = fraction.coerceIn(0f, 1f)
    Canvas(modifier = modifier.fillMaxWidth().height(if (showThumb) height + 4.dp else height)) {
        val barH = height.toPx()
        val y = (size.height - barH) / 2f
        val corner = CornerRadius(barH / 2f)
        drawRoundRect(color = FT.GlassTrack, topLeft = Offset(0f, y), size = Size(size.width, barH), cornerRadius = corner)
        val fillW = f * size.width
        if (fillW > 0f) {
            drawRoundRect(color = color, topLeft = Offset(0f, y), size = Size(fillW, barH), cornerRadius = corner)
        }
        if (showThumb && fillW > 0f) {
            val thumbR = barH * 0.85f
            drawCircle(color = color, radius = thumbR, center = Offset(fillW.coerceIn(thumbR, size.width - thumbR), size.height / 2f))
        }
    }
}
