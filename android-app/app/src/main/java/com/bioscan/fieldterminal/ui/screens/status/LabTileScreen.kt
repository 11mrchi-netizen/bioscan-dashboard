package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.NutritionOverview
import com.bioscan.fieldterminal.data.NutritionRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.domain.AddEntryStart
import com.bioscan.fieldterminal.domain.AddEntryType
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.PinnedPage
import com.bioscan.fieldterminal.ui.components.SegmentedToggle
import com.bioscan.fieldterminal.ui.components.SubTabRow
import com.bioscan.fieldterminal.ui.nav.LabTab
import com.bioscan.fieldterminal.ui.nav.PlanningSection
import com.bioscan.fieldterminal.ui.screens.AddEntrySheet
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.LocalDate

// DAV-74/DAV-87: the Labs tile page; DAV-209 moved Supplements in from Fuel.
// App restructure: the Lab tile holds what is tried, planned and tested: N=1 (the analysis cards that were on Today),
// SUPPLEMENTS, PLANNING (the training block, the food plan with meal prep, race fuel) and TESTS. Bloodwork moved to
// Health.
@Composable
fun LabTileScreen(
    onBack: () -> Unit,
    onOpenPantry: () -> Unit = {},
    onOpenPlanner: () -> Unit = {},
    onOpenAllBlocks: () -> Unit = {},
    onOpenEvent: (Long) -> Unit = {},
    // Set by a deep link (supplement reminder -> SUPPLEMENTS, fuel reminder -> PLANNING › FOOD PLAN, body check-in
    // reminder -> TESTS with the check-in up); applied once, then cleared via the callback.
    requestedTab: LabTab? = null,
    requestedSection: PlanningSection? = null,
    onRequestedTabConsumed: () -> Unit = {},
    openBodyCheckIn: Boolean = false,
    onBodyCheckInHandled: () -> Unit = {},
) {
    var tab by rememberSaveable { mutableStateOf(requestedTab ?: if (openBodyCheckIn) LabTab.Tests else LabTab.N1) }
    var section by rememberSaveable { mutableStateOf(requestedSection ?: PlanningSection.Training) }
    LaunchedEffect(requestedTab, requestedSection) {
        if (requestedTab != null || requestedSection != null) {
            requestedTab?.let { tab = it }
            requestedSection?.let { section = it; tab = LabTab.Planning }
            onRequestedTabConsumed()
        }
    }
    var test by rememberSaveable { mutableStateOf<LabTest?>(null) }
    LaunchedEffect(openBodyCheckIn) {
        if (openBodyCheckIn) {
            tab = LabTab.Tests
            test = LabTest.BodyCheckIn
            onBodyCheckInHandled()
        }
    }

    // Contract A4: title, context line and sub-tab rail stay pinned; only the tab's content scrolls, from the top on a tab change.
    val scroll = rememberScrollState()
    LaunchedEffect(tab, section) { scroll.scrollTo(0) }
    PinnedPage(
        onBack = onBack,
        title = "LAB",
        subtitle = when (tab) {
            LabTab.N1 -> "WHAT GOES WITH WHAT · EXPERIMENTS · SUMMARIES"
            LabTab.Supplements -> "ROSTER · PANTRY · EXPERIMENTS"
            LabTab.Planning -> "TRAINING · FOOD PLAN · RACE FUEL"
            LabTab.Tests -> "START A TEST · RESULTS GO TO LOG"
            LabTab.Routine -> "THE WEEK BY PLACE · COMMUTE · PHONE"
        },
        accent = FT.DomainLabs,
        rail = { SubTabRow(items = LabTab.entries, selected = tab, label = { it.label }, onSelect = { tab = it }) },
        scrollState = scroll,
    ) {
        when (tab) {
            LabTab.N1 -> N1Tab()
            LabTab.Supplements -> SupplementsScreen(onOpenPantry = onOpenPantry)
            LabTab.Planning -> PlanningTab(section, onSection = { section = it }, onOpenPlanner, onOpenAllBlocks, onOpenEvent)
            LabTab.Tests -> TestsTab(onStart = { test = it })
            LabTab.Routine -> RoutineTab()
        }
    }

    when (test) {
        LabTest.Grip -> AddEntrySheet(onDismiss = { test = null }, onSaved = { test = null }, start = AddEntryStart.Form(AddEntryType.Grip))
        LabTest.BodyCheckIn -> BodyCheckInSheet(onDismiss = { test = null }, onSaved = { test = null })
        LabTest.SweatTest -> com.bioscan.fieldterminal.ui.screens.events.SweatTestSheet(onDismiss = { test = null }, onSaved = { test = null })
        LabTest.RunningForm -> com.bioscan.fieldterminal.ui.screens.form.RunningFormSheet(onDismiss = { test = null })
        null -> Unit
    }
}

