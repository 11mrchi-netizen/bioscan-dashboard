package com.bioscan.fieldterminal.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.domain.score.ScoreBand
import com.bioscan.fieldterminal.domain.score.ScoreScale
import com.bioscan.fieldterminal.domain.score.ScoreTrend
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

// Scoring system (docs/scoring-system/02-page-mockups.md §0): every score is a ring. One lap is 0-100; above 100 a
// second lap is drawn over the first from 12 o'clock on the same scale, so 150 is one and a half laps. The ring's
// colour is its band; the second lap is brighter, and glows from SUPERCHARGED (125). Ticks on the track mark 40 and 60.
// The centre holds only the number and the direction against yesterday.

/** The ring colour of a band. */
fun ringColor(band: ScoreBand): Color = when (band) {
    ScoreBand.LOW -> FT.Critical
    ScoreBand.BELOW_NORMAL -> FT.Warning
    ScoreBand.NORMAL -> FT.Emerald
    ScoreBand.PRIMED, ScoreBand.SUPERCHARGED -> FT.Emerald
}

/** The second lap's colour (PRIMED and SUPERCHARGED). */
val SecondLapColor: Color = FT.EmeraldHighlight

fun trendGlyph(trend: ScoreTrend?): String = when (trend) {
    ScoreTrend.UP -> "▲"
    ScoreTrend.DOWN -> "▼"
    ScoreTrend.SAME -> "●"
    null -> ""
}

enum class RingState { SCORED, PROVISIONAL, PARTIAL, BUILDING }

@Composable
fun ScoreRing(
    score: Double?,
    trend: ScoreTrend?,
    size: Dp,
    modifier: Modifier = Modifier,
    state: RingState = RingState.SCORED,
    capped: Boolean = false,
    stroke: Dp = if (size >= 120.dp) 12.dp else 6.dp,
    valueStyle: TextStyle = if (size >= 120.dp) FTType.DisplayMetric else FTType.Telemetry,
    emptyText: String = "—",
    description: String = "",
) {
    val shown = score?.coerceIn(0.0, ScoreScale.MAX)
    val band = shown?.let(ScoreScale::band)
    val semantic = buildString {
        append(description)
        if (shown != null) append(" ${shown.roundToInt()}, ${band?.label?.lowercase()}") else append(" not scored")
        if (state == RingState.PROVISIONAL) append(", provisional")
        if (capped) append(", capped")
    }
    Box(modifier = modifier.size(size).semantics { contentDescription = semantic }, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(size)) {
            val strokePx = stroke.toPx()
            val inset = strokePx / 2 + strokePx * 0.6f // room for the glow
            val arcSize = Size(this.size.width - 2 * inset, this.size.height - 2 * inset)
            val topLeft = Offset(inset, inset)
            val dashed = if (state == RingState.PROVISIONAL || state == RingState.PARTIAL || state == RingState.BUILDING)
                PathEffect.dashPathEffect(floatArrayOf(strokePx * 0.9f, strokePx * 0.7f)) else null

            // Track and the 40 / 60 ticks.
            drawArc(FT.GlassTrack, 0f, 360f, false, topLeft, arcSize, style = Stroke(strokePx, pathEffect = if (shown == null) dashed else null))
            for (tick in listOf(ScoreScale.LOW_BELOW, ScoreScale.NORMAL_FROM)) drawTick(tick, arcSize, topLeft, strokePx)

            if (shown != null && band != null) {
                val first = (minOf(shown, 100.0) / 100.0 * 360.0).toFloat()
                drawArc(ringColor(band), -90f, first, false, topLeft, arcSize, style = Stroke(strokePx, cap = StrokeCap.Round, pathEffect = dashed))
                if (shown > 100.0) {
                    val second = ((shown - 100.0) / 100.0 * 360.0).toFloat()
                    if (band == ScoreBand.SUPERCHARGED) {
                        drawArc(SecondLapColor.copy(alpha = 0.25f), -90f, second, false, topLeft, arcSize, style = Stroke(strokePx * 2.2f, cap = StrokeCap.Round))
                    }
                    drawArc(SecondLapColor, -90f, second, false, topLeft, arcSize, style = Stroke(strokePx * 0.75f, cap = StrokeCap.Round, pathEffect = dashed))
                }
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(
                text = shown?.roundToInt()?.toString() ?: emptyText,
                style = valueStyle,
                color = if (shown == null) FT.TextMuted else FT.TextPrimary,
            )
            val marks = listOfNotNull(trendGlyph(trend).takeIf { it.isNotEmpty() && shown != null }, "⚠".takeIf { capped }).joinToString(" ")
            if (marks.isNotEmpty()) {
                Text(
                    text = marks,
                    style = if (size >= 120.dp) FTType.Telemetry else FTType.Micro,
                    color = when {
                        capped -> FT.Warning
                        trend == ScoreTrend.UP -> FT.EmeraldHighlight
                        trend == ScoreTrend.DOWN -> FT.Critical
                        else -> FT.TextSecondary
                    },
                )
            }
        }
    }
}

private fun DrawScope.drawTick(score: Double, arcSize: Size, topLeft: Offset, strokePx: Float) {
    val angle = Math.toRadians(-90.0 + score / 100.0 * 360.0)
    val cx = topLeft.x + arcSize.width / 2
    val cy = topLeft.y + arcSize.height / 2
    val r = arcSize.width / 2
    val inner = r - strokePx * 0.7f
    val outer = r + strokePx * 0.7f
    drawLine(
        FT.TextMuted,
        Offset(cx + inner * cos(angle).toFloat(), cy + inner * sin(angle).toFloat()),
        Offset(cx + outer * cos(angle).toFloat(), cy + outer * sin(angle).toFloat()),
        strokeWidth = strokePx * 0.18f,
    )
}
