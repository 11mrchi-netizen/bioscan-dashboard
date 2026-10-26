package com.bioscan.fieldterminal.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.AgingProfileOverview
import com.bioscan.fieldterminal.data.HealthspanBundle
import com.bioscan.fieldterminal.data.HealthspanRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.TrainingCyclesRepository
import com.bioscan.fieldterminal.domain.DataAvailability
import com.bioscan.fieldterminal.domain.TrainingCycle
import com.bioscan.fieldterminal.domain.levels.SkillLevel
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTDataState
import com.bioscan.fieldterminal.ui.components.LoadErrorCard
import com.bioscan.fieldterminal.ui.components.ScreenHeader
import com.bioscan.fieldterminal.ui.components.rememberLoadGuard
import com.bioscan.fieldterminal.ui.screens.training.currentBlockProgress
import com.bioscan.fieldterminal.ui.screens.training.currentBlockTitle
import com.bioscan.fieldterminal.ui.screens.user.Skill
import com.bioscan.fieldterminal.ui.screens.user.SkillNavTiles
import com.bioscan.fieldterminal.ui.screens.user.UserDataCache
import com.bioscan.fieldterminal.ui.screens.user.conditioningLevelOf
import com.bioscan.fieldterminal.ui.screens.user.runningLevelOf
import com.bioscan.fieldterminal.ui.screens.user.strengthLevelOf
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import kotlinx.coroutines.launch
import java.time.LocalDate

// User tab: Healthspan (radar over the scored factors), Aging, the three skill pages (Running / Strength / Conditioning) as buttons
// under Aging, then the training block. Skill levels are computed from real history (see domain/levels/SkillLevel.kt); nothing on
// this page is mock data.
@Composable
fun UserProfileScreen(
    onOpenAging: () -> Unit = {},
    // UX review P5: opens Training › Plan, the one home of the training block.
    onOpenTrainingPlan: () -> Unit = {},
    onOpenSkill: (Skill) -> Unit = {},
) {
    var agingOverview by remember { mutableStateOf<AgingProfileOverview?>(null) }
    val agingGuard = rememberLoadGuard()
    val cyclesGuard = rememberLoadGuard()
    LaunchedEffect(agingGuard.attempt) {
        agingGuard.run { agingOverview = UserDataCache.aging() }
    }

    // Analysis Layer 3 / Phase 8: the Healthspan score (loaded once aging is known).
    var healthspan by remember { mutableStateOf<HealthspanBundle?>(null) }
    var healthspanFailed by remember { mutableStateOf(false) }
    LaunchedEffect(agingOverview) {
        val overview = agingOverview ?: return@LaunchedEffect
        healthspanFailed = false
        healthspan = runCatching { HealthspanRepository(SupabaseClientProvider.client).load(overview) }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it else healthspanFailed = true }
            .getOrNull()
    }

    // Skill levels load independently and fill in as they arrive; one failing source never blanks the page.
    var runningLevel by remember { mutableStateOf<SkillLevel?>(null) }
    var strengthLevel by remember { mutableStateOf<SkillLevel?>(null) }
    var conditioningLevel by remember { mutableStateOf<SkillLevel?>(null) }
    LaunchedEffect(Unit) {
        launch { runningLevel = loadLevel { runningLevelOf(UserDataCache.running(), UserDataCache.vo2Percentile()) } }
        launch { strengthLevel = loadLevel { strengthLevelOf(UserDataCache.strength()) } }
        launch { conditioningLevel = loadLevel { conditioningLevelOf(UserDataCache.conditioning()) } }
    }

    var cycles by remember { mutableStateOf<List<TrainingCycle>?>(null) }
    LaunchedEffect(cyclesGuard.attempt) {
        cyclesGuard.run { cycles = TrainingCyclesRepository(SupabaseClientProvider.client).loadCycles() }
    }
    val activeCycle = cycles?.firstOrNull { it.isActiveOn(LocalDate.now()) }

    Column(modifier = Modifier.fillMaxSize().background(FT.Base)) {
        ScreenHeader(title = "USER", context = "HEALTHSPAN · SKILLS · TRAINING BLOCK")
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // Either page load failing shows one error card; RETRY re-runs both.
            listOf(agingGuard, cyclesGuard).firstOrNull { it.error != null }?.let { failed ->
                LoadErrorCard(failed, onRetry = { agingGuard.retry(); cyclesGuard.retry() })
            }

            HealthspanCard(healthspan?.summary, healthspanFailed)

            AgingCard(agingOverview, onOpenAging)

            SkillNavTiles(runningLevel, strengthLevel, conditioningLevel, onOpenSkill)

            TrainingBlockLinkCard(activeCycle, loaded = cycles != null, onOpen = onOpenTrainingPlan)
        }
    }
}

// A level that failed to load reads as "couldn't load", never as a missing level; cancellation (leaving the screen) is not a failure.
private suspend fun loadLevel(compute: suspend () -> SkillLevel): SkillLevel =
    try {
        compute()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        SkillLevel(
            label = "Not enough data yet", position = null, basis = null, provisional = false, headline = null,
            reason = "Couldn't load history — check connection", confidence = com.bioscan.fieldterminal.domain.Confidence(0, 1),
        )
    }

// ---- Section 1: Aging Card ----

@Composable
private fun AgingCard(overview: AgingProfileOverview?, onOpen: () -> Unit) {
    FTCard(
        title = "AGING",
        onClick = onOpen,
    ) {
        when {
            overview == null -> CircularProgressIndicator(color = FT.Emerald)
            overview.chronologicalAgeYears == null -> FTDataState(DataAvailability.Unavailable, "Set your date of birth in Setup › Profile & goals to see this.")
            else -> {
                BioAgeHero(overview)
                Text(
                    "CHRONOLOGICAL ${overview.chronologicalAgeYears} YRS · TAP FOR THE FULL BREAKDOWN",
                    style = FTType.Micro,
                    color = FT.TextMuted,
                )
            }
        }
    }
}

// UX review P5: the block itself lives in Training › Plan (current block, plan, history); the User tab keeps this compact
// card, which opens it, so what you trained stays next to the levels it produced.
@Composable
private fun TrainingBlockLinkCard(cycle: TrainingCycle?, loaded: Boolean, onOpen: () -> Unit) {
    FTCard(title = "TRAINING BLOCK", onClick = onOpen) {
        when {
            !loaded -> FTDataState(DataAvailability.Building, "Loading block…")
            cycle == null -> Text("No active block.", style = FTType.BodySmall, color = FT.TextSecondary)
            else -> {
                Text(currentBlockTitle(cycle), style = FTType.RowTitle, color = FT.TextPrimary)
                Text(currentBlockProgress(cycle), style = FTType.MonoCaption, color = FT.TextMuted)
            }
        }
        Text("OPEN TRAINING › PLAN ›", style = FTType.LabelCaps, color = FT.EmeraldHighlight)
    }
}
