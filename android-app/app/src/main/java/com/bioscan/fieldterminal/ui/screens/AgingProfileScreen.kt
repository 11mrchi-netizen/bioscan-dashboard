package com.bioscan.fieldterminal.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.AgingProfileOverview
import com.bioscan.fieldterminal.data.AgingProfileRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.domain.DataAvailability
import com.bioscan.fieldterminal.domain.aging.BiologicalAgeResult
import com.bioscan.fieldterminal.ui.components.DotPlot
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTDataState
import com.bioscan.fieldterminal.ui.components.TileHeader
import com.bioscan.fieldterminal.ui.components.InfoHelpButton
import com.bioscan.fieldterminal.domain.DisplayValue
import com.bioscan.fieldterminal.ui.components.FTMetricValue
import com.bioscan.fieldterminal.ui.components.FTStatePill
import com.bioscan.fieldterminal.ui.components.ageAccelerationState
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.format.DateTimeFormatter

// DAV-232 (09A Aging Profile, Phase 1). Overview / Dimensions / History /
// Explainability, per DAV-232's own structure -- scoped to PhenoAge +
// functional Cardio Age only; KDM/homeostatic dysregulation/molecular/
// organ-system sections are added here later without restructuring the page.
@Composable
fun AgingProfileScreen(onBack: () -> Unit) {
    var overview by remember { mutableStateOf<AgingProfileOverview?>(null) }
    LaunchedEffect(Unit) {
        overview = AgingProfileRepository(SupabaseClientProvider.client).loadOverview()
    }

    Column(modifier = Modifier.fillMaxSize().background(FT.Base)) {
        TileHeader(onBack = onBack, title = "AGING PROFILE")

        val current = overview
        when {
            current == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = FT.Emerald)
            }
            current.chronologicalAgeYears == null -> Box(Modifier.fillMaxSize().padding(22.dp), contentAlignment = Alignment.Center) {
                FTDataState(DataAvailability.Unavailable, "Set your date of birth in Setup › Profile to see your Aging Profile.")
            }
            else -> Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                OverviewCard(current)
                DimensionsCard(current)
                HistoryCard(current)
            }
        }
    }
}

@Composable
private fun OverviewCard(overview: AgingProfileOverview) {
    FTCard(title = "OVERVIEW") {
        BioAgeHero(overview)
        StatLineHelp("Chronological age", "${overview.chronologicalAgeYears} yr", "Chronological age", "Your real age, from date of birth (Setup › Profile). Every biological-age model is compared against this.")
    }
}

// The hero of the aging pages: each model's current biological age, side by
// side and never merged into one figure (models stay parallel, DAV-230). Each
// cell is label + help, the age as a display metric, a state pill and the
// signed delta against real age. Shared with the User tab's AGING card.
@Composable
internal fun BioAgeHero(overview: AgingProfileOverview, modifier: Modifier = Modifier) {
    if (overview.phenoAge == null && overview.cardioAge == null) {
        FTDataState(DataAvailability.Unavailable, "No biological-age model has a result yet.", modifier)
        return
    }
    Row(modifier = modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        overview.phenoAge?.let { BioAgeHeroCell(it, PHENOAGE_HELP, Modifier.weight(1f)) }
        overview.cardioAge?.let { BioAgeHeroCell(it, CARDIO_AGE_HELP, Modifier.weight(1f)) }
    }
}

@Composable
private fun BioAgeHeroCell(result: BiologicalAgeResult, help: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(result.model.label.uppercase(), style = FTType.LabelCaps, color = FT.TextMuted)
            InfoHelpButton(result.model.label, help)
        }
        if (result.isAvailable) {
            FTMetricValue(DisplayValue(primary = "%.0f".format(result.biologicalAge), unit = "YRS"))
            FTStatePill(ageAccelerationState(result.ageAcceleration))
            result.ageAcceleration?.let {
                Text(
                    "%+.1f yrs vs. real age".format(it),
                    style = FTType.Label,
                    color = if (it <= 0) FT.Emerald else FT.Warning,
                )
            }
        } else {
            FTDataState(DataAvailability.Unavailable, result.unavailableReason ?: "Unavailable")
        }
    }
}

