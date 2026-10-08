package com.bioscan.fieldterminal.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.bioscan.fieldterminal.domain.analysis.ComparisonResult
import com.bioscan.fieldterminal.domain.comparison.PercentileBand
import com.bioscan.fieldterminal.domain.comparison.presentComparison
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// DAV-200 (docs/analysis-layer-2/24-comparison-ui-integration.md). One line
// added to an existing evaluation card, not a new screen -- reuses this
// app's own established badge/pill visual language rather than a chart.
// No ranking math happens here: presentComparison() (DAV-199) already
// decided the band/label, this composable only formats it.
@Composable
fun ComparisonStrip(personal: ComparisonResult? = null, population: ComparisonResult? = null) {
    // Population-comparison row was withheld entirely until a real
    // ComparisonResult existed (DAV-196) -- a placeholder here previously
    // would have looked like the system tried and found nothing, when
    // nothing had been built to try yet. Now real; either row renders
    // independently since not every metric has both wired yet (e.g. steps
    // has a population artifact but no personal-history wiring so far).
    personal?.let { ComparisonRow("PERSONAL (${it.referenceIdentity.substringAfter("personal_")})", it) }
    population?.let { ComparisonRow("POPULATION", it) }
}

@Composable
private fun ComparisonRow(label: String, result: ComparisonResult) {
    val presentation = presentComparison(result)
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = FTType.MonoCaption, color = FT.TextMuted)
        Text(
            presentation.band?.let { band -> "${percentileBandLabel(band)} · ${result.percentile!!.toInt()}th pct" }
                ?: result.bandLabel?.let { categoryLabel(it) }
                ?: presentation.stateLabel,
            style = FTType.Caption,
            color = if (presentation.band != null || result.bandLabel != null) FT.TextPrimary else FT.TextSecondary,
            textAlign = TextAlign.End,
        )
    }
}

private fun percentileBandLabel(band: PercentileBand): String = when (band) {
    PercentileBand.TOP_DECILE -> "Top 10%"
    PercentileBand.ABOVE_AVERAGE -> "Above average"
    PercentileBand.AVERAGE -> "Average"
    PercentileBand.BELOW_AVERAGE -> "Below average"
    PercentileBand.BOTTOM_DECILE -> "Bottom 10%"
}

// e.g. "somewhat_active" -> "Somewhat active" -- a plain, generic
// snake_case-to-label formatter, not a metric-specific lookup table (a
// future non-steps threshold artifact reuses this unchanged).
private fun categoryLabel(raw: String): String =
    raw.replace('_', ' ').replaceFirstChar { it.uppercase() }
