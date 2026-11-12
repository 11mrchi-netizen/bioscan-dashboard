package com.bioscan.fieldterminal.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// A page-shaped placeholder while the first copy of a page loads (later opens come from the read cache): grey blocks
// the size of a section's title, number and chart, breathing gently. One announcement for screen readers.

@Composable
fun SkeletonSections(count: Int = 3) {
    // With reduced motion the placeholder holds still at its middle value.
    val pulse = if (FTMotion.reduced) 0.65f else rememberInfiniteTransition(label = "skeleton").animateFloat(
        initialValue = 0.45f, targetValue = 0.9f,
        animationSpec = infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "pulse",
    ).value
    Column(Modifier.fillMaxWidth().semantics { contentDescription = "Loading" }, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        repeat(count) { i ->
            val shape = RoundedCornerShape(FT.RadiusModule)
            Column(
                Modifier.fillMaxWidth().background(FT.Glass, shape).border(FT.BorderWidth, FT.GlassBorder, shape).padding(14.dp).alpha(pulse),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Bar(0.35f, 10.dp)
                Bar(if (i % 2 == 0) 0.5f else 0.3f, 22.dp)
                Bar(1f, if (i == 0) 120.dp else 70.dp)
            }
        }
    }
}

@Composable
private fun Bar(width: Float, height: Dp) {
    androidx.compose.foundation.layout.Box(
        Modifier.fillMaxWidth(width).height(height).background(FT.GlassTrack, RoundedCornerShape(6.dp)),
    )
}
