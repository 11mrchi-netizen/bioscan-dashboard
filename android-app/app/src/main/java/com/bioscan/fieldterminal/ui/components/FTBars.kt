package com.bioscan.fieldterminal.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.ui.theme.FTType
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// Bar graphs and diverging scales, as design/tokens/charts-bars-and-scales.md specifies them. Every bar chart in the
// app draws through these helpers so the geometry is one rule set:
//   - bars fill the plot width exactly: barWidth = (W - gap (n - 1)) / n, the gap always smaller than the bar;
//   - rounded tops only (radius = min(barWidth / 2, 4 dp)), square on the baseline;
//   - fill = a vertical gradient of the metric hue: a lighter tint at the top, the hue at 22 % at the baseline;
//   - the latest bar is full strength with a bright cap; when one is selected the others drop to 45 %;
//   - projected bars are the same shape at 40 %; empty slots are 2 dp baseline stubs; no data says so;
//   - at most one reference line: 1 dp dashed in the metric hue at 50 %; a goal band is the hue at 8 % behind the bars.
// Compose draws in real pixels, so the SVG stretch problem of §0 cannot happen here.

object FTBarSpec {
    const val BASE_ALPHA = 0.22f
    const val TOP_TINT = 0.10f
    const val DIMMED_ALPHA = 0.45f
    const val PROJECTED_ALPHA = 0.40f
    const val BAND_ALPHA = 0.08f
    const val REFERENCE_ALPHA = 0.5f
    val MaxRadius = 4.dp
    val MinBar = 3.dp
    val TopPad = 4.dp
    val Stub = 2.dp
    val StubColor = Color(0x0FFFFFFF)

    /** Intraday strip in a card. */
    val IntradayHeight = 66.dp
    /** Daily bars in a card. */
    val CardHeight = 84.dp
    /** A full-width detail chart. */
    val DetailHeight = 140.dp

    /** The gap for [n] bars (§1.4): 10 dp for a week, 5 dp for 24 hours, 2.5 dp for a month, 1.5 dp past 40. */
    fun gapFor(n: Int): Dp = when {
        n <= 7 -> 10.dp
        n <= 14 -> 7.dp
        n <= 24 -> 5.dp
        n <= 40 -> 2.5.dp
        else -> 1.5.dp
    }
}

/** Where bar [i] of [n] sits in a plot [width] px wide. */
class BarLayout(val width: Float, val n: Int, gapPx: Float) {
    val gap: Float
    val barWidth: Float

    init {
        val count = max(1, n)
        var g = gapPx
        var bw = (width - g * (count - 1)) / count
        // The gap is always smaller than the bar (§1.4).
        if (bw <= g) {
            g = width / count * 0.3f
            bw = (width - g * (count - 1)) / count
        }
        gap = if (count == 1) 0f else g
        barWidth = bw
    }

    fun left(i: Int): Float = i * (barWidth + gap)
    fun center(i: Int): Float = left(i) + barWidth / 2
    /** Which bar a touch at [x] is over. */
    fun indexAt(x: Float): Int = (x / (barWidth + gap)).toInt().coerceIn(0, max(0, n - 1))
}

fun DrawScope.barLayout(n: Int): BarLayout = BarLayout(size.width, n, FTBarSpec.gapFor(n).toPx())

/** The vertical fill of a bar in [color] between [top] and [bottom] (§1.3). */
fun barBrush(color: Color, top: Float, bottom: Float, alpha: Float = 1f): Brush = Brush.verticalGradient(
    0f to lerp(color, Color.White, FTBarSpec.TOP_TINT).copy(alpha = alpha),
    1f to color.copy(alpha = FTBarSpec.BASE_ALPHA * alpha),
    startY = top, endY = max(bottom, top + 1f),
)

/** A path for a bar with rounded top corners and a square bottom. */
fun roundedTopBar(left: Float, top: Float, width: Float, bottom: Float, radius: Float): Path {
    val r = min(radius, min(width / 2, (bottom - top).coerceAtLeast(0f)))
    return Path().apply {
        addRoundRect(RoundRect(left, top, left + width, bottom, topLeftCornerRadius = CornerRadius(r), topRightCornerRadius = CornerRadius(r),
            bottomRightCornerRadius = CornerRadius.Zero, bottomLeftCornerRadius = CornerRadius.Zero))
    }
}

