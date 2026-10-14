package com.bioscan.fieldterminal.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.AgingProfileOverview
import com.bioscan.fieldterminal.data.NutritionGoalsStore
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.TrainingCyclesRepository
import com.bioscan.fieldterminal.data.model.TrainingBlockSummaryRow
import com.bioscan.fieldterminal.domain.DataAvailability
import com.bioscan.fieldterminal.domain.DisplayValue
import com.bioscan.fieldterminal.domain.FocusQuality
import com.bioscan.fieldterminal.domain.FocusRole
import com.bioscan.fieldterminal.domain.PersonalRange
import com.bioscan.fieldterminal.domain.RangeComparison
import com.bioscan.fieldterminal.domain.RangeKind
import com.bioscan.fieldterminal.domain.TrainingCycle
import com.bioscan.fieldterminal.domain.healthspan.HealthspanAxis
import com.bioscan.fieldterminal.domain.healthspan.HealthspanAxisInput
import com.bioscan.fieldterminal.domain.healthspan.computeHealthspan
import com.bioscan.fieldterminal.domain.levels.SkillLevel
import com.bioscan.fieldterminal.domain.progressFraction
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTDataState
import com.bioscan.fieldterminal.ui.components.FTMetricValue
import com.bioscan.fieldterminal.ui.components.FTRangeIndicator
import com.bioscan.fieldterminal.ui.components.RangeBar
import com.bioscan.fieldterminal.ui.components.ScreenHeader
import com.bioscan.fieldterminal.ui.screens.user.HealthspanCard
import com.bioscan.fieldterminal.ui.screens.user.Skill
import com.bioscan.fieldterminal.ui.screens.user.SkillNavTiles
import com.bioscan.fieldterminal.ui.screens.user.UserDataCache
import com.bioscan.fieldterminal.ui.screens.user.conditioningLevelOf
import com.bioscan.fieldterminal.ui.screens.user.runningLevelOf
import com.bioscan.fieldterminal.ui.screens.user.strengthLevelOf
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.launch

// User tab: Healthspan heptagon, Aging, the three skill pages (Running / Strength / Conditioning) as buttons
// under Aging, then the training block. Skill levels are computed from real history (see
// domain/levels/SkillLevel.kt); nothing on this page is mock data.
@Composable
fun UserProfileScreen(
    onOpenAging: () -> Unit = {},
    onOpenTrainingBlocks: () -> Unit = {},
    onOpenSkill: (Skill) -> Unit = {},
) {
    val context = LocalContext.current
    var agingOverview by remember { mutableStateOf<AgingProfileOverview?>(null) }
    var axisInputs by remember { mutableStateOf<Map<HealthspanAxis, HealthspanAxisInput>>(emptyMap()) }
    var healthspanLoading by remember { mutableStateOf(true) }
    var runningLevel by remember { mutableStateOf<SkillLevel?>(null) }
    var strengthLevel by remember { mutableStateOf<SkillLevel?>(null) }
    var conditioningLevel by remember { mutableStateOf<SkillLevel?>(null) }

    // Each block loads independently and fills in as it arrives; one failing source never blanks the page.
    LaunchedEffect(Unit) {
        launch { agingOverview = runCatching { UserDataCache.aging() }.getOrNull() }
        launch {
            val calorieGoal = NutritionGoalsStore.getGoals(context).caloriesKcal
            runCatching { UserDataCache.loadHealthspan(calorieGoal) { axisInputs = axisInputs + (it.axis to it) } }
            healthspanLoading = false
        }
        launch {
            val cardioAge = runCatching { UserDataCache.aging() }.getOrNull()?.cardioAge
            runningLevel = runCatching { runningLevelOf(UserDataCache.running(), cardioAge) }.getOrElse { unavailableLevel() }
        }
        launch { strengthLevel = runCatching { strengthLevelOf(UserDataCache.strength()) }.getOrElse { unavailableLevel() } }
        launch { conditioningLevel = runCatching { conditioningLevelOf(UserDataCache.conditioning()) }.getOrElse { unavailableLevel() } }
    }

    var cycles by remember { mutableStateOf<List<TrainingCycle>?>(null) }
    var blockSummaries by remember { mutableStateOf<List<TrainingBlockSummaryRow>?>(null) }
    val cyclesRepo = remember { TrainingCyclesRepository(SupabaseClientProvider.client) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        cycles = cyclesRepo.loadCycles()
        blockSummaries = cyclesRepo.loadBlockSummaries()
    }
    val activeCycle = cycles?.firstOrNull { it.isActiveOn(LocalDate.now()) }

    var editingCycle by remember { mutableStateOf<TrainingCycle?>(null) }
    var addingCycle by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(FT.Base)) {
        ScreenHeader(title = "USER", context = "HEALTHSPAN · SKILLS · TRAINING BLOCK")
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            HealthspanCard(computeHealthspan(axisInputs.values.toList()), loading = healthspanLoading)

            AgingCard(agingOverview, onOpenAging)

            SkillNavTiles(runningLevel, strengthLevel, conditioningLevel, onOpenSkill)

            TrainingBlockCard(
                cycle = activeCycle,
                currentValue = null,
                onEdit = { editingCycle = activeCycle },
                onAdd = { addingCycle = true },
            )
            TrainingBlockHistoryCard(blockSummaries, onOpenTrainingBlocks)
        }
    }

    if (addingCycle || editingCycle != null) {
        TrainingBlockFormSheet(
            cycle = editingCycle,
            onDismiss = { addingCycle = false; editingCycle = null },
            onSaved = {
                addingCycle = false
                editingCycle = null
                scope.launch {
                    cycles = cyclesRepo.loadCycles()
                    blockSummaries = cyclesRepo.loadBlockSummaries()
                }
            },
        )
    }
}

