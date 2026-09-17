package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.background
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bioscan.fieldterminal.data.AnalysisRepository
import com.bioscan.fieldterminal.data.NutritionOverview
import com.bioscan.fieldterminal.data.NutritionRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.model.BodyMetricsAnalysisRow
import com.bioscan.fieldterminal.domain.BodyFatEvaluation
import com.bioscan.fieldterminal.domain.NutritionEvaluation
import com.bioscan.fieldterminal.domain.WeightEvaluation
import com.bioscan.fieldterminal.domain.evaluateBodyFat
import com.bioscan.fieldterminal.domain.evaluateNutrition
import com.bioscan.fieldterminal.domain.evaluateWeightTrend
import com.bioscan.fieldterminal.ui.components.Card
import com.bioscan.fieldterminal.ui.components.StateRow
import com.bioscan.fieldterminal.ui.components.SubTabRow
import com.bioscan.fieldterminal.ui.components.TileHeader
import com.bioscan.fieldterminal.ui.nav.FuelTab
import com.bioscan.fieldterminal.ui.theme.FieldColors
import com.bioscan.fieldterminal.ui.theme.FieldTextStyles
import com.bioscan.fieldterminal.ui.theme.JetBrainsMono
import com.bioscan.fieldterminal.ui.theme.Saira
import java.time.LocalDate

// DAV-72 (First feedback fixes): Fuel tile page, tabs Nutrition/Hydration/
// Supplements/Weight-TDEE. Nutrition+Hydration migrated from the old
// combined NutritionHydrationScreen.kt (split into NutritionTabContent/
// HydrationTabContent there); Supplements reuses SupplementsScreen()
// untouched; Weight/TDEE folds in Category 5's Weight + Body Fat evaluations
// (previously stranded on the old Analysis sub-tab). No real TDEE
// calculation exists anywhere in this project yet (Category 10 is still
// backlog per ROADMAP.md) -- this tab shows the real weight/body-fat trend
// data that does exist rather than fabricate a TDEE number.
@Composable
fun FuelTileScreen(onBack: () -> Unit) {
    var tab by remember { mutableStateOf(FuelTab.Nutrition) }
    var overview by remember { mutableStateOf<NutritionOverview?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        overview = NutritionRepository(SupabaseClientProvider.client).loadOverview()
        isLoading = false
    }

    Column(modifier = Modifier.fillMaxSize().background(FieldColors.Ground).verticalScroll(rememberScrollState())) {
        TileHeader(title = "FUEL", context = "NUTRITION + HYDRATION + SUPPLEMENTS", onBack = onBack)
        SubTabRow(items = FuelTab.entries, selected = tab, label = { it.label }, onSelect = { tab = it })

        when {
            isLoading && tab != FuelTab.Supplements && tab != FuelTab.Weight -> Box(Modifier.fillMaxWidth().padding(vertical = 60.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = FieldColors.Amber)
            }
            else -> when (tab) {
                FuelTab.Nutrition -> overview?.let {
                    NutritionTabContent(it)
                    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 16.dp)) {
                        NutritionCard(evaluateNutrition(it.allDays))
                    }
                }
                FuelTab.Hydration -> overview?.let { HydrationTabContent(it) }
                FuelTab.Supplements -> SupplementsScreen()
                FuelTab.Weight -> WeightTdeeTab()
            }
        }
    }
}

@Composable
private fun WeightTdeeTab() {
    var bodyMetrics by remember { mutableStateOf<List<BodyMetricsAnalysisRow>?>(null) }

    LaunchedEffect(Unit) {
        bodyMetrics = AnalysisRepository(SupabaseClientProvider.client).loadBodyMetrics()
    }

    val b = bodyMetrics
    if (b == null) {
        Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = FieldColors.Amber)
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        val weightPoints = b.mapNotNull { row -> row.weightKg?.let { LocalDate.parse(row.date) to it } }
        WeightCard(evaluateWeightTrend(weightPoints))

        val bodyFatPoints = b.mapNotNull { row -> row.bodyFatPct?.let { LocalDate.parse(row.date) to it } }
        BodyFatCard(evaluateBodyFat(bodyFatPoints))

        Text(
            "TDEE estimation isn't built yet — needs a real activity-level model this project doesn't have (see DAV-76/Category 10).",
            style = TextStyle(fontFamily = Saira, fontSize = 12.5.sp),
            color = FieldColors.InkMuted,
        )
    }
}

@Composable
private fun WeightCard(eval: WeightEvaluation) {
    Card(title = "WEIGHT TREND") {
        StateRow(eval.state)
        StatLine("Confidence", eval.confidence.label)
        eval.emaToday?.let { StatLine("EMA (today)", "%.1f kg".format(it)) }
        eval.rateKgPerWeek?.let { StatLine("Rate", "%+.2f kg/week".format(it)) }
    }
}

// Phase A4 (Category 4). No SHIFT_UP/SHIFT_DOWN here -- the spec never
// defines a threshold for calling an energy-trend move a "shift" the way it
// does for Categories 1/2/3/5/6, so `Stable` is shown once the gate is met,
// not because nothing moves but because there's no rule to judge it by.
@Composable
private fun NutritionCard(eval: NutritionEvaluation) {
    Card(title = "NUTRITION (ANALYSIS)") {
        StateRow(eval.state)
        StatLine("Confidence", eval.confidence.label)
        eval.energyTrend14d?.let { StatLine("14-day energy trend", "%.0f kcal".format(it)) }
        eval.energyCv28d?.let { StatLine("28-day energy CV", "%.1f%%".format(it)) }
        eval.proteinAdherence14d?.let { StatLine("Protein in AMDR band (10-35% kcal)", "%.0f%%".format(it)) }
    }
}

@Composable
private fun BodyFatCard(eval: BodyFatEvaluation) {
    Card(title = "BODY FAT %") {
        StateRow(eval.state)
        StatLine("Confidence", eval.confidence.label)
        eval.latest?.let { StatLine("Latest", "%.1f%%".format(it)) }
        eval.previous?.let { StatLine("Previous (≥30d prior)", "%.1f%%".format(it)) }
        eval.delta?.let { StatLine("Delta", "%+.1f pp".format(it)) }
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = TextStyle(fontFamily = Saira, fontSize = 14.5.sp), color = FieldColors.InkMuted)
        Text(value, style = TextStyle(fontFamily = JetBrainsMono, fontSize = 14.5.sp), color = FieldColors.Ink)
    }
}
