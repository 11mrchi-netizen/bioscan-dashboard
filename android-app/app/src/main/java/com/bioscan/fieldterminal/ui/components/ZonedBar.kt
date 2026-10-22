package com.bioscan.fieldterminal.ui.components

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// The three zones of a nutrition bar (ZonedScale.kt) as track segments with a 2 dp gap between them: under in the plain
// track, the target range tinted with the nutrient's colour, over in a muted tint. Centred on y, barHeight tall.
fun DrawScope.drawZonedTrack(y: Float, barHeight: Float, edges: Pair<Float, Float>, targetColor: Color) {
    val gap = 2.dp.toPx()
    val r = CornerRadius(barHeight / 2f)
    fun seg(from: Float, to: Float, color: Color) {
        val x0 = from * size.width + if (from > 0f) gap / 2f else 0f
        val x1 = to * size.width - if (to < 1f) gap / 2f else 0f
        if (x1 > x0) drawRoundRect(color, Offset(x0, y - barHeight / 2f), Size(x1 - x0, barHeight), r)
    }
    seg(0f, edges.first, FT.GlassTrack)
    seg(edges.first, edges.second, targetColor.copy(alpha = 0.35f))
    seg(edges.second, 1f, FT.TextMuted.copy(alpha = 0.35f))
}

// The value on a zoned bar: a fill from the start in the nutrient's colour and a dot at its end.
fun DrawScope.drawZonedValue(y: Float, barHeight: Float, fraction: Float, color: Color, dotRadius: Float) {
    val x = fraction * size.width
    if (x > 0f) {
        drawRoundRect(color.copy(alpha = 0.55f), Offset(0f, y - barHeight / 2f), Size(x, barHeight), CornerRadius(barHeight / 2f))
    }
    drawCircle(FT.Surface, radius = dotRadius + 1.5.dp.toPx(), center = Offset(x.coerceIn(dotRadius, size.width - dotRadius), y))
    drawCircle(color, radius = dotRadius, center = Offset(x.coerceIn(dotRadius, size.width - dotRadius), y))
}