private fun unavailableLevel() = SkillLevel(
    label = "Not enough data yet", position = null, basis = null, provisional = false, headline = null,
    reason = "Couldn't load history — check connection", confidence = com.bioscan.fieldterminal.domain.Confidence(0, 1),
)

// ---- Section 1: Aging Card ----

@Composable
private fun AgingCard(overview: AgingProfileOverview?, onOpen: () -> Unit) {
    FTCard(
        title = "AGING",
        modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onOpen),
    ) {
        when {
            overview == null -> CircularProgressIndicator(color = FT.Emerald)
            overview.chronologicalAgeYears == null -> FTDataState(DataAvailability.Unavailable, "Set your date of birth in Setup › Profile to see this.")
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

// ---- Section 4: Current Training Block ----

@Composable
private fun TrainingBlockCard(cycle: TrainingCycle?, currentValue: Double?, onEdit: () -> Unit, onAdd: () -> Unit) {
    FTCard(title = "CURRENT TRAINING BLOCK") {
        if (cycle == null) {
            FTDataState(DataAvailability.Unavailable, "No active training block set.")
            EditLink("+ ADD TRAINING BLOCK", onAdd)
            return@FTCard
        }
        val blockTitle = cycle.tbTemplate
            ?: cycle.focus.joinToString(" + ") { it.quality.label() }.ifBlank { "No stated focus" }
        val blockSubtitle = listOfNotNull(cycle.tbPrimary, cycle.tbSecondary)
            .joinToString(" · ") { it.toFocusLabel() }
            .ifBlank { null }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(
                    blockTitle,
                    style = FTType.RowTitle,
                    color = FT.TextPrimary,
                )
                blockSubtitle?.let {
                    Text(it, style = FTType.MonoCaption, color = FT.TextMuted)
                }
            }
            EditLink("EDIT", onEdit)
        }
        Text(
            "${cycle.startDate} — ${cycle.endDate ?: "ongoing"}",
            style = FTType.MonoCaption,
            color = FT.TextMuted,
        )

        // Temporal progress bar
        cycle.endDate?.let { end ->
            val totalDays = ChronoUnit.DAYS.between(cycle.startDate, end).toDouble()
            val elapsedDays = ChronoUnit.DAYS.between(cycle.startDate, LocalDate.now()).toDouble().coerceIn(0.0, totalDays)
            if (totalDays > 0) {
                RangeBar(
                    value = elapsedDays,
                    max = totalDays,
                    watchBelow = null,
                    color = FT.Emerald,
                )
                val weeksElapsed = (elapsedDays / 7).toInt() + 1
                val totalWeeks = (totalDays / 7).toInt()
                val remaining = (totalDays - elapsedDays).toInt()
                Text(
                    "Week $weeksElapsed of $totalWeeks — $remaining days remaining",
                    style = FTType.MonoCaption,
                    color = FT.TextMuted,
                )
            }
        }

        // Multi-focus weight visualization
        if (cycle.focus.size > 1) {
            Spacer(Modifier.height(4.dp))
            cycle.focus.forEach { entry ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        entry.quality.label(),
                        style = FTType.BodySmall,
                        color = FT.TextSecondary,
                        modifier = Modifier.weight(0.45f),
                    )
                    Text(
                        "${(entry.weight * 100).toInt()}%",
                        style = FTType.Label,
                        color = FT.TextPrimary,
                        modifier = Modifier.width(40.dp),
                        textAlign = TextAlign.End,
                    )
                }
                RangeBar(
                    value = entry.weight,
                    max = 1.0,
                    watchBelow = null,
                    color = if (entry.role == FocusRole.Primary) FT.Emerald else FT.Info,
                    height = 6.dp,
                    topPadding = 2.dp,
                )
            }
        }

        if (cycle.goalMetric != null && cycle.startingValue != null && cycle.targetValue != null) {
            FTMetricValue(
                DisplayValue(
                    primary = currentValue?.let { "%.0f".format(it) } ?: "—",
                    unit = cycle.goalMetric,
                    secondary = "target %.0f (from %.0f)".format(cycle.targetValue, cycle.startingValue),
                ),
            )
            val fraction = progressFraction(cycle.startingValue, cycle.targetValue, currentValue)
            if (fraction != null && currentValue != null) {
                FTRangeIndicator(
                    PersonalRange(
                        kind = RangeKind.TargetRange,
                        lower = minOf(cycle.startingValue, cycle.targetValue),
                        upper = maxOf(cycle.startingValue, cycle.targetValue),
                        current = currentValue,
                        label = "PROGRESS — ${(fraction * 100).toInt()}%",
                        comparison = RangeComparison.Within,
                        sufficientHistory = true,
                    ),
                )
            }
        } else {
            Text(
                "No numeric goal set for this block.",
                style = FTType.Caption,
                color = FT.TextSecondary,
            )
        }
    }
}

