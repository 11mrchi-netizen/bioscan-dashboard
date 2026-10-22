package com.bioscan.fieldterminal.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// Analysis Layer 3 / Phase 8. The one place in the app that shows a cross-metric grade: the User profile page. Product-defined,
// not clinically validated; the factor rows show what it rests on and what is missing.
//
// The radar plots each assessed factor's 0-100 score directly along its spoke axis.
@Composable
fun HealthspanCard(summary: HealthspanSummary?, failed: Boolean = false) {
    var selected by remember { mutableStateOf<Int?>(null) }
    FTCard(
        title = "HEALTHSPAN",
        info = "A general healthspan score on a 1-100 scale, computed as the average of assessed factor scores. A factor is assessed only when its input is recent, complete and consistent; otherwise it says what is missing. Product-defined, not a medical assessment.",
        methodology = "healthspan",
    ) {
        if (summary == null) {
            if (failed) Text("Couldn't load the inputs.", style = FTType.BodySmall, color = FT.Critical)
            else CircularProgressIndicator(color = FT.Emerald)
            return@FTCard
        }
        FTMetricValue(
            DisplayValue(
                primary = summary.score?.toString() ?: "PRELIMINARY",
                unit = if (summary.score != null) "/ 100" else null,
                secondary = "${summary.favorable} of ${summary.assessed} assessed factors favorable",
            ),
        )
        if (summary.preliminary) {
            Text(
                "No score yet: at least $HEALTHSPAN_MIN_ASSESSED of the 8 factors must be assessed, and ${summary.assessed} are.",
                style = FTType.Caption,
                color = FT.TextMuted,
            )
        }

        FTRadarChart(
            axes = summary.factors.map { f ->
                val (tag, color) = f.status.tag()
                RadarAxis(
                    label = f.id.short(),
                    value = f.score,
                    color = color,
                    valueLabel = if (f.score != null) "%.0f · %s".format(f.score, tag.shortTag() ?: "") else null
                )
            },
            selectedIndex = selected,
            onSelect = { selected = if (selected == it) null else it },
        )

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
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

// Collapsed: the factor name, numeric score (1-100) and range tag (e.g., "85 · FAVORABLE"). Expanded: details.
@Composable
private fun FactorRow(f: HealthspanFactor, expanded: Boolean, assessed: Int, onClick: () -> Unit) {
    val (tag, color) = f.status.tag()
    val rightLabel = if (f.score != null) "%.0f · %s".format(f.score, tag) else tag
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(if (expanded) Modifier.background(FT.GlassFill, RoundedCornerShape(FT.RadiusSmall)) else Modifier)
            .ftClickable(onClick = onClick, shape = RoundedCornerShape(FT.RadiusSmall))
            .padding(horizontal = if (expanded) 10.dp else 0.dp, vertical = if (expanded) 10.dp else 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(f.id.label, style = if (expanded) FTType.RowTitle else FTType.Body, color = if (expanded) FT.TextPrimary else FT.TextSecondary, modifier = Modifier.weight(1f))
            Text(rightLabel, style = FTType.MonoCaption, color = color)
        }
        if (expanded) {
            f.reading?.let { Text(it, style = FTType.BodySmall, color = FT.TextSecondary) }
            if (f.status != FactorStatus.UNASSESSED && assessed > 0) {
                Text("Counts as 1 of $assessed assessed factors in the score.", style = FTType.Caption, color = FT.TextMuted)
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
