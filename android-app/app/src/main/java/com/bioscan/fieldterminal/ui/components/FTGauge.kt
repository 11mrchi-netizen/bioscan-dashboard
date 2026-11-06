package com.bioscan.fieldterminal.ui.components

import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.domain.score.DomainKey
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

// The v1 radial gauge (design/tokens/gauge.html, `ftGauge`): a ring of N ticks, the lit ones coloured along a gradient
// from the first stop to the last, a glowing cap on the last lit tick, and a blurred radar sweep inside the ring that
// brightens towards the value. Geometry is in the 0..100 viewBox of the reference: ticks run from radius 36 to 45.
// A value past one lap (a score above 100) keeps the outer lap fully lit and draws the rest as a second lap of ticks
// just inside it (radius 28 to 34), from 12 o'clock again, brighter; the cap sits at the end of the second lap.

/** The colours of one gauge: the sweep's stops and the radar glow's hue. */
data class GaugePalette(val stops: List<Color>, val radar: Color) {
    companion object {
        /** CHARGE: the holographic spectrum. */
        val Charge = GaugePalette(FT.Domain.Charge, FT.Domain.ChargeRadar)
        fun of(key: DomainKey) = GaugePalette(FT.Domain.stops(key), FT.Domain.of(key))
        /** A gauge that is not a score: one metric hue, from a lighter step to the hue. */
        fun metric(color: Color) = GaugePalette(listOf(lerp(color, Color.White, 0.35f), color), color)
    }
}

/** The colour at [t] (0..1) along [stops] (`ftGrad`). */
fun gradientAt(stops: List<Color>, t: Float): Color {
    if (stops.size == 1) return stops[0]
    val c = t.coerceIn(0f, 1f)
    val seg = (stops.size - 1) * c
    val i = min(stops.size - 2, seg.toInt())
    return lerp(stops[i], stops[i + 1], seg - i)
}

/** How many of [n] ticks a fraction [pct] lights: at least one, so an empty gauge still shows where it starts. */
fun litTicks(pct: Float, n: Int): Int = max(1, (pct.coerceIn(0f, 1f) * n).roundToInt())

/**
 * The gauge. [pct] is the share of one lap: 0..1 fills the ring, above 1 (up to 2) draws a second lap inside it; null
 * draws the bare track (no reading). [dim] fades the lit ticks for a
 * reading that is provisional or partial. [ticks] defaults by size: 96 on a hero gauge, 56 on a ring, 40 on a small one.
 */
@Composable
fun FTGauge(
    pct: Float?,
    palette: GaugePalette,
    size: Dp,
    modifier: Modifier = Modifier,
    ticks: Int = when {
        size >= 140.dp -> 96
        size >= 72.dp -> 56
        else -> 40
    },
    dim: Boolean = false,
    radar: Boolean = true,
    center: @Composable BoxScope.() -> Unit = {},
) {
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        if (pct != null && radar) RadarGlow(pct.coerceIn(0f, 1f), palette.radar, size, inset = if (pct > 1f) 0.22f else 0.15f)
        Canvas(Modifier.fillMaxSize()) {
            val unit = this.size.minDimension / 100f
            val c = Offset(this.size.width / 2, this.size.height / 2)
            val r1 = 36f * unit
            val r2 = 45f * unit
            val stroke = max(1.2f, min(1.7f.dp.toPx(), 1.7f * unit))
            val first = pct?.coerceIn(0f, 1f)
            val second = pct?.let { (it - 1f).coerceIn(0f, 1f) } ?: 0f
            val on = first?.let { litTicks(it, ticks) } ?: 0
            val onSecond = if (second > 0f) litTicks(second, ticks) else 0
            val alpha = if (dim) 0.55f else 1f
            fun ring(lit: Int, inner: Float, outer: Float, off: Color?, brighten: Float) {
                for (i in 0 until ticks) {
                    val a = Math.toRadians(i.toDouble() / ticks * 360.0)
                    val sx = sin(a).toFloat()
                    val cy = -cos(a).toFloat()
                    val color = if (i < lit) lerp(gradientAt(palette.stops, if (lit > 1) i / (lit - 1f) else 1f), Color.White, brighten).copy(alpha = alpha)
                    else off ?: continue
                    drawLine(color, Offset(c.x + outer * sx, c.y + outer * cy), Offset(c.x + inner * sx, c.y + inner * cy), strokeWidth = stroke, cap = StrokeCap.Round)
                }
            }
            ring(on, r1, r2, OffTick, 0f)
            val r3 = 28f * unit
            val r4 = 34f * unit
            if (onSecond > 0) ring(onSecond, r3, r4, null, 0.3f)
            val (capTicks, capRadius) = if (onSecond > 0) onSecond to (r3 + r4) / 2 else on to (r1 + r2) / 2
            if (capTicks > 0) {
                val ea = Math.toRadians((capTicks - 0.5) / ticks * 360.0)
                val capAt = Offset(c.x + capRadius * sin(ea).toFloat(), c.y - capRadius * cos(ea).toFloat())
                val capR = 2.6f * unit
                // drop-shadow(0 0 4px rgba(255,255,255,.7)) as a soft halo under the cap
                drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.55f), Color.Transparent), capAt, capR + 4.dp.toPx()), capR + 4.dp.toPx(), capAt)
                drawCircle(lerp(gradientAt(palette.stops, 1f), Color.White, if (onSecond > 0) 0.3f else 0f).copy(alpha = alpha), capR, capAt)
            }
        }
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center, content = center)
    }
}

private val OffTick = Color.White.copy(alpha = 0.08f)

// The radar: a conic sweep from 12 o'clock to the value, brightening towards it, masked to a soft disc and blurred.
@Composable
private fun RadarGlow(pct: Float, hue: Color, size: Dp, inset: Float) {
    val sweep = pct * 360f
    val f = { deg: Float -> (deg / 360f).coerceIn(0f, 1f) }
    val stops = arrayOf(
        0f to hue.copy(alpha = 0.03f),
        f(sweep * 0.28f) to hue.copy(alpha = 0.08f),
        f(sweep * 0.72f) to hue.copy(alpha = 0.28f),
        f(sweep) to hue.copy(alpha = 0.7f),
        f(sweep + 1.6f) to hue.copy(alpha = 0f),
        1f to hue.copy(alpha = 0f),
    )
    val blurred = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Modifier.blur(size * 0.08f) else Modifier
    Canvas(
        Modifier
            .fillMaxSize()
            .padding(size * inset)
            .then(blurred)
            .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
            .rotate(-90f),
    ) {
        val c = Offset(this.size.width / 2, this.size.height / 2)
        val r = this.size.minDimension / 2
        drawCircle(Brush.sweepGradient(*stops, center = c), r, c)
        // mask: radial-gradient(circle, #000 0 52%, transparent 86%)
        drawCircle(
            Brush.radialGradient(0f to Color.Black, 0.52f to Color.Black, 0.86f to Color.Transparent, center = c, radius = r),
            r, c, blendMode = BlendMode.DstIn,
        )
    }
}
