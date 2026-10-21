package com.bioscan.fieldterminal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// App restructure, fix 4 (contract amendment A9): a movement is tinted by its direction -- a faint green fill when it
// supports recovery (or readiness), a faint red fill when it holds it back, no tint when neutral. The arrow and the word
// always say it too, so colour is never the only signal.
@Composable
fun FTMovementChip(direction: Int?, word: String, modifier: Modifier = Modifier) {
    val tint = when {
        direction == null -> null
        direction > 0 -> FT.Emerald
        direction < 0 -> FT.Critical
        else -> null
    }
    val arrow = when {
        direction == null -> "—"
        direction > 0 -> "↑"
        direction < 0 -> "↓"
        else -> "="
    }
    val shape = RoundedCornerShape(FT.RadiusSmall)
    Text(
        "$arrow $word",
        style = FTType.Label,
        color = FT.TextPrimary,
        modifier = modifier
            .semantics { contentDescription = word.lowercase() }
            .background(tint?.copy(alpha = 0.16f) ?: FT.GlassFill, shape)
            .border(FT.BorderWidth, tint?.copy(alpha = 0.45f) ?: FT.GlassBorder, shape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    )
}
