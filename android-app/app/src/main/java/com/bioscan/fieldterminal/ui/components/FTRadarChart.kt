package com.bioscan.fieldterminal.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

// One spoke of the radar. `value` is already a 0..100 radius supplied upstream; null means the axis could
// not be assessed -- it is drawn as a dashed spoke with no marker and a "—" label, and left OUT of the polygon (never
// plotted at zero, which would read as a bad result instead of a missing one). `valueLabel`, when set,
// replaces the number in the axis label (for axes whose radius is a position, not a score).
// `state`, when set, also sets the vertex's shape (filled dot = optimal, ring = neutral, triangle = attention), so the
// verdict never rests on colour alone; the list beside the chart shows the same state as a pill.
data class RadarAxis(
    val label: String,
    val value: Double?,
    val color: Color,
    val valueLabel: String? = null,
    val state: com.bioscan.fieldterminal.domain.MetricState? = null,
    val description: String? = null,
)

/** A ring drawn heavier and labelled with its value (the Healthspan 25 and 50 lines). */
data class RadarRing(val value: Double, val label: String)

// N-gon radar (the User tab's Healthspan factors). Rings at 25/50/75/100, an accent-filled polygon through
// the scored axes, a coloured vertex per axis, tap an axis to select it. Generic in N; knows nothing about
// Healthspan. Labels are drawn on the canvas with FTType so they stay inside the design scale.
@Composable
fun FTRadarChart(
    axes: List<RadarAxis>,
    modifier: Modifier = Modifier,
    selectedIndex: Int? = null,
    onSelect: (Int) -> Unit = {},
    accent: Color = FT.DomainUser,
    height: Dp = 280.dp,
    markedRings: List<RadarRing> = emptyList(),
) {
    val measurer = rememberTextMeasurer()
    val n = axes.size
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .semantics {
                contentDescription = axes.joinToString("; ") { a -> a.description ?: "${a.label} ${a.valueLabel ?: a.value?.let { "%.0f".format(it) } ?: "not assessed"}" }
            }
            .pointerInput(n) {
                if (n < 3) return@pointerInput
                detectTapGestures { tap ->
                    val c = Offset(size.width / 2f, size.height / 2f)
                    // Angle from the top, clockwise, back to the nearest spoke.
                    val a = atan2((tap.x - c.x).toDouble(), (c.y - tap.y).toDouble())
                    val turn = if (a < 0) a + 2 * PI else a
                    onSelect(((turn / (2 * PI) * n) + 0.5).toInt() % n)
                }
            },
    ) {
        if (n < 3) return@Canvas
        // The radius leaves room for the widest and tallest label (measured in the larger, selected style) beside the
        // spokes, so no label has to be pushed back over its vertex to stay on the canvas.
        val labels = axes.map { measurer.measure(labelText(it), FTType.CaptionStrong.copy(textAlign = TextAlign.Center)) }
        val labelPad = 10.dp.toPx()
        val radius = minOf(
            size.width / 2f - labels.maxOf { it.size.width } - labelPad - 2.dp.toPx(),
            size.height / 2f - labels.maxOf { it.size.height } - labelPad - 2.dp.toPx(),
        ).coerceAtLeast(minOf(size.width, size.height) / 4f)
        val center = Offset(size.width / 2f, size.height / 2f)
        fun point(i: Int, fraction: Float): Offset {
            val theta = -PI / 2 + 2 * PI * i / n
            return Offset(center.x + (radius * fraction * cos(theta)).toFloat(), center.y + (radius * fraction * sin(theta)).toFloat())
        }

        // Rings + spokes.
        listOf(0.25f, 0.5f, 0.75f, 1f).forEach { ring ->
            val path = Path()
            for (i in 0 until n) point(i, ring).let { if (i == 0) path.moveTo(it.x, it.y) else path.lineTo(it.x, it.y) }
            path.close()
            val marked = markedRings.any { kotlin.math.abs(it.value / 100.0 - ring) < 0.001 }
            drawPath(
                path, if (marked) FT.TextMuted.copy(alpha = 0.55f) else FT.GlassBorder,
                style = Stroke(width = if (ring == 1f || marked) 1.5.dp.toPx() else 1.dp.toPx()),
            )
        }
        // A marked ring that is not one of the four grid rings is drawn too.
        markedRings.filter { r -> listOf(25.0, 50.0, 75.0, 100.0).none { kotlin.math.abs(it - r.value) < 0.001 } }.forEach { r ->
            val path = Path()
            for (i in 0 until n) point(i, (r.value / 100.0).toFloat()).let { if (i == 0) path.moveTo(it.x, it.y) else path.lineTo(it.x, it.y) }
            path.close()
            drawPath(path, FT.TextMuted.copy(alpha = 0.55f), style = Stroke(width = 1.5.dp.toPx()))
        }
        val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))
        for (i in 0 until n) {
            val missing = axes[i].value == null
            drawLine(
                color = if (missing) FT.TextMuted.copy(alpha = 0.7f) else FT.GlassBorder,
                start = center, end = point(i, 1f),
                strokeWidth = 1.dp.toPx(), pathEffect = if (missing) dash else null,
            )
        }

        // Polygon through the scored axes only.
        val scored = axes.indices.filter { axes[it].value != null }
        if (scored.size >= 3) {
            val poly = Path()
            scored.forEachIndexed { k, i ->
                val p = point(i, (axes[i].value!! / 100.0).toFloat().coerceIn(0f, 1f))
                if (k == 0) poly.moveTo(p.x, p.y) else poly.lineTo(p.x, p.y)
            }
            poly.close()
            drawPath(poly, accent.copy(alpha = 0.18f), style = Fill)
            drawPath(poly, accent, style = Stroke(width = 2.dp.toPx()))
        } else if (scored.size == 2) {
            drawLine(accent, point(scored[0], (axes[scored[0]].value!! / 100.0).toFloat()), point(scored[1], (axes[scored[1]].value!! / 100.0).toFloat()), 2.dp.toPx())
        }

        // Vertices + labels.
        for (i in 0 until n) {
            val axis = axes[i]
            val selected = i == selectedIndex
            val v = axis.value
            if (v != null) {
                val p = point(i, (v / 100.0).toFloat().coerceIn(0f, 1f))
                if (selected) drawCircle(axis.color.copy(alpha = 0.3f), radius = 11.dp.toPx(), center = p)
                drawVertex(axis, p)
            }
            // An axis with no value has no marker: anywhere on the spoke would read as a score. Its spoke is dashed and its
            // label says "—".
            drawAxisLabel(measurer, axis, selected, point(i, 1f), center, labelPad)
        }
        // The marked rings' values, just left of the top spoke (clear of its vertex), so a vertex can be read against them.
        markedRings.forEach { r ->
            val p = point(0, (r.value / 100.0).toFloat())
            val layout = measurer.measure(r.label, FTType.Micro.copy(color = FT.TextMuted))
            val tl = Offset(p.x - layout.size.width - 5.dp.toPx(), p.y - layout.size.height / 2f)
            drawRoundRect(FT.Surface.copy(alpha = 0.85f), tl - Offset(2.dp.toPx(), 0f), androidx.compose.ui.geometry.Size(layout.size.width + 4.dp.toPx(), layout.size.height.toFloat()), androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
            drawText(layout, topLeft = tl)
        }
    }
}

