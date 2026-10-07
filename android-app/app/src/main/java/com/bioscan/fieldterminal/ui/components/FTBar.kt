package com.bioscan.fieldterminal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// The one fill bar: fully rounded, GlassTrack track, one default height.
// progress / share / target-vs-actual bars all render through this so a bar
// looks the same on every screen. `fraction` is already normalised 0..1 by
// the caller -- this never decides what a value means.
@Composable
fun FTBar(
    fraction: Float,
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 8.dp,
) {
    val shape = RoundedCornerShape(height / 2)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(shape)
            .background(FT.GlassTrack, shape),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(height)
                .background(color),
        )
    }
}