// ---- Section 5: Training Block History ----

@Composable
private fun TrainingBlockHistoryCard(
    summaries: List<TrainingBlockSummaryRow>?,
    onViewAll: () -> Unit = {},
) {
    FTCard(title = "TRAINING BLOCK HISTORY") {
        if (summaries == null) {
            FTDataState(DataAvailability.Building, "Loading blocks…")
            return@FTCard
        }
        if (summaries.isEmpty()) {
            FTDataState(DataAvailability.Unavailable, "No past training blocks yet.")
            return@FTCard
        }
        summaries.take(3).forEach { block ->
            val templateLabel = (block.template ?: "")
                .replace(Regex("\\s*\\(\\d+\\s+Weeks?\\)", RegexOption.IGNORE_CASE), "")
                .trim()
            val weeks = block.blockDays?.let { "${it / 7}W" } ?: ""
            val dateRange = buildBlockDateRange(block.startDate, block.endDate)
            val sessions = buildString {
                append("${block.strengthSessions} STR")
                append("  ${block.conditioningSessions} COND")
                if (block.enduranceSessions > 0) append("  ${block.enduranceSessions} END")
            }
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        templateLabel,
                        style = FTType.RowTitle,
                        color = FT.TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    if (weeks.isNotEmpty()) {
                        Text(
                            weeks,
                            style = FTType.Label,
                            color = FT.TextSecondary,
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        dateRange,
                        style = FTType.MonoCaption,
                        color = FT.TextMuted,
                    )
                    Text(
                        sessions,
                        style = FTType.MonoCaption,
                        color = FT.TextSecondary,
                    )
                }
            }
        }
        EditLink("VIEW ALL BLOCKS →", onViewAll)
    }
}

private fun buildBlockDateRange(start: String, end: String): String {
    val s = runCatching { java.time.LocalDate.parse(start) }.getOrNull() ?: return start
    val e = runCatching { java.time.LocalDate.parse(end) }.getOrNull() ?: return end
    val fmt = java.time.format.DateTimeFormatter.ofPattern("MMM d")
    return if (s.year == e.year)
        "${s.format(fmt)} — ${e.format(fmt)}, ${s.year}"
    else
        "${s.format(fmt)}, ${s.year} — ${e.format(fmt)}, ${e.year}"
}

// ---- Helper functions ----

private fun FocusQuality.label(): String = name.replace(Regex("(?<=.)(?=\\p{Upper})"), " ")

private fun String.toFocusLabel(): String =
    split("_").joinToString(" ") { it.replaceFirstChar { c -> c.uppercaseChar() } }

@Composable
private fun EditLink(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = FTType.Label,
        color = FT.Emerald,
        modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
    )
}
