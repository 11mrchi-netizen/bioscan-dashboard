package com.bioscan.fieldterminal.ui.screens.plan

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.NutritionOverview
import com.bioscan.fieldterminal.data.NutritionRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.catching
import com.bioscan.fieldterminal.ui.components.PinnedPage
import com.bioscan.fieldterminal.ui.components.SegmentedToggle
import com.bioscan.fieldterminal.ui.components.SubTabRow
import com.bioscan.fieldterminal.ui.components.rememberCacheVersion
import com.bioscan.fieldterminal.ui.nav.PlanTab
import com.bioscan.fieldterminal.ui.screens.events.EventsCard
import com.bioscan.fieldterminal.ui.screens.fuel.FuelPlanPage
import com.bioscan.fieldterminal.ui.screens.fuel.MealPrepPage
import com.bioscan.fieldterminal.ui.screens.status.TrainingSessionsTab
import com.bioscan.fieldterminal.ui.screens.training.TrainingPlanTab
import com.bioscan.fieldterminal.ui.theme.FTType
import java.time.LocalDate
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// PLAN, a dock tab. What is planned and what was done:
// the training block, the food plan with meal prep, events with their race fuel, and the sessions. The content is the
// existing screens' (was Lab › Planning and Training › Sessions), hosted here unchanged.

@Composable
fun PlanScreen(
    onOpenPlanner: () -> Unit,
    onOpenAllBlocks: () -> Unit,
    onOpenEvent: (Long) -> Unit,
    onOpenSessionDetail: (Long) -> Unit,
    // A deep link (the fuel reminder -> FOOD, the block card on ME -> TRAINING); applied once, then cleared.
    requestedTab: PlanTab? = null,
    onRequestedTabConsumed: () -> Unit = {},
) {
    var tab by rememberSaveable { mutableStateOf(requestedTab ?: PlanTab.Training) }
    LaunchedEffect(requestedTab) {
        if (requestedTab != null) {
            tab = requestedTab
            onRequestedTabConsumed()
        }
    }
    val scroll = rememberScrollState()
    LaunchedEffect(tab) { scroll.scrollTo(0) }
    PinnedPage(
        onBack = null,
        title = "PLAN",
        subtitle = when (tab) {
            PlanTab.Training -> "CURRENT BLOCK · THIS WEEK · BLOCKS"
            PlanTab.Food -> "NEXT 10 DAYS · MEAL PREP"
            PlanTab.Race -> "EVENTS · RACE FUEL"
            PlanTab.Sessions -> "EVERY RECORDED SESSION"
        },
        accent = FT.DomainTraining,
        rail = { SubTabRow(items = PlanTab.entries, selected = tab, label = { it.label }, onSelect = { tab = it }) },
        scrollState = scroll,
    ) {
        when (tab) {
            PlanTab.Training -> TrainingPlanTab(onOpenPlanner, onOpenAllBlocks = onOpenAllBlocks)
            PlanTab.Food -> FoodPlan()
            PlanTab.Race -> Column(
                Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Each event opens its own fuel plan on its FUEL tab.", style = FTType.Caption, color = FT.TextSecondary)
                EventsCard(onOpenEvent = onOpenEvent)
            }
            PlanTab.Sessions -> TrainingSessionsTab(onOpenSessionDetail)
        }
    }
}

private enum class FoodPlanView(val label: String) { Days("NEXT 10 DAYS"), Prep("MEAL PREP") }

/** The ten-day food plan and Sunday meal prep under one toggle (was Lab › Planning › Food plan). */
@Composable
private fun FoodPlan() {
    var view by rememberSaveable { mutableStateOf(FoodPlanView.Days) }
    var openDays by rememberSaveable { mutableStateOf(setOf<LocalDate>()) }
    var overview by remember { mutableStateOf<NutritionOverview?>(null) }
    // The food plan reads better with today's intake; without it (a failed read) the plan still shows.
    val cacheVersion = rememberCacheVersion()
    LaunchedEffect(cacheVersion) { overview = catching { NutritionRepository(SupabaseClientProvider.client).loadOverview() }.getOrNull() ?: overview }
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(top = 12.dp)) {
        SegmentedToggle(options = FoodPlanView.entries, selected = view, labelOf = { it.label }, onSelect = { view = it }, accentColor = FT.DomainFuel)
    }
    when (view) {
        FoodPlanView.Days -> FuelPlanPage(
            overview,
            openDays = openDays,
            onToggleDay = { d -> openDays = if (d in openDays) openDays - d else openDays + d },
        )
        FoodPlanView.Prep -> MealPrepPage(overview)
    }
}
