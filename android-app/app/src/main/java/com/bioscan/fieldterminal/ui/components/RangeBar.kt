package com.bioscan.fieldterminal.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

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
    val radius = 100f

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = topPadding)
            .height(if (showThumb) height + 4.dp else height),
    ) {
        val barH = height.toPx()
        val yOffset = (size.height - barH) / 2f

        // track
        drawRoundRect(
            color = FT.GlassFill,
            topLeft = Offset(0f, yOffset),
            size = Size(size.width, barH),
            cornerRadius = CornerRadius(radius),
        )

        // fill
        val fillW = fraction * size.width
        if (fillW > 0f) {
            drawRoundRect(
                color = fillColor,
                topLeft = Offset(0f, yOffset),
                size = Size(fillW, barH),
                cornerRadius = CornerRadius(radius),
            )
        }

        // thumb circle
        if (showThumb && fillW > 0f) {
            val thumbR = barH * 0.85f
            drawCircle(
                color = fillColor,
                radius = thumbR,
                center = Offset(fillW.coerceIn(thumbR, size.width - thumbR), size.height / 2f),
            )
        }
    }
}
