package com.bioscan.fieldterminal.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bioscan.fieldterminal.domain.ChartPresentation
import com.bioscan.fieldterminal.domain.ConfidenceLevel
import com.bioscan.fieldterminal.domain.DataAvailability
import com.bioscan.fieldterminal.domain.DisplayValue
import com.bioscan.fieldterminal.domain.MetricPresentation
import com.bioscan.fieldterminal.domain.MetricState
import com.bioscan.fieldterminal.domain.PersonalRange
import com.bioscan.fieldterminal.domain.Provenance
import com.bioscan.fieldterminal.domain.RangeComparison
import com.bioscan.fieldterminal.domain.RangeKind
import com.bioscan.fieldterminal.domain.TrendState
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT


@Composable
fun FTMetricValue(value: DisplayValue, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value.primary, color = FT.TextPrimary, style = FTType.DisplayMetric)
            value.unit?.let {
                Spacer(Modifier.width(6.dp))
                Text(it, color = FT.TextSecondary, style = FTType.Value)
            }
        }
        value.secondary?.let { Text(it, color = FT.TextSecondary, style = FTType.BodySmall) }
    }
}

@Composable
fun FTStatePill(state: MetricState, modifier: Modifier = Modifier) {
    val treatment = stateTreatment(state)
    Row(
        modifier = modifier
            .semantics { contentDescription = treatment.label }
            .border(FT.BorderWidth, treatment.color.copy(alpha = 0.65f), RoundedCornerShape(FT.RadiusSmall))
            .background(treatment.color.copy(alpha = 0.12f), RoundedCornerShape(FT.RadiusSmall))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(treatment.symbol, color = treatment.color, style = FTType.Label)
        Spacer(Modifier.width(5.dp))
        Text(treatment.label, color = treatment.color, style = FTType.Label)
    }
}

@Composable
fun FTTrendIndicator(trend: TrendState, modifier: Modifier = Modifier) {
    val treatment = when (trend) {
        TrendState.Improving -> "↑" to "IMPROVING"
        TrendState.Declining -> "↓" to "DECLINING"
        TrendState.Stable -> "→" to "STABLE"
        TrendState.InsufficientData -> "·" to "NOT ENOUGH HISTORY"
        TrendState.Unavailable -> "—" to "TREND UNAVAILABLE"
    }
    Text(
        text = treatment.first + " " + treatment.second,
        modifier = modifier.semantics { contentDescription = treatment.second.lowercase() },
        color = FT.TextSecondary,
        style = FTType.Label,
    )
}

@Composable
fun FTConfidenceChip(level: ConfidenceLevel, modifier: Modifier = Modifier) {
    val label = "CONFIDENCE: " + level.name.uppercase()
    val color = when (level) {
        ConfidenceLevel.High -> FT.TextSecondary
        ConfidenceLevel.Medium -> FT.Info
        ConfidenceLevel.Low -> FT.Warning
    }
    Text(
        text = label,
        modifier = modifier
            .semantics { contentDescription = label.lowercase() }
            .border(FT.BorderWidth, color.copy(alpha = 0.5f), RoundedCornerShape(FT.RadiusSmall))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        color = color,
        style = FTType.Label,
    )
}