private fun DrawScope.drawVertex(axis: RadarAxis, p: Offset) {
    val r = 4.5.dp.toPx()
    when (axis.state) {
        com.bioscan.fieldterminal.domain.MetricState.Neutral -> {
            drawCircle(FT.Surface, radius = 6.dp.toPx(), center = p)
            drawCircle(axis.color, radius = r, center = p, style = Stroke(width = 2.dp.toPx()))
        }
        com.bioscan.fieldterminal.domain.MetricState.Warning, com.bioscan.fieldterminal.domain.MetricState.Critical -> {
            val t = Path().apply {
                moveTo(p.x, p.y - r * 1.35f); lineTo(p.x + r * 1.2f, p.y + r * 0.85f); lineTo(p.x - r * 1.2f, p.y + r * 0.85f); close()
            }
            drawCircle(FT.Surface, radius = 7.dp.toPx(), center = p)
            drawPath(t, axis.color, style = Fill)
        }
        else -> {
            drawCircle(FT.Surface, radius = 6.dp.toPx(), center = p)
            drawCircle(axis.color, radius = r, center = p)
        }
    }
}

// The axis name over its value (or status tag) on a second line: half the width of one line, so it fits beside a spoke.
private fun labelText(axis: RadarAxis): String = when {
    axis.valueLabel != null -> "${axis.label}\n${axis.valueLabel}"
    axis.value != null -> "${axis.label}\n${"%.0f".format(axis.value)}"
    else -> "${axis.label}\n—"
}

private fun DrawScope.drawAxisLabel(measurer: TextMeasurer, axis: RadarAxis, selected: Boolean, tip: Offset, center: Offset, pad: Float) {
    val style = (if (selected) FTType.CaptionStrong else FTType.Caption)
        .copy(color = if (axis.value == null) FT.TextMuted else if (selected) FT.TextPrimary else FT.TextSecondary, textAlign = TextAlign.Center)
    val layout = measurer.measure(labelText(axis), style)
    // Push the label outward along the spoke, keeping it on the canvas.
    val dx = tip.x - center.x
    val dy = tip.y - center.y
    val len = kotlin.math.sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
    val ox = tip.x + dx / len * pad - layout.size.width * (0.5f - 0.5f * dx / len)
    val oy = tip.y + dy / len * pad - layout.size.height * (0.5f - 0.5f * dy / len)
    val x = ox.coerceIn(0f, size.width - layout.size.width)
    val y = oy.coerceIn(0f, size.height - layout.size.height)
    drawText(layout, topLeft = Offset(x, y))
}