@Composable
private fun DimensionsCard(overview: AgingProfileOverview) {
    FTCard(title = "DIMENSIONS") {
        Text("CLINICAL", style = sectionLabel, color = FT.TextMuted)
        overview.phenoAge?.let { ResultLine(it, PHENOAGE_HELP) } ?: FTDataState(DataAvailability.Unavailable, "No lab draw with all 9 PhenoAge markers yet.")
        Text("FUNCTIONAL", style = sectionLabel, color = FT.TextMuted, modifier = Modifier.padding(top = 6.dp))
        overview.cardioAge?.let { ResultLine(it, CARDIO_AGE_HELP) } ?: FTDataState(DataAvailability.Unavailable, "No VO2max reading yet.")
        Text(
            "Molecular and organ-system dimensions arrive in a later pass.",
            style = FTType.Caption,
            color = FT.TextMuted,
            modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun HistoryCard(overview: AgingProfileOverview) {
    FTCard(title = "HISTORY") {
        Text(
            "PhenoAge, by draw",
            style = FTType.RowTitle,
            color = FT.TextPrimary,
        )
        val available = overview.phenoAgeHistory.filter { it.isAvailable }
        if (available.size < 2) {
            Text(
                "Not enough complete draws yet for a trend — shown as points, never connected, until there's real history to connect.",
                style = FTType.Caption,
                color = FT.TextSecondary,
            )
        }
        if (available.isNotEmpty()) {
            DotPlot(
                points = available.map { it.observedAt to it.biologicalAge!! },
                refLow = null,
                refHigh = null,
                dotColor = { FT.Emerald },
            )
        }
        overview.phenoAgeHistory.filter { !it.isAvailable }.forEach { incomplete ->
            Text(
                "${incomplete.observedAt.format(DateTimeFormatter.ofPattern("d MMM yyyy"))}: incomplete — ${incomplete.unavailableReason}",
                style = FTType.Caption,
                color = FT.TextMuted,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        if (overview.phenoAgeHistory.isEmpty()) {
            FTDataState(DataAvailability.Unavailable, "No lab draws yet.")
        }
    }
}

@Composable
private fun ResultLine(result: BiologicalAgeResult, help: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(result.model.label, style = FTType.Body, color = FT.TextSecondary)
            InfoHelpButton(result.model.label, help)
        }
        Column(horizontalAlignment = Alignment.End) {
            if (result.isAvailable) {
                Text(
                    "%.1f yr".format(result.biologicalAge),
                    style = FTType.Value,
                    color = FT.TextPrimary,
                )
                Text(
                    "%+.1f yr vs. chronological".format(result.ageAcceleration),
                    style = FTType.MonoCaption,
                    color = if (result.ageAcceleration!! < 0) FT.Emerald else FT.Warning,
                )
            } else {
                Text(
                    result.unavailableReason ?: "Unavailable",
                    style = FTType.Caption,
                    color = FT.TextMuted,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

@Composable
private fun StatLineHelp(label: String, value: String, helpTitle: String, helpBody: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, style = FTType.Body, color = FT.TextSecondary)
            InfoHelpButton(helpTitle, helpBody)
        }
        Text(
            value,
            style = FTType.Value,
            color = FT.TextPrimary,
            textAlign = TextAlign.End,
        )
    }
}

private val sectionLabel = FTType.Label

private const val PHENOAGE_HELP =
    "PhenoAge (Levine et al. 2018) is a published formula combining 9 routine blood markers " +
        "(albumin, creatinine, glucose, CRP, lymphocyte %, MCV, RDW, alkaline phosphatase, WBC) " +
        "plus your age into a single age-equivalent, via a mortality-risk model. Computed from your " +
        "most recent lab draw that has all 9 markers together — not necessarily your latest draw. " +
        "A negative acceleration (younger than your real age) reflects a lower modeled mortality risk " +
        "for this marker profile, not a guarantee about your own future health.\n\n" +
        "Trained on a general US adult population (NHANES III), not specific to any age band or sex. " +
        "Recalculates automatically from your latest complete lab draw every time you open this page — " +
        "there's nothing to trigger manually, and nothing is cached from an older draw."

private const val CARDIO_AGE_HELP =
    "Cardio Age compares your measured VO2max against FRIEND registry / Cooper Institute norms " +
        "(as tabulated in ACSM's Guidelines for Exercise Testing and Prescription) for your sex, " +
        "interpolated between published age-decade medians. A functional, not clinical, age-equivalent — " +
        "clamped, not extrapolated, past either end of the table: fitter than the youngest bracket's " +
        "median reads as that bracket's age (e.g. 24.5), not a fabricated age below 20.\n\n" +
        "Reference table applies to US adults aged 20-79; needs your sex set in Setup › Profile. " +
        "Recalculates automatically from your latest synced VO2max reading every time you open this page."