@Composable
fun FTProvenanceCue(provenance: List<Provenance>, modifier: Modifier = Modifier) {
    if (provenance.isEmpty()) return
    val label = provenance.joinToString(" + ") { it.displayName() }
    Text(
        text = label,
        modifier = modifier.semantics { contentDescription = "Source: " + label },
        color = FT.TextMuted,
        style = FTType.Micro,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun FTRangeIndicator(range: PersonalRange, modifier: Modifier = Modifier, currentColor: Color = FT.TextPrimary) {
    val minimum = range.lower
    val maximum = range.upper
    if (!range.sufficientHistory || minimum == null || maximum == null) {
        FTDataState(DataAvailability.Building, range.explanation ?: "More history is needed for a personal range.", modifier)
        return
    }
    // The track spans a domain wider than the band so the band reads as
    // "the expected range" and a current/baseline value outside it stays
    // visible instead of being clamped onto the band's edge.
    val bandSpan = (maximum - minimum).takeIf { it > 0.0 } ?: 1.0
    val pad = bandSpan * 0.08
    val domainMin = listOfNotNull(minimum, maximum, range.current, range.baseline).min() - pad
    val domainMax = listOfNotNull(minimum, maximum, range.current, range.baseline).max() + pad
    val domainSpan = (domainMax - domainMin).takeIf { it > 0.0 } ?: 1.0
    fun fraction(value: Double) = ((value - domainMin) / domainSpan).toFloat().coerceIn(0f, 1f)
    val bandColor = if (range.kind == RangeKind.ReferenceRange) FT.TextSecondary.copy(alpha = 0.30f) else FT.Emerald.copy(alpha = 0.38f)
    val comparisonLabel = when (range.comparison) {
        RangeComparison.Below -> "BELOW RANGE"
        RangeComparison.Within -> "WITHIN RANGE"
        RangeComparison.Above -> "ABOVE RANGE"
        RangeComparison.NotComparable -> null
    }

    Column(modifier = modifier.semantics { contentDescription = range.label + (comparisonLabel?.let { ", " + it.lowercase() } ?: "") }) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(range.label.uppercase(), color = FT.TextSecondary, style = FTType.MonoCaption, modifier = Modifier.weight(1f))
            comparisonLabel?.let { Text(it, color = FT.TextSecondary, style = FTType.Label) }
        }
        Canvas(modifier = Modifier.fillMaxWidth().height(24.dp).padding(top = 6.dp)) {
            val y = size.height / 2f
            val stroke = 8.dp.toPx()
            drawLine(FT.GlassTrack, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), strokeWidth = stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round)
            drawLine(
                bandColor,
                androidx.compose.ui.geometry.Offset(fraction(minimum) * size.width, y),
                androidx.compose.ui.geometry.Offset(fraction(maximum) * size.width, y),
                strokeWidth = stroke,
                cap = androidx.compose.ui.graphics.StrokeCap.Round,
            )
            range.baseline?.let {
                drawCircle(FT.TextSecondary, radius = 4.dp.toPx(), center = androidx.compose.ui.geometry.Offset(fraction(it) * size.width, y), style = Stroke(2.dp.toPx()))
            }
            range.current?.let {
                drawCircle(currentColor, radius = 5.dp.toPx(), center = androidx.compose.ui.geometry.Offset(fraction(it) * size.width, y))
            }
        }
        Text(
            text = buildString {
                append("CURRENT " + formatRangeValue(range.current))
                range.baseline?.let { append(" · BASELINE " + formatRangeValue(it)) }
                append(" · RANGE " + formatRangeValue(minimum) + "–" + formatRangeValue(maximum))
            },
            color = FT.TextMuted,
            style = FTType.MonoCaption,
        )
    }
}

// Titled container matching Card.kt's exact shape/API (title bar + padded
// content) for screens that have migrated to Futuristic Material -- Card.kt
// itself stays legacy-amber until every one of its callers migrates (see
// FuturisticMaterialTokens.kt's own "legacy tokens stay intact" precedent).
@Composable
fun FTCard(
    title: String,
    modifier: Modifier = Modifier,
    // Caveats / "what this is" copy lives behind one help button in the title
    // bar instead of a muted paragraph inside the card.
    info: String? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(FT.RadiusCard)
    Column(
        modifier = modifier
            .fillMaxWidth()
            // background()/border() only draw within this shape, they don't
            // clip -- invisible for text/stat-line content (nothing ever
            // drew in the corners anyway), but a rectangular child that
            // fills the full card (RouteMiniMap's map, DAV-186's elevation
            // chart) visibly squared off past the rounded corners without
            // this. clip() is what actually confines any child to the shape.
            .clip(shape)
            .border(FT.BorderWidth, FT.GlassBorder, shape)
            .background(FT.GlassFill, shape),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title.uppercase(),
                color = FT.Emerald,
                style = FTType.CardTitle,
                modifier = Modifier.weight(1f),
            )
            info?.let { InfoHelpButton(title = title, body = it) }
        }
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            content()
        }
    }
}

