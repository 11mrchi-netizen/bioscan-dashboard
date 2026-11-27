package com.bioscan.fieldterminal.ui.screens.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.domain.readMetric
import com.bioscan.fieldterminal.domain.score.InputDirection
import com.bioscan.fieldterminal.domain.score.Series
import com.bioscan.fieldterminal.ui.components.FTCardEmphasis
import com.bioscan.fieldterminal.ui.components.FTChevron
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.screens.today.SlotSparkline
import com.bioscan.fieldterminal.ui.theme.FTType
import java.time.LocalDate
import kotlin.math.abs
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// Contract A10, Lead, Support, Depth, for a detail page whose lead is a metric chart:
//   LEAD     the measure picked from the support list: its value, the verdict line, its chart over the page's range;
//   SUPPORT  up to FOCUS_SUPPORT_MAX measures as rows (value, direction, a month's line); tapping one makes it the lead
//            in place, and the page remembers the pick;
//   DEPTH    everything else, folded one line each (FocusDepth), the explanations in one fold (FocusHowBuilt).
// The selection is platinum (contract: selection is platinum), never a metric or domain hue.

/** One measure a [FocusMetrics] block can lead with or list. */
data class FocusMetric(
    val key: String,
    val label: String,
    val series: Series,
    val unit: String,
    val format: (Double) -> String,
    val color: Color,
    val minSd: Double = 0.0,
    val betterHigher: Boolean? = null,
    val info: String? = null,
    val target: Pair<Double, Double>? = null,
    val targetLabel: String? = null,
)

/**
 * The lead card (the picked measure) and the support list. [pageKey] keeps the pick per page. [leadExtra] draws inside the
 * lead card between the verdict line and the chart, for what only one measure has (last night's bed and wake times).
 */
@Composable
fun ColumnScope.FocusMetrics(
    pageKey: String,
    metrics: List<FocusMetric>,
    today: LocalDate = LocalDate.now(),
    leadExtra: @Composable ColumnScope.(key: String) -> Unit = {},
) {
    val shown = focusSupport(metrics)
    var saved by rememberSaveable(pageKey) { mutableStateOf<String?>(null) }
    val key = focusKey(saved, shown.map { it.key }) ?: return
    val lead = shown.first { it.key == key }
    val reading = remember(lead.series, today) { readMetric(lead.series, today, minSd = lead.minSd) }

    Section(lead.label, info = null, emphasis = FTCardEmphasis.Hero) {
        FocusHeadline(reading.latest, reading.latestDate, reading.position, reading.normal, reading.change, lead, today)
        leadExtra(key)
        TrendChart(lead.series, today, LocalRangeDays.current, reading.normal, lead.format, lead.color, target = lead.target, targetLabel = lead.targetLabel)
    }

    Section("MEASURES", trailing = "tap one to lead") {
        shown.forEach { m -> FocusSupportRow(m, selected = m.key == key, today = today) { saved = m.key } }
    }
}

@Composable
private fun FocusHeadline(
    latest: Double?,
    latestDate: LocalDate?,
    position: InputDirection?,
    normal: Pair<Double, Double>?,
    change: Double?,
    m: FocusMetric,
    today: LocalDate,
) {
    if (latest == null) {
        EmptyLine("No reading in the last week.")
        return
    }
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(m.format(latest), style = FTType.MetricMedium, color = FT.TextPrimary)
        Text(m.unit, style = FTType.MonoCaption, color = FT.TextMuted, modifier = Modifier.padding(bottom = 4.dp))
        whenWords(latestDate, today)?.let { Text("· $it", style = FTType.MonoCaption, color = FT.TextMuted, modifier = Modifier.padding(bottom = 4.dp)) }
    }
    val band = normal?.let { "${m.format(it.first)} to ${m.format(it.second)}" }
    val move = change?.let { c -> "${if (c >= 0) "+" else "−"}${m.format(abs(c))} vs the week before" }
    // A status colour says how it is, never which series it is (contract); the words carry it as well.
    val tint = when {
        position == null || position == InputDirection.NORMAL || m.betterHigher == null -> FT.TextSecondary
        (position == InputDirection.ABOVE) == m.betterHigher -> FT.Ok
        else -> FT.Warn
    }
    Text("${positionGlyph(position)} ${focusVerdictLine(position, band, move)}", style = FTType.Caption, color = tint)
}

@Composable
private fun FocusSupportRow(m: FocusMetric, selected: Boolean, today: LocalDate, onPick: () -> Unit) {
    val reading = remember(m.series, today) { readMetric(m.series, today, minSd = m.minSd) }
    val spark = remember(m.series, today) { (29 downTo 0).map { m.series[today.minusDays(it.toLong())] } }
    val shape = RoundedCornerShape(FT.RadiusSmall)
    Row(
        modifier = Modifier.fillMaxWidth()
            .then(if (selected) Modifier.background(FT.Glass, shape).border(FT.BorderWidth, FT.Platinum, shape) else Modifier)
            .ftClickable(shape = shape, role = Role.Button, onClickLabel = "Show ${m.label}", onClick = onPick)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(m.label, style = FTType.RowTitle, color = FT.TextPrimary)
            Text(positionWords(reading.position), style = FTType.Caption, color = FT.TextMuted)
        }
        Text(reading.latest?.let { "${m.format(it)} ${m.unit}".trim() } ?: "—", style = FTType.Telemetry, color = FT.TextPrimary)
        Text(positionGlyph(reading.position), style = FTType.Telemetry, color = FT.TextSecondary)
        SlotSparkline(spark, color = m.color)
        FTChevron(tint = FT.TextMuted)
    }
}

/**
 * Depth: a card folded to one line (title and its one-line result), opened on tap. Open or closed is remembered for the
 * visit only; nothing in Depth is needed to read the page.
 */
@Composable
fun FocusDepth(key: String, title: String, summary: String?, content: @Composable ColumnScope.() -> Unit) {
    var open by rememberSaveable(key) { mutableStateOf(false) }
    Section(
        title,
        trailing = if (open) null else (summary?.let { "$it ›" } ?: "›"),
        onClick = { open = !open },
    ) {
        if (open) content()
    }
}

/** The page's explanations in one place (contract A10): each "what is this" that used to sit behind a card's "?". */
@Composable
fun FocusHowBuilt(key: String, entries: List<Pair<String, String>>) {
    FocusDepth(key, "HOW THIS IS BUILT", entries.joinToString(" · ") { it.first.lowercase() }) {
        entries.forEach { (name, text) ->
            Text(name.uppercase(), style = FTType.LabelCaps, color = FT.TextSecondary)
            Text(text, style = FTType.BodySmall, color = FT.TextMuted)
        }
    }
}
