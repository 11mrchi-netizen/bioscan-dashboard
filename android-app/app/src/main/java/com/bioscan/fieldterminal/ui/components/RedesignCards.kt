package com.bioscan.fieldterminal.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.MetricRegistryStore
import com.bioscan.fieldterminal.domain.EvalState
import com.bioscan.fieldterminal.domain.MetricState
import com.bioscan.fieldterminal.domain.StateWord
import com.bioscan.fieldterminal.domain.TimeRange
import com.bioscan.fieldterminal.domain.aggregate
import com.bioscan.fieldterminal.domain.analysis.Directionality
import com.bioscan.fieldterminal.domain.baselineStateWord
import com.bioscan.fieldterminal.domain.helpText
import com.bioscan.fieldterminal.domain.summary
import com.bioscan.fieldterminal.ui.theme.FTType
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.sqrt
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// Redesign (spec §1, §2): the global range selector and the four reusable card layouts. Contract amendment: the
// per-card "?" now carries the explanatory notes that used to sit inside cards; the title is right-aligned, the
// latest reading and its direction pill sit at the top left.

/** The one range every Training, Nutrition and Health graph follows. Process-wide, so it survives moving between pages. */
object GlobalTimeRange {
    var selected by mutableStateOf(TimeRange.Month)
}

/** Card chrome without a built-in title row: the header below supplies it. */
@Composable
fun RedesignCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(FT.RadiusCard)
    Column(
        modifier = modifier.fillMaxWidth().clip(shape).border(FT.BorderWidth, FT.GlassBorder, shape).background(FT.Glass, shape).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/**
 * Spec §1.1. Left: the latest reading, the direction pill directly below it. Right: the title, vertically centred against
 * that block, with the "?" at its end opening the explanatory notes.
 */
@Composable
fun MetricCardHeader(
    title: String,
    latest: String?,
    unit: String? = null,
    pill: StateWord? = null,
    helpBody: String? = null,
    helpDetails: String? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (latest != null || pill != null) Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (latest != null) Row(verticalAlignment = Alignment.Bottom) {
                Text(latest, style = FTType.MetricMedium, color = FT.TextPrimary)
                unit?.let { Text(" $it", style = FTType.MonoCaption, color = FT.TextMuted, modifier = Modifier.padding(bottom = 4.dp)) }
            }
            pill?.let { FTStatePill(it) }
        }
        trailing?.invoke()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
            Text(title.uppercase(), style = FTType.CardTitle, color = FT.TextSecondary, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
            helpBody?.let { InfoHelpButton(title = title, body = it, details = helpDetails) }
        }
    }
}

@Composable
internal fun rememberHelp(methodology: String?, info: String?): Pair<String?, String?> {
    val context = LocalContext.current
    val entry = remember(methodology) { methodology?.let { MetricRegistryStore.get(context, it) } }
    val body = listOfNotNull(info, entry?.summary()).joinToString("\n\n").ifBlank { null }
    return body to entry?.helpText()
}

/** A person's own baseline over a window: the mean, with limits one standard deviation either side. Null under 5 readings. */
data class WindowBaseline(val baseline: Double, val low: Double, val high: Double)

fun windowBaseline(values: List<Double>): WindowBaseline? {
    if (values.size < 5) return null
    val mean = values.average()
    val sd = sqrt(values.sumOf { (it - mean) * (it - mean) } / (values.size - 1))
    if (sd <= 0.0) return null
    return WindowBaseline(mean, mean - sd, mean + sd)
}

/**
 * The direction pill read from the band the card draws: the latest reading against the window's own limits (ABOVE BAND, BELOW
 * BAND or WITHIN BAND), coloured by what that direction means for the metric. Building under five readings.
 */
fun windowStateWord(values: List<Double>, directionality: Directionality, healthyRange: ClosedFloatingPointRange<Double>? = null): StateWord {
    val band = windowBaseline(values) ?: return StateWord(MetricState.Building)
    val state = when {
        values.last() > band.high -> EvalState.ShiftUp
        values.last() < band.low -> EvalState.ShiftDown
        else -> EvalState.Stable
    }
    val word = baselineStateWord(state, directionality, healthyRange, values.takeLast(7).average())
    return word.copy(
        word = when (state) {
            EvalState.ShiftUp -> "ABOVE BAND"
            EvalState.ShiftDown -> "BELOW BAND"
            else -> "WITHIN BAND"
        },
    )
}

private fun gapFor(range: TimeRange): Int? = when (range.grain) {
    TimeRange.Grain.Day -> 1
    TimeRange.Grain.Week -> 7
    TimeRange.Grain.Month -> 31
}

