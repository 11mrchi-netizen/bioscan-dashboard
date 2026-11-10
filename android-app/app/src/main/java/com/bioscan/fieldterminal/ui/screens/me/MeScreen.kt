package com.bioscan.fieldterminal.ui.screens.me

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.ui.screens.user.Skill
import com.bioscan.fieldterminal.ui.components.SubTabRow
import com.bioscan.fieldterminal.ui.components.ftPlatinumWash
import com.bioscan.fieldterminal.ui.components.TileHeader
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.nav.MeTab
import com.bioscan.fieldterminal.ui.screens.UserProfileScreen
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// Scoring system rebuild (docs/scoring-system/02-page-mockups.md §8): ME, a dock tab. The slow-moving picture:
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
    onOpenSport: (com.bioscan.fieldterminal.domain.Sport) -> Unit = {},
    requestedTab: MeTab? = null,
    onRequestedTabConsumed: () -> Unit = {},
    onOpenDayMap: (java.time.LocalDate) -> Unit = {},
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
                MeTab.Health -> "INJURIES · PAIN · SYMPTOMS · ILLNESS"
                MeTab.Bloodwork -> "LAB DRAWS · MARKERS"
                MeTab.Routine -> "THE DAYS · TIME · PLACES · PHONE · PRACTICES"
            },
            accent = FT.DomainUser,
            action = {
                Text("⚙", style = FTType.SectionTitle, color = FT.TextSecondary,
                    modifier = Modifier.ftClickable(role = Role.Button, onClickLabel = "Settings") { onOpenSettings() }.padding(8.dp))
            },
        )
        SubTabRow(items = MeTab.entries, selected = tab, label = { it.label }, onSelect = { tab = it })
        Box(Modifier.fillMaxWidth().height(FT.BorderWidth).background(FT.GlassBorder))
        Box(Modifier.fillMaxWidth().weight(1f)) {
            when (tab) {
                // The healthspan page scrolls itself.
                MeTab.Healthspan -> UserProfileScreen(
                    onOpenAging = onOpenAging,
                    onOpenTrainingPlan = onOpenTrainingPlan,
                    onOpenSkill = { skill ->
                        when (skill) {
                            Skill.RUNNING -> onOpenSport(com.bioscan.fieldterminal.domain.sportOf("run", null))
                            Skill.STRENGTH -> onOpenSport(com.bioscan.fieldterminal.domain.sportOf("strength", null))
                            Skill.CONDITIONING -> onOpenConditioning()
                        }
                    },
                    showHeader = false,
                )
                else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    when (tab) {
                        MeTab.Performance -> Column(Modifier.padding(16.dp)) {
                            com.bioscan.fieldterminal.ui.screens.performance.PerformanceHub(onOpenSport)
                        }
                        MeTab.Body -> com.bioscan.fieldterminal.ui.screens.status.BodyTabContent()
                        MeTab.Health -> com.bioscan.fieldterminal.ui.screens.health.ConcernsTab(onOpenConcern = onOpenConcern)
                        MeTab.Bloodwork -> com.bioscan.fieldterminal.ui.screens.status.LabsScreen()
                        MeTab.Routine -> RoutinePage(onOpenDay = onOpenDayMap)
                        MeTab.Healthspan -> Unit
                    }
                }
            }
        }
    }
}
