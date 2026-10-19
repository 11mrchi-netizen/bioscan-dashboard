package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.bioscan.fieldterminal.data.BodyCheckinRepository
import com.bioscan.fieldterminal.data.UserProfileRepository
import com.bioscan.fieldterminal.data.model.UserProfileRow
import com.bioscan.fieldterminal.domain.BodyCheckinRecord
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.AnalysisRepository
import com.bioscan.fieldterminal.data.NutritionOverview
import com.bioscan.fieldterminal.data.NutritionRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.model.BodyMetricsAnalysisRow
import com.bioscan.fieldterminal.data.model.StoolAnalysisRow
import com.bioscan.fieldterminal.domain.BodyFatEvaluation
import com.bioscan.fieldterminal.domain.BristolEvaluation
import com.bioscan.fieldterminal.domain.BristolPattern
import com.bioscan.fieldterminal.domain.DailyNutrition
import com.bioscan.fieldterminal.domain.DisplayValue
import com.bioscan.fieldterminal.domain.EvalState
import com.bioscan.fieldterminal.domain.MetricState
import com.bioscan.fieldterminal.domain.NutritionEvaluation
import com.bioscan.fieldterminal.domain.PROTEIN_PCT_HIGH
import com.bioscan.fieldterminal.domain.PROTEIN_PCT_LOW
import com.bioscan.fieldterminal.domain.TdeeEvaluation
import com.bioscan.fieldterminal.domain.WeightEvaluation
import com.bioscan.fieldterminal.domain.evaluateBodyFat
import com.bioscan.fieldterminal.domain.evaluateBristol
import com.bioscan.fieldterminal.domain.evaluateNutrition
import com.bioscan.fieldterminal.domain.evaluateTdee
import com.bioscan.fieldterminal.domain.evaluateWeightTrend
import com.bioscan.fieldterminal.domain.gateStateWord
import com.bioscan.fieldterminal.domain.proteinPercentSeries
import com.bioscan.fieldterminal.domain.trendStateWord
import com.bioscan.fieldterminal.ui.components.DateTrendLine
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTDataChip
import com.bioscan.fieldterminal.ui.components.FTDataState
import com.bioscan.fieldterminal.ui.components.FTMetricRow
import com.bioscan.fieldterminal.ui.components.FTMetricValue
import com.bioscan.fieldterminal.ui.components.FTSegment
import com.bioscan.fieldterminal.ui.components.FTSegmentBar
import com.bioscan.fieldterminal.ui.components.FTStatePill
import com.bioscan.fieldterminal.ui.components.LoadErrorCard
import com.bioscan.fieldterminal.ui.components.PinnedPage
import com.bioscan.fieldterminal.ui.components.SubTabRow
import com.bioscan.fieldterminal.ui.components.rememberLoadGuard
import java.time.OffsetDateTime
import com.bioscan.fieldterminal.ui.nav.NutritionTab
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

