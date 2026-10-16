package com.bioscan.fieldterminal.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// Contract A1 (design/FUTURISTIC_MATERIAL_DESIGN_CONTRACT.md, "Interaction states"): the one tap modifier.
//
// `indication = null` everywhere used to mean no feedback at all (the review counted 81 such taps). This keeps the
// mechanical, no-ripple look but makes every tap visible: a strong glass tint drawn the instant a finger lands and faded
// out over 120 ms on release, and a 2dp emerald-300 ring while the element has keyboard / switch-access focus. Both are
// drawn in the element's own shape. Place it where `.clickable(...)` was: after the element's own background/border, before
// its inner padding, so the tint sits over the background and under the content.
@Composable
fun Modifier.ftClickable(
    enabled: Boolean = true,
    shape: Shape = RectangleShape,
    role: Role? = null,
    onClickLabel: String? = null,
    onClick: () -> Unit,
): Modifier {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val focused by interactionSource.collectIsFocusedAsState()
    val pressAlpha by animateFloatAsState(
        targetValue = if (pressed && enabled) 1f else 0f,
        animationSpec = if (pressed) snap() else tween(durationMillis = 120, easing = LinearEasing),
        label = "ftPress",
    )
    return this
        .drawBehind {
            if (pressAlpha > 0f) {
                drawOutline(shape.createOutline(size, layoutDirection, this), FT.GlassStrongFill.copy(alpha = FT.GlassStrongFill.alpha * pressAlpha))
            }
        }
        .border(2.dp, if (focused && enabled) FT.EmeraldHighlight.copy(alpha = 0.7f) else Color.Transparent, shape)
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            onClickLabel = onClickLabel,
            role = role,
            onClick = onClick,
        )
}