/**
 * One magnitude bar from the baseline [bottom] up to [top]. [fill] overrides the gradient (a stacked segment); [roundTop]
 * is off for the lower segments of a stack.
 */
fun DrawScope.drawBar(
    color: Color, left: Float, top: Float, width: Float, bottom: Float = size.height,
    alpha: Float = 1f, roundTop: Boolean = true, gradient: Boolean = true, cap: Boolean = false,
) {
    val radius = if (roundTop) min(FTBarSpec.MaxRadius.toPx(), width / 2) else 0f
    val path = roundedTopBar(left, top, width, bottom, radius)
    if (gradient) drawPath(path, barBrush(color, top, bottom, alpha)) else drawPath(path, color.copy(alpha = alpha))
    // §1.8: the latest bar gets a 1 dp bright cap.
    if (cap && bottom - top > 2f) {
        val inset = radius * 0.6f
        drawLine(lerp(color, Color.White, 0.55f), Offset(left + inset, top + 0.5.dp.toPx()), Offset(left + width - inset, top + 0.5.dp.toPx()), strokeWidth = 1.dp.toPx())
    }
}

/**
 * §1.10 stacked bar: segments from the baseline up in their own [colors], 2 dp apart, the top-most one rounded.
 * [scale] turns a value into pixels. Returns the top y of the stack.
 */
fun DrawScope.drawStackedBar(
    values: List<Double>, colors: List<Color>, left: Float, width: Float, scale: (Double) -> Float,
    bottom: Float = size.height, alpha: Float = 1f, cap: Boolean = false,
): Float {
    val gap = 2.dp.toPx()
    val topPart = values.indexOfLast { it > 0 }
    var y = bottom
    values.forEachIndexed { k, v ->
        if (v <= 0) return@forEachIndexed
        val h = scale(v)
        if (h < 0.5f) return@forEachIndexed
        val drawn = if (k == topPart) h else (h - gap).coerceAtLeast(1f)
        drawBar(colors[k % colors.size], left, y - drawn, width, bottom = y, alpha = alpha, roundTop = k == topPart, gradient = false, cap = cap && k == topPart)
        y -= h
    }
    return y
}

/** §1.9: an empty slot keeps the time axis legible as a 2 dp stub on the baseline. */
fun DrawScope.drawBarStub(left: Float, width: Float, bottom: Float = size.height) {
    drawRect(FTBarSpec.StubColor, Offset(left, bottom - FTBarSpec.Stub.toPx()), Size(width, FTBarSpec.Stub.toPx()))
}

/** §1.7: the one reference line (target or average), dashed, in the metric hue at 50 %. */
fun DrawScope.drawReferenceLine(y: Float, color: Color) {
    drawLine(color.copy(alpha = FTBarSpec.REFERENCE_ALPHA), Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx(),
        pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx())))
}

/** §1.7: a goal band behind the bars, the metric hue at 8 %. */
fun DrawScope.drawGoalBand(yTop: Float, yBottom: Float, color: Color) {
    drawRect(color.copy(alpha = FTBarSpec.BAND_ALPHA), Offset(0f, min(yTop, yBottom)), Size(size.width, abs(yBottom - yTop)))
}

/** How strong bar [i] is drawn (§1.8): selected or latest full, the rest dimmed while one is selected. */
fun barAlpha(i: Int, selected: Int?, projected: Boolean = false): Float = when {
    projected -> FTBarSpec.PROJECTED_ALPHA
    selected == null || selected == i -> 1f
    else -> FTBarSpec.DIMMED_ALPHA
}

/** §1.1: the readout row under a bar plot, the current value in the metric hue and a muted context stat. */
@Composable
fun BarReadout(left: String?, right: String?, color: Color) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(left ?: "", style = FTType.Telemetry, color = color, modifier = Modifier.weight(1f))
        if (right != null) Text(right, style = FTType.MonoCaption, color = FT.TextSecondary, textAlign = TextAlign.End)
    }
}

/** §1.9: no data renders the baseline and says so, never zero-height bars. */
@Composable
fun FTBarsEmpty(height: Dp, modifier: Modifier = Modifier, text: String = "No data yet") {
    Box(modifier.fillMaxWidth().height(height).drawBehind {
        drawLine(FTBarSpec.StubColor, Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), strokeWidth = 2.dp.toPx())
    }, contentAlignment = Alignment.Center) {
        Text(text, style = FTType.MonoCaption, color = FT.TextMuted)
    }
}