// DAV-72 (First feedback fixes): the Fuel tile page. App restructure: it is the Nutrition tile, NUTRITION · HYDRATION ·
// PLAN, trends over spot metrics. NUTRITION is what was logged (with protein on target, moved from Body), HYDRATION its
// own tab, PLAN today and the next two days of the fuel plan, linking to the full plan and meal prep in Lab › Planning.
// BODY moved to Health.
@Composable
fun NutritionTileScreen(
    onBack: () -> Unit,
    onOpenNutrientBreakdown: () -> Unit = {},
    onOpenFullPlan: () -> Unit = {},
    requestedTab: NutritionTab? = null,
    onRequestedTabConsumed: () -> Unit = {},
) {
    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(requestedTab ?: NutritionTab.Nutrition) }
    LaunchedEffect(requestedTab) {
        if (requestedTab != null) {
            tab = requestedTab
            onRequestedTabConsumed()
        }
    }
    // The plan's open days, kept here so they survive a tab switch.
    var planOpenDays by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(setOf<LocalDate>()) }
    var overview by remember { mutableStateOf<NutritionOverview?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    val guard = rememberLoadGuard()
    LaunchedEffect(guard.attempt) {
        guard.run { overview = NutritionRepository(SupabaseClientProvider.client).loadOverview() }
        isLoading = false
    }

    // Contract A4: title, context line and sub-tab rail stay pinned; only the tab's content scrolls, from the top on a tab change.
    val scroll = rememberScrollState()
    LaunchedEffect(tab) { scroll.scrollTo(0) }
    PinnedPage(
        onBack = onBack,
        title = "NUTRITION",
        subtitle = when (tab) {
            NutritionTab.Nutrition -> "CALORIES · MACROS · AVERAGES · TREND"
            NutritionTab.Hydration -> "HYDRATION · CAFFEINE · INTELLIGENCE"
            NutritionTab.Plan -> "TODAY AND THE NEXT 2 DAYS"
        },
        accent = FT.DomainFuel,
        rail = { SubTabRow(items = NutritionTab.entries, selected = tab, label = { it.label }, onSelect = { tab = it }) },
        scrollState = scroll,
    ) {

        when {
            guard.error != null && !isLoading -> LoadErrorCard(guard)
            isLoading -> Box(Modifier.fillMaxWidth().padding(vertical = 60.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = FT.DomainFuel)
            }
            else -> when (tab) {
                NutritionTab.Nutrition -> overview?.let {
                    NutritionTabContent(it, onOpenNutrientBreakdown = onOpenNutrientBreakdown)
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        NutritionCard(evaluateNutrition(it.allDays))
                        // DAV-76: the one real target this project has for protein (the AMDR band), day by day.
                        NutritionOnTargetCard(proteinPercentSeries(it.allDays))
                    }
                }
                NutritionTab.Hydration -> overview?.let { HydrationTabContent(it) }
                NutritionTab.Plan -> com.bioscan.fieldterminal.ui.screens.fuel.FuelPlanPage(
                    overview,
                    openDays = planOpenDays,
                    onToggleDay = { d -> planOpenDays = if (d in planOpenDays) planOpenDays - d else planOpenDays + d },
                    days = 3,
                    onOpenFullPlan = onOpenFullPlan,
                )
            }
        }
    }
}

// DAV-76. Green ("on-target here") already means "in range" everywhere else
// in this app (BLOODWORK STABLE, 0 FLAGS), not a color picked fresh for
// this card.
@Composable
private fun NutritionOnTargetCard(proteinSeries: List<Pair<LocalDate, Double>>) {
    FTCard(title = "PROTEIN ON-TARGET (AMDR 10–35%)") {
        if (proteinSeries.size < 2) {
            Text(
                "Not enough complete-day nutrition logs yet to chart a trend.",
                style = FTType.BodySmall,
                color = FT.TextSecondary,
            )
        } else {
            DateTrendLine(
                points = proteinSeries,
                color = FT.DomainFuel,
                refLow = PROTEIN_PCT_LOW * 100,
                refHigh = PROTEIN_PCT_HIGH * 100,
                unit = "% of calories from protein",
                // Chart rules: a day without a complete log is a gap, not a straight line across it.
                maxGapDays = 1,
            )
        }
    }
}

// Phase A4 (Category 4). No SHIFT_UP/SHIFT_DOWN here -- the spec never
// defines a threshold for calling an energy-trend move a "shift" the way it
// does for Categories 1/2/3/5/6, so `Stable` is shown once the gate is met,
// not because nothing moves but because there's no rule to judge it by.
@Composable
private fun NutritionCard(eval: NutritionEvaluation) {
    FTCard(dataQuality = eval.confidence, title = "NUTRITION (ANALYSIS)") {
        FTStatePill(gateStateWord(eval.state))
        eval.energyTrend14d?.let { FTMetricRow("14-day energy trend", "%.0f kcal".format(it)) }
        eval.energyCv28d?.let { FTMetricRow("28-day energy CV", "%.1f%%".format(it)) }
        eval.proteinAdherence14d?.let { FTMetricRow("Protein in AMDR band (10-35% kcal)", "%.0f%%".format(it)) }
    }
}