@Composable
fun FTDataState(availability: DataAvailability, reason: String? = null, modifier: Modifier = Modifier) {
    val label = when (availability) {
        DataAvailability.Available -> return
        DataAvailability.Sparse -> "SPARSE DATA"
        DataAvailability.Building -> "BUILDING"
        DataAvailability.Unavailable -> "UNAVAILABLE"
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(FT.BorderWidth, FT.GlassBorder, RoundedCornerShape(FT.RadiusModule))
            .background(FT.GlassFill, RoundedCornerShape(FT.RadiusModule))
            .padding(12.dp),
    ) {
        Text(label, color = if (availability == DataAvailability.Building) FT.Analysis else FT.TextSecondary, style = FTType.Label)
        reason?.let { Text(it, color = FT.TextSecondary, style = FTType.BodySmall) }
    }
}

@Composable
fun FTChartFrame(chart: ChartPresentation, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(FT.BorderWidth, FT.GlassBorder, RoundedCornerShape(FT.RadiusModule))
            .background(FT.GlassFill, RoundedCornerShape(FT.RadiusModule))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(chart.timeWindow ?: "CURRENT WINDOW", color = FT.TextSecondary, style = FTType.Label)
            Spacer(Modifier.weight(1f))
            chart.unit?.let { Text(it, color = FT.TextMuted, style = FTType.MonoCaption) }
        }
        content()
        if (chart.missingness.name != "None") {
            Text("DATA: " + chart.missingness.name.uppercase(), color = FT.TextMuted, style = FTType.Micro)
        }
    }
}

@Composable
fun FTAnalyticalCard(
    presentation: MetricPresentation,
    modifier: Modifier = Modifier,
    chartContent: (@Composable () -> Unit)? = null,
) {
    if (presentation.availability == DataAvailability.Unavailable) {
        FTDataState(DataAvailability.Unavailable, presentation.context, modifier)
        return
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = presentation.accessibilitySummary }
            .border(FT.BorderWidth, FT.GlassBorder, RoundedCornerShape(FT.RadiusCard))
            .background(FT.GlassFill, RoundedCornerShape(FT.RadiusCard))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(presentation.label.uppercase(), color = FT.TextPrimary, style = FTType.SectionTitle)
                presentation.context?.let { Text(it, color = FT.TextSecondary, style = FTType.BodySmall) }
            }
            FTStatePill(presentation.semanticState)
        }
        presentation.value?.let { FTMetricValue(it) }
        FTTrendIndicator(presentation.trend)
        presentation.confidence?.let { FTConfidenceChip(it) }
        FTProvenanceCue(presentation.provenance)
        presentation.range?.let { FTRangeIndicator(it) }
        if (presentation.availability == DataAvailability.Sparse || presentation.availability == DataAvailability.Building) {
            FTDataState(presentation.availability, presentation.context)
        }
        presentation.chart?.let { chart ->
            chartContent?.let { content -> FTChartFrame(chart, content = content) }
        }
    }
}

// Whole numbers print without a trailing ".0" (Double.toString() always
// shows one); one decimal place otherwise.
private fun formatRangeValue(value: Double?): String {
    if (value == null) return "—"
    return if (value == Math.floor(value)) value.toInt().toString() else "%.1f".format(value)
}

// Public read access to the one state treatment table, for surfaces that draw
// state without a pill (e.g. the body figure's zone tints) -- they must use
// the same color and word the pill uses, never a parallel table.
fun metricStateColor(state: MetricState): Color = stateTreatment(state).color
fun metricStateLabel(state: MetricState): String = stateTreatment(state).label

private data class StateTreatment(val label: String, val symbol: String, val color: Color)

private fun stateTreatment(state: MetricState) = when (state) {
    MetricState.Optimal -> StateTreatment("OPTIMAL", "●", FT.Emerald)
    MetricState.Neutral -> StateTreatment("CURRENT", "●", FT.TextSecondary)
    MetricState.Warning -> StateTreatment("ATTENTION", "!", FT.Warning)
    MetricState.Critical -> StateTreatment("CRITICAL", "!", FT.Critical)
    MetricState.Building -> StateTreatment("BUILDING", "·", FT.Analysis)
    MetricState.Unavailable -> StateTreatment("UNAVAILABLE", "—", FT.TextMuted)
}

private fun Provenance.displayName() = when (this) {
    Provenance.HealthConnect -> "HEALTH CONNECT"
    Provenance.Wearable -> "WEARABLE"
    Provenance.ImportedActivity -> "IMPORTED ACTIVITY"
    Provenance.Manual -> "MANUAL"
    Provenance.Derived -> "DERIVED"
    Provenance.ModelEstimated -> "MODEL ESTIMATE"
}