/** Spec §1.3 Trend Card: header and the line chart (daily at a month or less, drawn as its trend with each day a dot). */
@Composable
fun TrendCard(
    title: String,
    points: List<Pair<LocalDate, Double>>,
    unit: String,
    modifier: Modifier = Modifier,
    range: TimeRange = GlobalTimeRange.selected,
    pill: StateWord? = null,
    methodology: String? = null,
    info: String? = null,
    color: Color = FT.DomainHeart,
    scale: ClosedFloatingPointRange<Double>? = null,
    format: (Double) -> String = { if (abs(it) >= 100) "%.0f".format(it) else "%.1f".format(it) },
    footer: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val (helpBody, helpDetails) = rememberHelp(methodology, info)
    val latest = points.maxByOrNull { it.first }?.second
    val shown = aggregate(points, range)
    RedesignCard(modifier) {
        MetricCardHeader(title, latest?.let(format), unit, pill, helpBody, helpDetails)
        if (shown.size >= 2) {
            DateTrendLine(points = shown, color = color, unit = unit, scale = scale, maxGapDays = gapFor(range), valueFormat = format, highlightColor = color)
        } else {
            Text("Not enough readings in this range yet.", style = FTType.Caption, color = FT.TextMuted)
        }
        footer?.invoke(this)
    }
}

/** One line on a [DualAxisChart]. */
data class AxisLine(val label: String, val points: List<Pair<LocalDate, Double>>, val color: Color)

/**
 * Two value axes on one date axis (nutrition: kcal on the left, grams on the right). Each axis scales to its own lines;
 * [leftBand] shades a range on the left axis (the TDEE range). Axis ends are labelled; a key names every line.
 */
@Composable
fun DualAxisChart(
    left: List<AxisLine>,
    right: List<AxisLine>,
    modifier: Modifier = Modifier,
    leftBand: ClosedFloatingPointRange<Double>? = null,
    leftUnit: String = "kcal",
    bandLabel: String = "RANGE",
    rightUnit: String = "g",
    height: Dp = 150.dp,
) {
    val all = (left + right).flatMap { it.points }
    if (all.size < 2) return
    val d0 = all.minOf { it.first }
    val d1 = all.maxOf { it.first }
    val span = ChronoUnit.DAYS.between(d0, d1).coerceAtLeast(1).toFloat()
    fun bounds(lines: List<AxisLine>, band: ClosedFloatingPointRange<Double>?): Pair<Double, Double>? {
        val v = lines.flatMap { l -> l.points.map { it.second } } + listOfNotNull(band?.start, band?.endInclusive)
        if (v.isEmpty()) return null
        val lo = 0.0
        val hi = v.max().takeIf { it > lo } ?: 1.0
        return lo to hi * 1.08
    }
    val lb = bounds(left, leftBand)
    val rb = bounds(right, null)
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth()) {
            Text(leftUnit.uppercase() + (lb?.let { "  0–%.0f".format(it.second) } ?: ""), style = FTType.Micro, color = FT.TextMuted, modifier = Modifier.weight(1f))
            Text(rightUnit.uppercase() + (rb?.let { "  0–%.0f".format(it.second) } ?: ""), style = FTType.Micro, color = FT.TextMuted, textAlign = TextAlign.End, modifier = Modifier.weight(1f))
        }
        Canvas(Modifier.fillMaxWidth().height(height)) {
            fun x(d: LocalDate) = ChronoUnit.DAYS.between(d0, d).toFloat() / span * size.width
            fun y(v: Double, b: Pair<Double, Double>) = size.height - ((v - b.first) / (b.second - b.first)).toFloat() * size.height
            if (leftBand != null && lb != null) {
                drawRect(FT.Emerald.copy(alpha = 0.14f), Offset(0f, y(leftBand.endInclusive, lb)), Size(size.width, y(leftBand.start, lb) - y(leftBand.endInclusive, lb)))
            }
            fun draw(lines: List<AxisLine>, b: Pair<Double, Double>?) {
                if (b == null) return
                lines.forEach { l ->
                    val pts = l.points.sortedBy { it.first }
                    pts.zipWithNext().forEach { (a, c) ->
                        drawLine(l.color, Offset(x(a.first), y(a.second, b)), Offset(x(c.first), y(c.second, b)), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                    }
                    if (pts.size == 1) drawCircle(l.color, 3.dp.toPx(), Offset(x(pts[0].first), y(pts[0].second, b)))
                }
            }
            draw(left, lb)
            draw(right, rb)
        }
        FlowRow(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            (left + right).forEach { l ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(Modifier.height(3.dp).width(12.dp).background(l.color))
                    Text(l.label, style = FTType.Micro, color = FT.TextSecondary)
                }
            }
            if (leftBand != null) Text(bandLabel, style = FTType.Micro, color = FT.Emerald)
        }
    }
}

