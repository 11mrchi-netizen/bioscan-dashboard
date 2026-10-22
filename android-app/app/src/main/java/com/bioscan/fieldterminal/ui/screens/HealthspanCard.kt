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

// Analysis Layer 3 / Phase 8. The one place in the app that shows a cross-metric score: the User profile page. Product-defined,
// not clinically validated; the factor rows show what it rests on and what is missing.
//
// Every factor is a 1-100 score on one scale: favorable from 50, neutral from 25, an opportunity below. The radar plots each
// assessed factor at its score, so its 50 and 25 rings are those two lines; a factor that could not be assessed is a dashed
// spoke marked "—", left out of the polygon. The headline is the mean of the assessed scores, from domain/aging/Healthspan.kt.
@Composable
fun HealthspanCard(summary: HealthspanSummary?, failed: Boolean = false) {
    var selected by remember { mutableStateOf<Int?>(null) }
    FTCard(
        title = "HEALTHSPAN",
        info = "A general score from 1 to 100: the mean of the factors that could be assessed from your own data. Each factor is scored 1-100 on one scale, favorable from 50 and an opportunity below 25: as a percentile for your age and sex where a population table exists, otherwise against cited clinical targets or lifter standards. A factor is assessed only when its input is recent, complete and consistent; otherwise it says what is missing. Chart: each factor at its score; the 50 and 25 rings are the favorable and opportunity lines; a dashed spoke marked — is not assessed. Product-defined, not a medical assessment.",
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
                unit = summary.score?.let { "/ 100" },
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
                RadarAxis(f.id.short(), f.score?.toDouble(), color)
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

// Collapsed: the factor, its score and status, one line. Expanded (tap the row or the chart vertex): what the number is
// measured against, what was measured, what is missing, the caveats and the one action.
@Composable
private fun FactorRow(f: HealthspanFactor, expanded: Boolean, assessed: Int, onClick: () -> Unit) {
    val (tag, color) = f.status.tag()
    // The whole row is the touch target, at least 48 dp tall (it was 28 dp: the padding sat outside the clickable area).
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
            Text(f.score?.let { "$it · $tag" } ?: tag, style = FTType.MonoCaption, color = color)
        }
        if (expanded) {
            f.reading?.let { Text(it, style = FTType.BodySmall, color = FT.TextSecondary) }
            if (f.score != null && f.basis != null && assessed > 0) {
                Text("${f.score} / 100, ${f.basis.label}; 1 of $assessed factors in the mean.", style = FTType.Caption, color = FT.TextMuted)
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
