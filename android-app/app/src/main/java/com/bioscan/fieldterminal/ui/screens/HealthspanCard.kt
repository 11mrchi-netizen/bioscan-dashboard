package com.bioscan.fieldterminal.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.domain.DisplayValue
import com.bioscan.fieldterminal.domain.aging.FactorStatus
import com.bioscan.fieldterminal.domain.aging.HEALTHSPAN_MIN_ASSESSED
import com.bioscan.fieldterminal.domain.aging.HealthspanFactor
import com.bioscan.fieldterminal.domain.aging.HealthspanFactorId
import com.bioscan.fieldterminal.domain.aging.HealthspanSummary
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTMetricValue
import com.bioscan.fieldterminal.ui.components.FTRadarChart
import com.bioscan.fieldterminal.ui.components.RadarAxis
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// Analysis Layer 3 / Phase 8. The one place in the app that shows a cross-metric grade: the User profile page. Product-defined,
// not clinically validated; the factor rows show what it rests on and what is missing.
//
// The radar is a picture of the same factors, not a second score: each assessed factor sits on a ring for its status (outer =
// favorable, middle = neutral, inner = opportunity) and a factor that could not be assessed is a hollow dashed marker left out of
// the polygon. The grade itself is still only "favorable of assessed", computed in domain/aging/Healthspan.kt.
@Composable
fun HealthspanCard(summary: HealthspanSummary?, failed: Boolean = false) {
    var selected by remember { mutableStateOf<Int?>(null) }
    FTCard(
        title = "HEALTHSPAN",
        info = "A general grade on this page only: the share of the factors that could be assessed from your own data and that are favorable. A factor is assessed only when its input is recent, complete, in a known unit and not conflicting; otherwise it says what is missing. In the chart, the outer ring is favorable, the middle neutral and the inner an opportunity; a hollow dashed marker means not assessed. Product-defined and provisional, not a medical assessment.",
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

        FTRadarChart(
            axes = summary.factors.map { f ->
                val (tag, color) = f.status.tag()
                RadarAxis(f.id.short(), f.status.radius(), color, valueLabel = tag.shortTag())
            },
            selectedIndex = selected,
            onSelect = { selected = if (selected == it) null else it },
        )

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            summary.factors.forEachIndexed { i, f ->
                FactorRow(f, expanded = i == selected, assessed = summary.assessed) { selected = if (selected == i) null else i }
            }
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

// Ring position only (not a score): favorable on the outer ring, opportunity near the centre; unassessed is left out.
private fun FactorStatus.radius(): Double? = when (this) {
    FactorStatus.FAVORABLE -> 100.0
    FactorStatus.NEUTRAL -> 60.0
    FactorStatus.OPPORTUNITY -> 25.0
    FactorStatus.UNASSESSED -> null
}

private fun String.shortTag(): String? = when (this) {
    "FAVORABLE" -> "FAV"
    "NEUTRAL" -> "NEU"
    "OPPORTUNITY" -> "OPP"
    else -> null
}

private fun HealthspanFactorId.short(): String = when (this) {
    HealthspanFactorId.CARDIO_FITNESS -> "CARDIO"
    HealthspanFactorId.BIOLOGICAL_AGE -> "BIO AGE"
    HealthspanFactorId.STRENGTH -> "STRENGTH"
    HealthspanFactorId.BODY_COMPOSITION -> "BODY"
    HealthspanFactorId.SLEEP -> "SLEEP"
    HealthspanFactorId.ACTIVITY -> "ACTIVITY"
    HealthspanFactorId.METABOLIC -> "LIPIDS"
    HealthspanFactorId.GLYCAEMIC -> "GLUCOSE"
}

// Collapsed: the factor and its status, one line. Expanded (tap the row or the chart vertex): what was measured, what is missing,
// the caveats and the one action.
@Composable
private fun FactorRow(f: HealthspanFactor, expanded: Boolean, assessed: Int, onClick: () -> Unit) {
    val (tag, color) = f.status.tag()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (expanded) Modifier.background(FT.GlassFill, RoundedCornerShape(FT.RadiusSmall)).padding(10.dp) else Modifier.padding(vertical = 2.dp))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(f.id.label, style = if (expanded) FTType.RowTitle else FTType.Body, color = if (expanded) FT.TextPrimary else FT.TextSecondary, modifier = Modifier.weight(1f))
            Text(tag, style = FTType.MonoCaption, color = color)
        }
        if (expanded) {
            f.reading?.let { Text(it, style = FTType.BodySmall, color = FT.TextSecondary) }
            if (f.status != FactorStatus.UNASSESSED && assessed > 0) {
                Text("Counts as 1 of $assessed assessed factors in the grade.", style = FTType.Caption, color = FT.TextMuted)
            }
            if (f.missingInputs.isNotEmpty()) {
                Text("Needs: " + f.missingInputs.joinToString("; "), style = FTType.Caption, color = FT.TextMuted)
            }
            f.notes.forEach { Text(it, style = FTType.Caption, color = FT.TextMuted) }
            f.action?.let { Text(it, style = FTType.Caption, color = if (f.status == FactorStatus.OPPORTUNITY) FT.Warning else FT.TextMuted) }
        } else if (f.status == FactorStatus.UNASSESSED && f.missingInputs.isNotEmpty()) {
            Text("Needs: " + f.missingInputs.first(), style = FTType.Caption, color = FT.TextMuted, maxLines = 1)
        }
    }
}
