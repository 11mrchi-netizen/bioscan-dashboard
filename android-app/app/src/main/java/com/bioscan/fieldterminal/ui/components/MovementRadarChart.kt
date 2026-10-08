package com.bioscan.fieldterminal.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import com.bioscan.fieldterminal.ui.theme.Inter
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

// v11: 5-axis radar chart for Zepp movement evaluation scores (0-100).
// Axis order matches Zepp app radar clockwise from top:
// Stability (top), Consistency, Speed Decay, Rhythm, Continuity.
// ponytail: labels are static strings passed via the scores Map key order.
@Composable
fun MovementRadarChart(
    scores: Map<String, Float>,
    modifier: Modifier = Modifier,
    color: Color = FT.DomainTraining,
) {
    val axes = scores.keys.toList()
    val values = scores.values.toList()
    val n = axes.size.coerceAtLeast(3)
    val measurer = rememberTextMeasurer()
    val labelColor = FT.TextSecondary
    val gridColor = FT.TextMuted.copy(alpha = 0.25f)
    val fillColor = color.copy(alpha = 0.18f)
    val strokeColor = color

    Canvas(modifier = modifier.aspectRatio(1f)) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val radius = min(cx, cy) * 0.58f
        val labelRadius = min(cx, cy) * 0.82f

        // Grid rings at 25/50/75/100%.
        for (ring in listOf(0.25f, 0.5f, 0.75f, 1f)) {
            val r = radius * ring
            val gridPath = Path()
            for (i in 0 until n) {
                val angle = axisAngle(i, n)
                val pt = Offset(cx + r * cos(angle), cy + r * sin(angle))
                if (i == 0) gridPath.moveTo(pt.x, pt.y) else gridPath.lineTo(pt.x, pt.y)
            }
            gridPath.close()
            drawPath(gridPath, color = gridColor, style = Stroke(width = 1.dp.toPx()))
        }

        // Axis lines.
        for (i in 0 until n) {
            val angle = axisAngle(i, n)
            drawLine(
                color = gridColor,
                start = Offset(cx, cy),
                end = Offset(cx + radius * cos(angle), cy + radius * sin(angle)),
                strokeWidth = 1.dp.toPx(),
            )
        }

        // Data polygon.
        val dataPath = Path()
        for (i in 0 until n) {
            val angle = axisAngle(i, n)
            val v = (values.getOrElse(i) { 0f } / 100f).coerceIn(0f, 1f)
            val pt = Offset(cx + radius * v * cos(angle), cy + radius * v * sin(angle))
            if (i == 0) dataPath.moveTo(pt.x, pt.y) else dataPath.lineTo(pt.x, pt.y)
        }
        dataPath.close()
        drawPath(dataPath, color = fillColor)
        drawPath(dataPath, color = strokeColor, style = Stroke(width = 2.dp.toPx()))

        // Axis labels.
        for (i in 0 until n) {
            val angle = axisAngle(i, n)
            val lx = cx + labelRadius * cos(angle)
            val ly = cy + labelRadius * sin(angle)
            drawAxisLabel(measurer, axes.getOrElse(i) { "" }, lx, ly, labelColor)
        }
    }
}

// Clockwise from top: angle 0 = top = -π/2.
private fun axisAngle(i: Int, n: Int): Float =
    (-Math.PI / 2 + 2 * Math.PI * i / n).toFloat()

private fun DrawScope.drawAxisLabel(
    measurer: TextMeasurer,
    text: String,
    cx: Float,
    cy: Float,
    color: Color,
) {
    val result = measurer.measure(
        text,
        style = TextStyle(fontFamily = Inter, fontSize = 10.sp, fontWeight = FontWeight.Medium, color = color),
    )
    val x = cx - result.size.width / 2f
    val y = cy - result.size.height / 2f
    drawText(result, topLeft = Offset(x, y))
}
