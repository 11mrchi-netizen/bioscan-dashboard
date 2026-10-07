package com.bioscan.fieldterminal.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import com.bioscan.fieldterminal.ui.theme.Inter
import com.bioscan.fieldterminal.ui.theme.RobotoMono
import kotlin.math.cos
import kotlin.math.sin

// A band covers (previous band's upTo, upTo]; the first starts at `min`.
data class GaugeBand(val upTo: Double, val color: Color)

// Half-circle gauge with colored bands and a marker at `value` (clamped to the
// axis). Generic on purpose -- the Training tab feeds it TSB + the shared TSB
// thresholds, but nothing here knows about training load.
@Composable
fun BandedGauge(
    value: Double,
    min: Double,
    max: Double,
    bands: List<GaugeBand>,
    valueText: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    val span = max - min
    fun frac(v: Double) = ((v - min) / span).coerceIn(0.0, 1.0).toFloat()
    val activeColor = (bands.firstOrNull { value <= it.upTo } ?: bands.last()).color

    Box(modifier = modifier.fillMaxWidth().height(130.dp), contentAlignment = Alignment.BottomCenter) {
        Canvas(modifier = Modifier.fillMaxWidth().height(130.dp)) {
            val stroke = 16.dp.toPx()
            val r = minOf(size.width / 2f, size.height) - stroke
            val cx = size.width / 2f
            val cy = size.height - stroke / 2f
            val arcTopLeft = Offset(cx - r, cy - r)
            val arcSize = Size(r * 2, r * 2)

            var from = min
            bands.forEach { b ->
                val start = 180f + frac(from) * 180f
                val sweep = (frac(b.upTo) - frac(from)) * 180f
                // 1.5-degree gap between bands so thresholds read as edges.
                drawArc(b.color.copy(alpha = 0.85f), start + 0.75f, (sweep - 1.5f).coerceAtLeast(0.1f), false, arcTopLeft, arcSize, style = Stroke(stroke))
                from = b.upTo
            }

            val theta = Math.toRadians((180f + frac(value) * 180f).toDouble())
            val m = Offset(cx + (r * cos(theta)).toFloat(), cy + (r * sin(theta)).toFloat())
            drawCircle(FT.Surface, radius = stroke * 0.75f, center = m)
            drawCircle(activeColor, radius = stroke * 0.55f, center = m, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // The centred value is the card's hero metric.
            Text(valueText, style = FTType.DisplayMetric, color = FT.TextPrimary)
            Text(label, style = FTType.CaptionStrong, color = activeColor)
        }
    }
}