// ---- §1.10: range (floating) bars ----

/** A capsule from [yHigh] to [yLow], the hue at 40 %, with a brighter 2 dp cap at [yMean] when given. */
fun DrawScope.drawRangeCapsule(color: Color, centerX: Float, width: Float, yHigh: Float, yLow: Float, yMean: Float? = null, alpha: Float = 1f) {
    val h = max(width, yLow - yHigh)
    val top = if (yLow - yHigh < width) (yHigh + yLow) / 2 - width / 2 else yHigh
    drawRoundRect(color.copy(alpha = 0.4f * alpha), Offset(centerX - width / 2, top), Size(width, h), CornerRadius(width / 2))
    yMean?.let { drawLine(lerp(color, Color.White, 0.3f).copy(alpha = alpha), Offset(centerX - width / 2, it), Offset(centerX + width / 2, it), strokeWidth = 2.dp.toPx()) }
}

// ---- §3: diverging scale ----

/** The poles of a diverging scale (§3.4), by meaning: the undesired side on the left, the desired on the right. */
data class DivergingPoles(val negative: Color, val positive: Color) {
    companion object {
        val EnergyBalance = DivergingPoles(FT.Domain.Load, FT.Domain.Cardio)
        val Form = DivergingPoles(FT.Bad, FT.Ok)
        val FluidBalance = DivergingPoles(FT.Warn, FT.Metric.Hydration)
        val Delta = DivergingPoles(FT.Bad, FT.Ok)
    }
}

private val DeadZone = Color(0xFF3A3F46)
private val NormalBand = Color(0xFF4A515C)

/** Where [value] sits on a symmetric [-d, +d] axis: 0 is the left end, 0.5 the centre, 1 the right end. */
fun divergingPosition(value: Double, d: Double): Float = ((value + d) / (2 * d)).toFloat().coerceIn(0f, 1f)

/**
 * A single bipolar value on a zero-centred axis (§3): label row (what it is, the signed value in its side's colour),
 * the diverging track with a neutral dead-zone (or a brighter [normalBand]), a white thumb with a dark ring, and
 * symmetric axis labels. Zero is always the centre. Past ±[d] the thumb pins to the end with a chevron and the label keeps
 * the true value. [lowConfidence] draws the thumb hollow; null [value] greys the track. [mini] is the 6 dp row variant.
 */