// Lab › PLANNING: the three plans, one at a time under a toggle. Food plan keeps the ten-day plan and Sunday meal prep
// (were Fuel › PLAN and PREP) under its own toggle.
@Composable
private fun PlanningTab(
    section: PlanningSection,
    onSection: (PlanningSection) -> Unit,
    onOpenPlanner: () -> Unit,
    onOpenAllBlocks: () -> Unit,
    onOpenEvent: (Long) -> Unit,
) {
    var food by rememberSaveable { mutableStateOf(FoodPlanView.Days) }
    var planOpenDays by rememberSaveable { mutableStateOf(setOf<LocalDate>()) }
    var overview by remember { mutableStateOf<NutritionOverview?>(null) }
    // The food plan reads better with today's intake; without it (a failed read) the plan still shows.
    LaunchedEffect(Unit) { overview = runCatching { NutritionRepository(SupabaseClientProvider.client).loadOverview() }.getOrNull() }

    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(top = 12.dp)) {
        SegmentedToggle(options = PlanningSection.entries, selected = section, labelOf = { it.label }, onSelect = onSection, accentColor = FT.DomainLabs)
    }
    when (section) {
        PlanningSection.Training -> com.bioscan.fieldterminal.ui.screens.training.TrainingPlanTab(onOpenPlanner, onOpenAllBlocks = onOpenAllBlocks)
        PlanningSection.Food -> {
            Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(top = 10.dp)) {
                SegmentedToggle(options = FoodPlanView.entries, selected = food, labelOf = { it.label }, onSelect = { food = it }, accentColor = FT.DomainFuel)
            }
            when (food) {
                FoodPlanView.Days -> com.bioscan.fieldterminal.ui.screens.fuel.FuelPlanPage(
                    overview,
                    openDays = planOpenDays,
                    onToggleDay = { d -> planOpenDays = if (d in planOpenDays) planOpenDays - d else planOpenDays + d },
                )
                FoodPlanView.Prep -> com.bioscan.fieldterminal.ui.screens.fuel.MealPrepPage(overview)
            }
        }
        PlanningSection.RaceFuel -> Column(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Each event opens its own fuel plan on its FUEL tab.", style = FTType.Caption, color = FT.TextSecondary)
            com.bioscan.fieldterminal.ui.screens.events.EventsCard(onOpenEvent = onOpenEvent)
        }
    }
}

private enum class FoodPlanView(val label: String) { Days("NEXT 10 DAYS"), Prep("MEAL PREP") }

enum class LabTest { Grip, BodyCheckIn, SweatTest, RunningForm }

// Lab › TESTS: where a test starts. Its result is saved like any other entry, so it shows in Log and on the cards that
// read it (grip and the check-in on Health › Body, the sweat test in race fuel plans).
@Composable
private fun TestsTab(onStart: (LabTest) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        TestRow("GRIP STRENGTH", "Dynamometer, best of three per hand. Two minutes.") { onStart(LabTest.Grip) }
        TestRow("BODY CHECK-IN", "Grip, tape girths and three skinfolds, once a month before breakfast. About 15 minutes.") { onStart(LabTest.BodyCheckIn) }
        TestRow("SWEAT TEST", "Weigh before and after a session of an hour or more, with what you drank. It sets your sweat rate.") { onStart(LabTest.SweatTest) }
        TestRow("RUNNING FORM ANALYSER", "A 10–20 s side-on clip, read on the phone: foot landing, knee and shin, trunk lean, bounce and, in slow motion, contact time.") { onStart(LabTest.RunningForm) }
    }
}

@Composable
private fun TestRow(title: String, line: String, onStart: (() -> Unit)?) {
    FTCard(title = title) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(line, style = FTType.BodySmall, color = FT.TextSecondary, modifier = Modifier.weight(1f))
            if (onStart != null) {
                FTButton("START", emphasis = FTButtonEmphasis.Primary, onClick = onStart)
            } else {
                FTButton("COMING", enabled = false, emphasis = FTButtonEmphasis.Tertiary, onClick = {})
            }
        }
    }
}
