package com.bioscan.fieldterminal.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bioscan.fieldterminal.domain.score.ScoreBand
import com.bioscan.fieldterminal.domain.score.ScoreScale
import com.bioscan.fieldterminal.domain.score.ScoreTrend
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import kotlin.math.roundToInt

// Scoring system (docs/scoring-system/02-page-mockups.md §0), drawn as the v1 tick gauge (FTGauge.kt): the full ring is
// the whole 0-150 scale, so 100 (top of normal) sits at two thirds and SUPERCHARGED (125) past five sixths. The sweep is
// CHARGE's holographic spectrum or the domain's own two stops; the band is said in words beside the ring
// ([bandWordColor] / the overlay text), never by tinting the sweep. A provisional or partial reading draws its lit ticks
// faded. The centre holds the number, the direction against yesterday, and an optional label.

/** The colour of a band's word (LOW, PRIMED...) under a gauge: status colours for the low bands, platinum ink above. */
fun ringColor(band: ScoreBand): Color = when (band) {
    ScoreBand.LOW -> FT.Bad
    ScoreBand.BELOW_NORMAL -> FT.Warn
    ScoreBand.NORMAL -> FT.TextPrimary
    ScoreBand.PRIMED, ScoreBand.SUPERCHARGED -> FT.Ok
}

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
    palette: GaugePalette = GaugePalette.Charge,
    label: String? = null,
    valueStyle: TextStyle = when {
        size >= 140.dp -> FTType.DisplayMetric.copy(fontSize = (size.value * 0.27f).sp)
        size >= 72.dp -> FTType.MetricMedium
        else -> FTType.Telemetry
    },
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
    FTGauge(
        pct = shown?.let { (it / ScoreScale.MAX).toFloat() },
        palette = palette,
        size = size,
        dim = state != RingState.SCORED,
        modifier = modifier.semantics { contentDescription = semantic },
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Text(
                text = shown?.roundToInt()?.toString() ?: emptyText,
                style = valueStyle,
                color = if (shown == null) FT.TextMuted else FT.TextPrimary,
            )
            if (label != null) Text(label, style = FTType.LabelCaps, color = FT.TextSecondary, modifier = Modifier.padding(top = 4.dp))
            val marks = listOfNotNull(trendGlyph(trend).takeIf { it.isNotEmpty() && shown != null }, "⚠".takeIf { capped }).joinToString(" ")
            if (marks.isNotEmpty()) {
                Text(
                    text = marks,
                    style = FTType.Micro,
                    color = when {
                        capped -> FT.Warn
                        trend == ScoreTrend.UP -> FT.Ok
                        trend == ScoreTrend.DOWN -> FT.Bad
                        else -> FT.TextSecondary
                    },
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

/** The band word under a hero gauge (PRIMED, STEADY...), in CHARGE's holographic ink when [holo]. */
@Composable
fun GaugeStateWord(word: String, band: ScoreBand?, holo: Boolean, modifier: Modifier = Modifier) {
    val style = FTType.HeroHeading
    if (holo && band != null && band >= ScoreBand.PRIMED) {
        Text(word, style = style.copy(brush = Brush.linearGradient(listOf(Color(0xFF8AF0D2), Color(0xFF8BD3FF), Color(0xFFC6A8FF)))), modifier = modifier)
    } else {
        Text(word, style = style, color = band?.let(::ringColor) ?: FT.TextMuted, modifier = modifier)
    }
}
