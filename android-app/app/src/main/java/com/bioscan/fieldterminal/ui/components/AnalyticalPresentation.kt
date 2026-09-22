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
import com.bioscan.fieldterminal.domain.TrendState
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

private val Telemetry = com.bioscan.fieldterminal.ui.theme.RobotoMono
private val Interface = com.bioscan.fieldterminal.ui.theme.Inter

@Composable
fun FTMetricValue(value: DisplayValue, modifier: Modifier = Modifier) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value.primary, color = FT.TextPrimary, fontFamily = Telemetry, fontWeight = FontWeight.Bold, fontSize = 36.sp)
            value.unit?.let {
                Spacer(Modifier.width(6.dp))
                Text(it, color = FT.TextSecondary, fontFamily = Telemetry, fontSize = 13.sp)
            }
        }
        value.secondary?.let { Text(it, color = FT.TextSecondary, fontFamily = Interface, fontSize = 13.sp) }
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
        Text(treatment.symbol, color = treatment.color, fontFamily = Telemetry, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Spacer(Modifier.width(5.dp))
        Text(treatment.label, color = treatment.color, fontFamily = Telemetry, fontWeight = FontWeight.Bold, fontSize = 11.sp)
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
        fontFamily = Telemetry,
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
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
        fontFamily = Telemetry,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
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
        fontFamily = Telemetry,
        fontSize = 10.sp,
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
    val span = (maximum - minimum).takeIf { it > 0.0 } ?: 1.0
    fun fraction(value: Double?) = value?.let { ((it - minimum) / span).toFloat().coerceIn(0f, 1f) }

    Column(modifier = modifier.semantics { contentDescription = range.label }) {
        Text(range.label.uppercase(), color = FT.TextSecondary, fontFamily = Telemetry, fontSize = 10.sp)
        Canvas(modifier = Modifier.fillMaxWidth().height(24.dp).padding(top = 6.dp)) {
            val y = size.height / 2f
            drawLine(FT.GlassTrack, androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), strokeWidth = 8f)
            drawLine(FT.Emerald.copy(alpha = 0.38f), androidx.compose.ui.geometry.Offset(0f, y), androidx.compose.ui.geometry.Offset(size.width, y), strokeWidth = 8f)
            fraction(range.baseline)?.let { x ->
                drawCircle(FT.TextSecondary, radius = 4f, center = androidx.compose.ui.geometry.Offset(x * size.width, y), style = Stroke(2f))
            }
            fraction(range.current)?.let { x ->
                drawCircle(currentColor, radius = 5f, center = androidx.compose.ui.geometry.Offset(x * size.width, y))
            }
        }
        Text(
            text = "CURRENT " + formatRangeValue(range.current) + " · BASELINE " + formatRangeValue(range.baseline),
            color = FT.TextMuted,
            fontFamily = Telemetry,
            fontSize = 10.sp,
        )
    }
}

// Titled container matching Card.kt's exact shape/API (title bar + padded
// content) for screens that have migrated to Futuristic Material -- Card.kt
// itself stays legacy-amber until every one of its callers migrates (see
// FuturisticMaterialTokens.kt's own "legacy tokens stay intact" precedent).
@Composable
fun FTCard(title: String, modifier: Modifier = Modifier, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .border(FT.BorderWidth, FT.GlassBorder, RoundedCornerShape(FT.RadiusCard))
            .background(FT.GlassFill, RoundedCornerShape(FT.RadiusCard)),
    ) {
        Text(
            text = title.uppercase(),
            color = FT.Emerald,
            fontFamily = Interface,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        )
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
        Text(label, color = if (availability == DataAvailability.Building) FT.Analysis else FT.TextSecondary, fontFamily = Telemetry, fontWeight = FontWeight.Bold, fontSize = 11.sp)
        reason?.let { Text(it, color = FT.TextSecondary, fontFamily = Interface, fontSize = 13.sp) }
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
            Text(chart.timeWindow ?: "CURRENT WINDOW", color = FT.TextSecondary, fontFamily = Telemetry, fontWeight = FontWeight.Bold, fontSize = 11.sp)
            Spacer(Modifier.weight(1f))
            chart.unit?.let { Text(it, color = FT.TextMuted, fontFamily = Telemetry, fontSize = 11.sp) }
        }
        content()
        if (chart.missingness.name != "None") {
            Text("DATA: " + chart.missingness.name.uppercase(), color = FT.TextMuted, fontFamily = Telemetry, fontSize = 10.sp)
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
                Text(presentation.label.uppercase(), color = FT.TextPrimary, fontFamily = Interface, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                presentation.context?.let { Text(it, color = FT.TextSecondary, fontFamily = Interface, fontSize = 13.sp) }
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
