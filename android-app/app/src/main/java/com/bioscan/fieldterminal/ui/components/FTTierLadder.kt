package com.bioscan.fieldterminal.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// Position on a four-step ladder (the strength tiers): a rounded track in four equal segments with a divider at
// each tier threshold, a fill up to `fraction`, and a thumb. `tierLabels` are the names of the four thresholds
// (e.g. NOVICE..ELITE), printed under the right-hand edge of their segment; `reachedCount` highlights the tiers
// already passed. `fraction` is the ladder fraction the domain computed -- this never decides what a value means.
@Composable
fun FTTierLadder(
    fraction: Float,
    tierLabels: List<String>,
    reachedCount: Int,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val f = fraction.coerceIn(0f, 1f)
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(modifier = Modifier.fillMaxWidth().height(14.dp)) {
            val barH = 8.dp.toPx()
            val y = (size.height - barH) / 2f
            val corner = CornerRadius(barH / 2f)
            drawRoundRect(FT.GlassTrack, Offset(0f, y), Size(size.width, barH), corner)
            val fillW = f * size.width
            if (fillW > 0f) drawRoundRect(color, Offset(0f, y), Size(fillW, barH), corner)
            // Tier thresholds at 25/50/75% (the 100% end is the track's end).
            for (i in 1..3) {
                val x = size.width * i / 4f
                drawLine(FT.Base, Offset(x, y), Offset(x, y + barH), strokeWidth = 2.dp.toPx())
            }
            if (fillW > 0f) {
                val r = barH * 0.85f
                val cx = fillW.coerceIn(r, size.width - r)
                drawCircle(FT.Surface, r + 1.dp.toPx(), Offset(cx, size.height / 2f))
                drawCircle(color, r, Offset(cx, size.height / 2f))
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            tierLabels.forEachIndexed { i, label ->
                Text(
                    label.uppercase(),
                    style = FTType.Micro,
                    color = if (i < reachedCount) FT.TextSecondary else FT.TextMuted,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
