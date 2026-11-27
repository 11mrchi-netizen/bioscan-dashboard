package com.bioscan.fieldterminal.ui.screens.me

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.domain.Sport
import com.bioscan.fieldterminal.domain.sportOf
import com.bioscan.fieldterminal.ui.components.FTIconButton
import com.bioscan.fieldterminal.ui.components.FTIcons
import com.bioscan.fieldterminal.ui.components.FTMotion
import com.bioscan.fieldterminal.ui.components.SubTabRow
import com.bioscan.fieldterminal.ui.components.TileHeader
import com.bioscan.fieldterminal.ui.components.ftPlatinumWash
import com.bioscan.fieldterminal.ui.nav.MeTab
import com.bioscan.fieldterminal.ui.screens.UserProfileScreen
import com.bioscan.fieldterminal.ui.screens.health.ConcernsTab
import com.bioscan.fieldterminal.ui.screens.performance.PerformanceHub
import com.bioscan.fieldterminal.ui.screens.status.BodyTabContent
import com.bioscan.fieldterminal.ui.screens.detail.LinkRow
import com.bioscan.fieldterminal.ui.screens.detail.Section
import com.bioscan.fieldterminal.ui.screens.status.LabsScreen
import com.bioscan.fieldterminal.ui.screens.status.RoutineTab
import com.bioscan.fieldterminal.ui.screens.user.Skill
import java.time.LocalDate
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// ME, a dock tab. The slow-moving picture:
// healthspan and biological age (the old User page), performance levels (Training's Running and Strength), body
// composition, health concerns and bloodwork (Health's tabs). Settings sit behind the gear (Setup was a dock tab).
// The content is the existing screens', hosted here unchanged.

@Composable
fun MeScreen(
    onOpenSettings: () -> Unit,
    onOpenAging: () -> Unit,
    onOpenTrainingPlan: () -> Unit,
    onOpenConditioning: () -> Unit,
    onOpenConcern: (String) -> Unit,
    onOpenSport: (Sport) -> Unit = {},
    requestedTab: MeTab? = null,
    onRequestedTabConsumed: () -> Unit = {},
    onOpenDayMap: (LocalDate) -> Unit = {},
    onOpenRecords: () -> Unit = {},
    onOpenSessions: () -> Unit = {},
) {
    var tab by rememberSaveable { mutableStateOf(requestedTab ?: MeTab.Healthspan) }
    LaunchedEffect(requestedTab) {
        if (requestedTab != null) {
            tab = requestedTab
            onRequestedTabConsumed()
        }
    }
    Column(modifier = Modifier.fillMaxSize().background(FT.Base).ftPlatinumWash()) {
        TileHeader(
            onBack = null,
            title = "ME",
            subtitle = when (tab) {
                MeTab.Healthspan -> "HEALTHSPAN · AGING · LEVELS · BLOCK"
                MeTab.Performance -> "EVERY SPORT YOU RECORD"
                MeTab.Body -> "WEIGHT · BODY FAT · MEASUREMENTS"
                MeTab.Health -> "INJURIES · PAIN · SYMPTOMS · ILLNESS · PREVENTION"
                MeTab.Bloodwork -> "HEALTH · LAB DRAWS · MARKERS"
                MeTab.Intimacy -> "HEALTH · STATS · TRENDS · STI TESTS · PARTNERS"
                MeTab.Routine -> "THE DAYS · TIME · PLACES · PHONE · PRACTICES"
                MeTab.Exposures -> "HEALTH · AIR · WHERE THE DOSE COMES FROM · WAYS TO IMPROVE"
            },
            accent = FT.DomainUser,
            action = {
                FTIconButton(FTIcons.Settings, "Settings", onOpenSettings)
            },
        )
        SubTabRow(items = MeTab.row, selected = tab.rowTab, label = { it.label }, onSelect = { tab = it })
        Box(Modifier.fillMaxWidth().height(FT.BorderWidth).background(FT.GlassBorder))
        // Sub-tab switch: a short slide toward the tab's side with a fade (FTMotion; snaps with reduced motion).
        AnimatedContent(
            targetState = tab,
            modifier = Modifier.fillMaxWidth().weight(1f),
            transitionSpec = { FTMotion.subTab(this, targetState.ordinal > initialState.ordinal) },
            label = "me-tab",
        ) { tab ->
        Box(Modifier.fillMaxSize()) {
            when (tab) {
                // The healthspan page scrolls itself.
                MeTab.Healthspan -> UserProfileScreen(
                    onOpenAging = onOpenAging,
                    onOpenTrainingPlan = onOpenTrainingPlan,
                    onOpenSkill = { skill ->
                        when (skill) {
                            Skill.RUNNING -> onOpenSport(sportOf("run", null))
                            Skill.STRENGTH -> onOpenSport(sportOf("strength", null))
                            Skill.CONDITIONING -> onOpenConditioning()
                        }
                    },
                    showHeader = false,
                )
                // Intimacy scrolls itself too: its three views (overview, partners, one partner) each start at the top.
                MeTab.Intimacy -> Column(Modifier.fillMaxSize()) {
                    BackToHealth { tab = MeTab.Health }
                    IntimacyPage()
                }
                else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    when (tab) {
                        MeTab.Performance -> Column(Modifier.padding(16.dp)) {
                            PerformanceHub(onOpenSport, onOpenRecords, onOpenSessions)
                        }
                        MeTab.Body -> BodyTabContent()
                        MeTab.Health -> {
                            ConcernsTab(onOpenConcern = onOpenConcern)
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { PreventionAndRisk(onOpen = { tab = it }) }
                        }
                        MeTab.Bloodwork -> {
                            BackToHealth { tab = MeTab.Health }
                            LabsScreen()
                        }
                        // What you do (practices, time, the day map, the commute) above the long view.
                        MeTab.Routine -> {
                            RoutineTab(onOpenDay = onOpenDayMap)
                            RoutinePage(onOpenDay = onOpenDayMap)
                        }
                        MeTab.Exposures -> {
                            BackToHealth { tab = MeTab.Health }
                            ExposuresPage()
                        }
                        MeTab.Healthspan, MeTab.Intimacy -> Unit
                    }
                }
            }
        }
        }
    }
}

/** Health's fold of the screens that are about risk and prevention, not about what is wrong now (A11: five tabs, nothing deleted). */
@Composable
private fun PreventionAndRisk(onOpen: (MeTab) -> Unit) {
    Section("PREVENTION AND RISK") {
        LinkRow("BLOODWORK", "Lab draws and markers") { onOpen(MeTab.Bloodwork) }
        LinkRow("EXPOSURES", "Air, where the dose comes from, ways to improve") { onOpen(MeTab.Exposures) }
        LinkRow("INTIMACY", "Stats, trends, STI tests, partners") { onOpen(MeTab.Intimacy) }
    }
}

@Composable
private fun BackToHealth(onBack: () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { LinkRow("‹ HEALTH", onClick = onBack) }
}
