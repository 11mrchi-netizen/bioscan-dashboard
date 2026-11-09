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
fun HealthspanCard(summary: HealthspanSummary?, failed: Boolean = false, onOpenAging: () -> Unit = {}) {
    var openFactor by remember { mutableStateOf<HealthspanFactor?>(null) }
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

        // Tapping a factor (row or chart vertex) opens its page; biological age opens the Aging page, which holds its history.
        val open: (HealthspanFactor) -> Unit = { f ->
            if (f.id == HealthspanFactorId.BIOLOGICAL_AGE) onOpenAging() else openFactor = f
        }
        FTRadarChart(
            axes = summary.factors.map { f ->
                val (tag, color) = f.status.tag()
                RadarAxis(f.id.short(), f.score?.toDouble(), color)
            },
            selectedIndex = null,
            onSelect = { i -> summary.factors.getOrNull(i)?.let(open) },
        )

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            summary.factors.forEach { f -> FactorRow(f) { open(f) } }
        }
        Text("PRODUCT-DEFINED · PROVISIONAL · NOT CLINICALLY VALIDATED", style = FTType.Micro, color = FT.TextMuted)
    }
    openFactor?.let { f -> HealthspanFactorOverlay(f) { openFactor = null } }
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

// One row: the factor, its score and status, and a › — it opens the factor's own page (score, basis and the trend of
// each metric behind it). An unassessed factor shows what it is waiting for.
@Composable
private fun FactorRow(f: HealthspanFactor, onClick: () -> Unit) {
    val (tag, color) = f.status.tag()
    // The whole row is the touch target, at least 48 dp tall.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .ftClickable(onClick = onClick, shape = RoundedCornerShape(FT.RadiusSmall))
            .padding(vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(f.id.label, style = FTType.Body, color = FT.TextSecondary, modifier = Modifier.weight(1f))
            Text(f.score?.let { "$it · $tag" } ?: tag, style = FTType.MonoCaption, color = color)
            Text("›", style = FTType.MonoCaption, color = FT.TextMuted, modifier = Modifier.padding(start = 6.dp))
        }
        if (f.status == FactorStatus.UNASSESSED && f.missingInputs.isNotEmpty()) {
            Text("Needs: " + f.missingInputs.first(), style = FTType.Caption, color = FT.TextMuted, maxLines = 1)
        }
    }
}
