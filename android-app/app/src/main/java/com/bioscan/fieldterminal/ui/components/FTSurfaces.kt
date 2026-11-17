package com.bioscan.fieldterminal.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// v1 "new look" surfaces (design/tokens/backgrounds.css + components.css):
//   background = domain: ftDomainWash, a soft wash of the domain hue behind the page;
//   surface = neutral:   ftGlass, faint-platinum frosted glass, never tinted;
//   ink = metric:        chart and value colours come from FT.Metric.
// Backdrop blur is not drawn: Compose cannot blur what is behind a surface, and over the near-black ground the glass
// gradient and the hairline carry the look on their own.

/** `.ft-card` / `.ft-tile`: the neutral glass gradient, the 1 dp hairline and the inset top highlight. */
fun Modifier.ftGlass(shape: Shape, strong: Boolean = false, highlight: Boolean = true): Modifier =
    this
        .background(if (strong) StrongGlass else FT.Glass, shape)
        .border(FT.BorderWidth, FT.GlassBorder, shape)
        .then(if (highlight) Modifier.drawWithContent { drawContent(); drawTopHighlight(shape) } else Modifier)

private val StrongGlass = Brush.linearGradient(0f to Color(0x24FFFFFF), 0.6f to Color(0x0DFFFFFF), 1f to Color(0x0DFFFFFF))

// inset 0 1px 0 rgba(255,255,255,.15): a hairline just inside the top edge, between the corners.
private fun DrawScope.drawTopHighlight(shape: Shape) {
    val r = if (shape is RoundedCornerShape) shape.topStart.toPx(size, this) else 0f
    val y = 1.dp.toPx() + 0.5f
    if (size.width > 2 * r) drawLine(FT.GlassHighlight, Offset(r, y), Offset(size.width - r, y), strokeWidth = 1.dp.toPx())
}

/**
 * `.ft-wash`: three elliptical radial glows of the domain hue (top centre 34 %, right 17 %, bottom left 20 %) behind the
 * page. Put it on the page's root, under the content. Sizes are the reference's px read as dp.
 */
fun Modifier.ftDomainWash(hue: Color): Modifier = drawBehind {
    glow(hue, 0.34f, Offset(size.width * 0.5f, size.height * -0.12f), 540.dp.toPx(), 440.dp.toPx(), 0.64f)
    glow(hue, 0.17f, Offset(size.width * 1.12f, size.height * 0.42f), 440.dp.toPx(), 400.dp.toPx(), 0.62f)
    glow(hue, 0.20f, Offset(size.width * -0.12f, size.height * 0.94f), 480.dp.toPx(), 440.dp.toPx(), 0.64f)
}

/**
 * The neutral page wash for screens with no domain hue: a soft platinum glow top centre and a fainter one bottom-left,
 * so a page that is not colour-themed reads as part of the system without borrowing any metric's colour. Kept low-alpha
 * because platinum is near-white; put it on the page's root, over [FT.Base] and under the content, like [ftDomainWash].
 */
fun Modifier.ftPlatinumWash(): Modifier = drawBehind {
    glow(FT.PlatinumSolid, 0.10f, Offset(size.width * 0.5f, size.height * -0.12f), 540.dp.toPx(), 440.dp.toPx(), 0.64f)
    glow(FT.PlatinumSolid, 0.05f, Offset(size.width * -0.12f, size.height * 0.96f), 480.dp.toPx(), 440.dp.toPx(), 0.64f)
}

/** The CHARGE page's wash (gauge.html): deep teal from the top, violet from the right. */
fun Modifier.ftChargeWash(): Modifier = drawBehind {
    glow(Color(0xFF2D7882), 0.28f, Offset(size.width * 0.5f, size.height * -0.10f), 640.dp.toPx(), 440.dp.toPx(), 0.62f)
    glow(Color(0xFF6D5EFC), 0.16f, Offset(size.width * 1.08f, size.height * 0.60f), 520.dp.toPx(), 440.dp.toPx(), 0.58f)
}

// radial-gradient(<rx> <ry> at <center>, hue@alpha, transparent <end>): a circle of radius rx squashed to ry.
private fun DrawScope.glow(hue: Color, alpha: Float, center: Offset, rx: Float, ry: Float, end: Float) {
    scale(scaleX = 1f, scaleY = ry / rx, pivot = center) {
        drawCircle(
            Brush.radialGradient(0f to hue.copy(alpha = alpha), end to Color.Transparent, center = center, radius = rx),
            radius = rx, center = center,
        )
    }
}

/** `.ft-row__dot`: a metric's dot with its glow. */
@Composable
fun FTMetricDot(color: Color, modifier: Modifier = Modifier, size: Dp = 9.dp) {
    Box(
        modifier
            .size(size)
            .drawBehind { drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.55f), Color.Transparent)), radius = this.size.minDimension) }
            .background(color, CircleShape),
    )
}

/** `.ft-switch`: off = faint glass, on = platinum with the knob to the right. */
@Composable
fun FTSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier = modifier
            .size(width = 48.dp, height = 27.dp)
            .then(if (checked) Modifier.background(FT.Platinum, shape) else Modifier.background(Color(0x0FFFFFFF), shape).border(FT.BorderWidth, FT.Line, shape))
            .then(
                if (onCheckedChange != null) Modifier.ftClickable(enabled = enabled, shape = shape, role = Role.Switch) { onCheckedChange(!checked) }
                else Modifier,
            )
            .semantics { role = Role.Switch; stateDescription = if (checked) "On" else "Off" }
            .padding(3.dp),
    ) {
        Box(
            Modifier
                .padding(start = if (checked) 21.dp else 0.dp)
                .size(21.dp)
                .background(if (enabled) Color.White else FT.TextMuted, CircleShape),
        )
    }
}