@Composable
fun FTDivergingScale(
    value: Double?,
    d: Double,
    poles: DivergingPoles,
    modifier: Modifier = Modifier,
    label: String? = null,
    valueText: String? = null,
    deadZone: Double = d * 0.08,
    normalBand: Pair<Double, Double>? = null,
    negativeWord: String? = null,
    positiveWord: String? = null,
    format: (Double) -> String = { v -> (if (v > 0) "+" else if (v < 0) "−" else "") + abs(v).let { if (it >= 10) it.toInt().toString() else "%.1f".format(it).removeSuffix(".0") } },
    lowConfidence: Boolean = false,
    mini: Boolean = false,
    description: String = "",
) {
    val side = when {
        value == null -> null
        abs(value) <= deadZone -> 0
        normalBand != null && value >= minOf(normalBand.first, normalBand.second) && value <= maxOf(normalBand.first, normalBand.second) -> 0
        value > 0 -> 1
        else -> -1
    }
    val valueColor = when (side) {
        1 -> poles.positive
        -1 -> poles.negative
        else -> FT.TextPrimary
    }
    Column(modifier.semantics { contentDescription = description }, verticalArrangement = Arrangement.spacedBy(if (mini) 4.dp else 8.dp)) {
        if (!mini && (label != null || valueText != null)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label ?: "", style = FTType.LabelCaps, color = FT.TextSecondary, modifier = Modifier.weight(1f))
                Text(if (value == null) "No data" else valueText ?: format(value), style = FTType.Telemetry, color = if (value == null) FT.TextMuted else valueColor)
            }
        }
        val trackH = if (mini) 6.dp else 8.dp
        val thumb = if (mini) 12.dp else 16.dp
        Canvas(Modifier.fillMaxWidth().height(thumb + 6.dp)) {
            val cy = size.height / 2
            val th = trackH.toPx()
            val r = thumb.toPx() / 2
            val inset = r + 3.dp.toPx()
            val w = size.width - 2 * inset
            fun x(v: Double) = inset + w * divergingPosition(v, d)
            val track = if (value == null) Brush.horizontalGradient(listOf(Color(0x1FFFFFFF), Color(0x1FFFFFFF)), startX = inset, endX = inset + w)
            else {
                val dz = (deadZone / (2 * d)).toFloat().coerceIn(0f, 0.2f)
                Brush.horizontalGradient(
                    0f to poles.negative, (0.5f - dz) to DeadZone, (0.5f + dz) to DeadZone, 1f to poles.positive,
                    startX = inset, endX = inset + w,
                )
            }
            drawRoundRect(track, Offset(inset, cy - th / 2), Size(w, th), CornerRadius(th / 2))
            if (value != null && normalBand != null) {
                val a = x(normalBand.first)
                val b = x(normalBand.second)
                drawRoundRect(NormalBand, Offset(min(a, b), cy - th / 2), Size(abs(b - a), th), CornerRadius(th / 2))
            }
            if (!mini) drawLine(FT.Dim, Offset(x(0.0), cy - th), Offset(x(0.0), cy + th), strokeWidth = 1.dp.toPx())
            if (value != null) {
                val at = Offset(x(value), cy)
                // shadow 0 2px 8px rgba(0,0,0,.5), then the 3 dp ground-coloured ring, then the thumb
                drawCircle(Brush.radialGradient(listOf(Color.Black.copy(alpha = 0.5f), Color.Transparent), at + Offset(0f, 2.dp.toPx()), r + 6.dp.toPx()), r + 6.dp.toPx(), at + Offset(0f, 2.dp.toPx()))
                drawCircle(FT.Base, r + 3.dp.toPx(), at)
                if (lowConfidence) drawCircle(Color.White, r - 1.dp.toPx(), at, style = Stroke(2.dp.toPx())) else drawCircle(Color.White, r, at)
                if (abs(value) > d) {
                    val dir = if (value > 0) 1f else -1f
                    val tip = at.x + dir * (r + 5.dp.toPx())
                    drawLine(FT.TextPrimary, Offset(tip, cy - 4.dp.toPx()), Offset(tip + dir * 3.dp.toPx(), cy), strokeWidth = 1.5.dp.toPx())
                    drawLine(FT.TextPrimary, Offset(tip + dir * 3.dp.toPx(), cy), Offset(tip, cy + 4.dp.toPx()), strokeWidth = 1.5.dp.toPx())
                }
            }
        }
        if (!mini) {
            Row(Modifier.fillMaxWidth()) {
                for ((i, v) in listOf(-d, -d / 2, 0.0, d / 2, d).withIndex()) {
                    Text(
                        if (v == 0.0) "0" else format(v),
                        style = FTType.Micro, color = FT.Dim, modifier = Modifier.weight(1f),
                        textAlign = when (i) { 0 -> TextAlign.Start; 4 -> TextAlign.End; else -> TextAlign.Center },
                    )
                }
            }
            if (negativeWord != null || positiveWord != null) {
                Row(Modifier.fillMaxWidth()) {
                    Text("◀ ${negativeWord.orEmpty()}", style = FTType.Micro, color = FT.TextMuted, modifier = Modifier.weight(1f))
                    Text("${positiveWord.orEmpty()} ▶", style = FTType.Micro, color = FT.TextMuted, textAlign = TextAlign.End)
                }
            }
        }
    }
}

/**
 * A bar chart's y-axis: 0, half and the top of the scale, each at its height (the top under the bars' top pad), in a
 * gutter of [width] left of the bars. Three labelled values, as the chart rules ask.
 */
@androidx.compose.runtime.Composable
fun BarYAxis(top: Double, format: (Double) -> String, height: Dp, width: Dp = 40.dp) {
    BoxWithConstraints(Modifier.width(width).height(height)) {
        val usable = maxHeight - FTBarSpec.TopPad
        for (f in listOf(1.0, 0.5, 0.0)) {
            val y = (FTBarSpec.TopPad + usable * (1 - f).toFloat() - 7.dp).coerceIn(0.dp, maxHeight - 12.dp)
            Text(
                format(top * f), style = FTType.Micro, color = FT.TextMuted,
                modifier = Modifier.padding(top = y),
            )
        }
    }
}
