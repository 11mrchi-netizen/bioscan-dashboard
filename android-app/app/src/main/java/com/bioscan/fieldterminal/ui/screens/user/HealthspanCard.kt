package com.bioscan.fieldterminal.ui.screens.user

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.bioscan.fieldterminal.domain.DataAvailability
import com.bioscan.fieldterminal.domain.DisplayValue
import com.bioscan.fieldterminal.domain.healthspan.HealthspanAxis
import com.bioscan.fieldterminal.domain.healthspan.HealthspanAxisResult
import com.bioscan.fieldterminal.domain.healthspan.HealthspanResult
import com.bioscan.fieldterminal.ui.components.FTBar
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTConfidenceChip
import com.bioscan.fieldterminal.ui.components.FTDataState
import com.bioscan.fieldterminal.ui.components.FTMetricValue
import com.bioscan.fieldterminal.ui.components.FTRadarChart
import com.bioscan.fieldterminal.ui.components.FTStatePill
import com.bioscan.fieldterminal.ui.components.RadarAxis
import com.bioscan.fieldterminal.ui.components.confidenceLevel
import com.bioscan.fieldterminal.ui.components.scoreState
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// Axis identity colours: Category tier for the lifestyle axes, Domain tier for the body-system axes. State
// (the pill, "attention", etc.) is always the separate scoreState() treatment, never these.
fun healthspanAxisColor(axis: HealthspanAxis): Color = when (axis) {
    HealthspanAxis.SLEEP -> FT.Category.Sleep.c500
    HealthspanAxis.MOVEMENT -> FT.Category.Activity.c500
    HealthspanAxis.CARDIO -> FT.DomainHeart
    HealthspanAxis.RECOVERY -> FT.Category.Wellbeing.c500
    HealthspanAxis.STRENGTH -> FT.Category.Activity.c300
    HealthspanAxis.BODY_COMPOSITION -> FT.DomainLabs
    HealthspanAxis.NUTRITION -> FT.Category.Intake.c500
}

private const val INFO =
    "A lifestyle-and-function index, not a clinical measure. Seven axes, each 0-100 from an evaluation the app already " +
        "runs (sleep, movement, VO2max vs age norm, HRV/RHR vs your baseline, lift standards, body fat, protein/calorie " +
        "adherence). The headline is their equal-weight mean over the axes that have data; an axis without data is " +
        "shown hollow and left out, never counted as zero. Tap an axis to see its contribution."

@Composable
fun HealthspanCard(result: HealthspanResult, loading: Boolean) {
    var selected by remember { mutableStateOf<Int?>(null) }
    FTCard(title = "HEALTHSPAN", info = INFO) {
        val score = result.score
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            if (score != null) {
                FTMetricValue(DisplayValue(primary = "%.0f".format(score), unit = "/ 100"))
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FTStatePill(scoreState(score))
                    FTConfidenceChip(confidenceLevel(result.confidence))
                }
            } else {
                FTDataState(
                    DataAvailability.Building,
                    if (loading) "Loading axes… (${result.confidence.have} of ${result.confidence.need} ready)"
                    else "Needs at least 3 of 7 axes (${result.confidence.have} ready) — see what each one needs below",
                )
            }
        }

        FTRadarChart(
            axes = result.axes.map { RadarAxis(it.axis.short, it.score, healthspanAxisColor(it.axis)) },
            selectedIndex = selected,
            onSelect = { selected = if (selected == it) null else it },
        )

        selected?.let { AxisDetail(result.axes[it]) }

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            val scored = result.axes.withIndex().filter { it.value.score != null }
                .sortedByDescending { it.value.headroomPts ?: 0.0 }
            scored.forEach { (i, axis) -> ImpactRow(axis, i == selected) { selected = if (selected == i) null else i } }
            result.axes.withIndex().filter { it.value.score == null }.forEach { (i, axis) ->
                MissingRow(axis, i == selected) { selected = if (selected == i) null else i }
            }
        }
        if (score != null) {
            Text("BAR = AXIS SCORE · +PTS = SHARE OF HEADLINE · −PTS = HEADROOM TO 100 · SORTED BY HEADROOM", style = FTType.Micro, color = FT.TextMuted)
        }
    }
}

@Composable
private fun AxisDetail(axis: HealthspanAxisResult) {
    Column(
        modifier = Modifier.fillMaxWidth().background(FT.GlassFill, RoundedCornerShape(FT.RadiusSmall)).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(axis.axis.label.uppercase(), style = FTType.LabelCaps, color = healthspanAxisColor(axis.axis))
            FTStatePill(scoreState(axis.score))
        }
        if (axis.score != null) {
            Text(
                "Score ${"%.0f".format(axis.score)}" +
                    (axis.contributionPts?.let { " · supplies ${"%.1f".format(it)} of the headline" } ?: "") +
                    (axis.headroomPts?.let { " · ${"%.1f".format(it)} pts of headroom" } ?: ""),
                style = FTType.BodySmall, color = FT.TextSecondary,
            )
            FTConfidenceChip(confidenceLevel(axis.confidence))
        } else {
            Text(axis.reason ?: "No data", style = FTType.BodySmall, color = FT.TextSecondary)
        }
    }
}

@Composable
private fun ImpactRow(axis: HealthspanAxisResult, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                axis.axis.label,
                style = if (selected) FTType.RowTitle else FTType.Body,
                color = if (selected) FT.TextPrimary else FT.TextSecondary,
                modifier = Modifier.weight(1f),
            )
            Text(axis.score?.let { "%.0f".format(it) } ?: "—", style = FTType.Telemetry, color = FT.TextPrimary)
            axis.contributionPts?.let { Text("  +${"%.1f".format(it)}", style = FTType.MonoCaption, color = FT.TextSecondary) }
            axis.headroomPts?.let { Text("  −${"%.1f".format(it)}", style = FTType.MonoCaption, color = FT.TextMuted) }
        }
        FTBar(fraction = ((axis.score ?: 0.0) / 100.0).toFloat(), color = healthspanAxisColor(axis.axis), height = 6.dp)
    }
}

@Composable
private fun MissingRow(axis: HealthspanAxisResult, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
        verticalAlignment = Alignment.Top,
    ) {
        Text(axis.axis.label, style = if (selected) FTType.RowTitle else FTType.Body, color = FT.TextMuted, modifier = Modifier.weight(0.4f))
        Text(axis.reason ?: "No data", style = FTType.Caption, color = FT.TextMuted, modifier = Modifier.weight(0.6f))
    }
}
