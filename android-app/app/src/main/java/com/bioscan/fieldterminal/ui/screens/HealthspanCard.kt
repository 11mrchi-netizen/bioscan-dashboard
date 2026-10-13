package com.bioscan.fieldterminal.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.domain.DisplayValue
import com.bioscan.fieldterminal.domain.aging.FactorStatus
import com.bioscan.fieldterminal.domain.aging.HEALTHSPAN_MIN_ASSESSED
import com.bioscan.fieldterminal.domain.aging.HealthspanFactor
import com.bioscan.fieldterminal.domain.aging.HealthspanSummary
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTMetricValue
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// Analysis Layer 3 / Phase 8. The one place in the app that shows a cross-metric grade: the User profile page. Product-defined,
// not clinically validated; the factor rows show what it rests on and what is missing.
@Composable
fun HealthspanCard(summary: HealthspanSummary?, failed: Boolean = false) {
    FTCard(
        title = "HEALTHSPAN",
        info = "A general grade on this page only: the share of the factors that could be assessed from your own data and that are favorable. A factor is assessed only when its input is recent, complete, in a known unit and not conflicting; otherwise it says what is missing. Product-defined and provisional, not a medical assessment.",
        methodology = "healthspan",
    ) {
        if (summary == null) {
            if (failed) Text("Couldn't load the inputs.", style = FTType.BodySmall, color = FT.Critical)
            else CircularProgressIndicator(color = FT.Emerald)
            return@FTCard
        }
        FTMetricValue(
            DisplayValue(
                primary = summary.grade ?: "PRELIMINARY",
                unit = null,
                secondary = "${summary.favorable} of ${summary.assessed} assessed factors favorable",
            ),
        )
        if (summary.preliminary) {
            Text(
                "No letter yet: at least $HEALTHSPAN_MIN_ASSESSED of the 8 factors must be assessed, and ${summary.assessed} are.",
                style = FTType.Caption,
                color = FT.TextMuted,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            summary.factors.forEach { FactorRow(it) }
        }
        Text("PRODUCT-DEFINED · PROVISIONAL · NOT CLINICALLY VALIDATED", style = FTType.Micro, color = FT.TextMuted)
    }
}

private fun FactorStatus.tag(): Pair<String, Color> = when (this) {
    FactorStatus.FAVORABLE -> "FAVORABLE" to FT.Emerald
    FactorStatus.NEUTRAL -> "NEUTRAL" to FT.TextSecondary
    FactorStatus.OPPORTUNITY -> "OPPORTUNITY" to FT.Warning
    FactorStatus.UNASSESSED -> "NOT ASSESSED" to FT.TextMuted
}

@Composable
private fun FactorRow(f: HealthspanFactor) {
    val (tag, color) = f.status.tag()
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(f.id.label, style = FTType.RowTitle, color = FT.TextPrimary, modifier = Modifier.weight(1f))
            Text(tag, style = FTType.MonoCaption, color = color)
        }
        f.reading?.let { Text(it, style = FTType.BodySmall, color = FT.TextSecondary) }
        if (f.missingInputs.isNotEmpty()) {
            Text("Needs: " + f.missingInputs.joinToString("; "), style = FTType.Caption, color = FT.TextMuted)
        }
        f.action?.let { Text(it, style = FTType.Caption, color = if (f.status == FactorStatus.OPPORTUNITY) FT.Warning else FT.TextMuted) }
    }
}
