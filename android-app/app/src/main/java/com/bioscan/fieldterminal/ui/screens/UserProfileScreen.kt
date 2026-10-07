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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.AgingProfileOverview
import com.bioscan.fieldterminal.data.AgingProfileRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.TrainingCyclesRepository
import com.bioscan.fieldterminal.domain.Confidence
import com.bioscan.fieldterminal.domain.ConfidenceLevel
import com.bioscan.fieldterminal.domain.DataAvailability
import com.bioscan.fieldterminal.domain.DisplayValue
import com.bioscan.fieldterminal.domain.FocusEntry
import com.bioscan.fieldterminal.domain.FocusQuality
import com.bioscan.fieldterminal.domain.FocusRole
import com.bioscan.fieldterminal.domain.MetricState
import com.bioscan.fieldterminal.domain.PersonalRange
import com.bioscan.fieldterminal.domain.RangeComparison
import com.bioscan.fieldterminal.domain.RangeKind
import com.bioscan.fieldterminal.domain.TrainingCycle
import com.bioscan.fieldterminal.domain.achievement.Achievement
import com.bioscan.fieldterminal.domain.achievement.AchievementDomain
import com.bioscan.fieldterminal.domain.aging.BiologicalAgeResult
import com.bioscan.fieldterminal.domain.analysis.ComparisonResult
import com.bioscan.fieldterminal.domain.analysis.ComparisonState
import com.bioscan.fieldterminal.domain.analysis.ComparisonType
import com.bioscan.fieldterminal.domain.analysis.Directionality
import com.bioscan.fieldterminal.domain.analysis.InputCompleteness
import com.bioscan.fieldterminal.domain.analysis.Provenance
import com.bioscan.fieldterminal.domain.comparison.PercentileBand
import com.bioscan.fieldterminal.domain.levels.DomainLevel
import com.bioscan.fieldterminal.domain.levels.conditioningLevel
import com.bioscan.fieldterminal.domain.levels.mountainLevel
import com.bioscan.fieldterminal.domain.levels.runningLevel
import com.bioscan.fieldterminal.domain.levels.strengthLevel
import com.bioscan.fieldterminal.domain.progressFraction
import com.bioscan.fieldterminal.ui.components.ComparisonStrip
import com.bioscan.fieldterminal.ui.components.ageAccelerationState
import com.bioscan.fieldterminal.ui.components.confidenceLevel
import com.bioscan.fieldterminal.ui.components.percentileBandState
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTConfidenceChip
import com.bioscan.fieldterminal.ui.components.FTDataState
import com.bioscan.fieldterminal.ui.components.FTMetricValue
import com.bioscan.fieldterminal.ui.components.FTRangeIndicator
import com.bioscan.fieldterminal.ui.components.FTStatePill
import com.bioscan.fieldterminal.ui.components.RangeBar
import com.bioscan.fieldterminal.ui.components.ScreenHeader
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.launch

data class UserProfileData(
    val domainLevels: Map<AchievementDomain, DomainLevel>,
    val achievements: Map<AchievementDomain, List<Achievement>>,
    val activeCycle: TrainingCycle?,
    val activeCycleCurrentValue: Double?,
    val cycleHistory: List<TrainingCycle>,
)

@Composable
fun UserProfileScreen(data: UserProfileData? = null, onOpenAging: () -> Unit = {}) {
    val profile = data ?: remember { mockUserProfileData() }

    var agingOverview by remember { mutableStateOf<AgingProfileOverview?>(null) }
    LaunchedEffect(Unit) {
        agingOverview = AgingProfileRepository(SupabaseClientProvider.client).loadOverview()
    }

    var cycles by remember { mutableStateOf<List<TrainingCycle>?>(null) }
    val cyclesRepo = remember { TrainingCyclesRepository(SupabaseClientProvider.client) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { cycles = cyclesRepo.loadCycles() }
    val activeCycle = cycles?.firstOrNull { it.isActiveOn(LocalDate.now()) }
    val cycleHistory = cycles.orEmpty().filter { it.id != activeCycle?.id }

    var editingCycle by remember { mutableStateOf<TrainingCycle?>(null) }
    var addingCycle by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(FT.Base)) {
        ScreenHeader(title = "USER", context = "LEVELS · ACHIEVEMENTS · TRAINING BLOCK")
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            AgingCard(agingOverview, onOpenAging)

            Text(
                "Domain levels and achievements below are sample data — real ones arrive once the historical " +
                    "archive import lands. The training block below is already real.",
                style = FTType.Caption,
                color = FT.TextMuted,
            )

            FTCard(title = "DOMAIN LEVELS") {
                AchievementDomain.entries.forEach { domain ->
                    DomainLevelRow(domain, profile.domainLevels[domain])
                }
            }

            AchievementDomain.entries.forEach { domain ->
                AchievementSection(domain, profile.achievements[domain].orEmpty())
            }

            TrainingBlockCard(
                cycle = activeCycle,
                currentValue = null,
                onEdit = { editingCycle = activeCycle },
                onAdd = { addingCycle = true },
            )
            TrainingBlockHistoryCard(cycleHistory)
        }
    }

    if (addingCycle || editingCycle != null) {
        TrainingBlockFormSheet(
            cycle = editingCycle,
            onDismiss = { addingCycle = false; editingCycle = null },
            onSaved = {
                addingCycle = false
                editingCycle = null
                scope.launch { cycles = cyclesRepo.loadCycles() }
            },
        )
    }
}

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

// ---- Section 2: Domain Levels ----

@Composable
private fun DomainLevelRow(domain: AchievementDomain, level: DomainLevel?) {
    Column(modifier = Modifier.padding(vertical = 6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(domain.label(), style = FTType.Value, color = FT.TextSecondary)
            if (level?.band != null) {
                FTStatePill(percentileBandState(level.band))
            } else {
                Text(
                    "Not enough data yet",
                    style = FTType.RowTitle,
                    color = FT.TextMuted,
                    textAlign = TextAlign.End,
                )
            }
        }

        level?.evidence?.let { evidence ->
            evidence.percentile?.let { percentile ->
                RangeBar(
                    value = percentile,
                    max = 100.0,
                    watchBelow = null,
                    color = bandToColor(level.band),
                )
            }
            ComparisonStrip(population = evidence)
            FTConfidenceChip(confidenceLevel(evidence.confidence))
        }
    }
}

// ---- Section 3: Achievements ----

@Composable
private fun AchievementSection(domain: AchievementDomain, achievements: List<Achievement>) {
    FTCard(title = "${domain.label()} ACHIEVEMENTS") {
        if (achievements.isEmpty()) {
            FTDataState(DataAvailability.Unavailable, "No ${domain.label().lowercase()} achievements yet.")
            return@FTCard
        }
        achievements.forEach { achievement ->
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    achievement.metricLabel(),
                    style = FTType.RowTitle,
                    color = FT.TextSecondary,
                )
                Spacer(Modifier.height(4.dp))
                FTMetricValue(achievement.toDisplayValue())

                achievement.comparisonContext?.let {
                    Text(
                        it,
                        style = FTType.Caption,
                        color = FT.Emerald,
                    )
                }
                achievement.confidence?.let { conf ->
                    Spacer(Modifier.height(4.dp))
                    FTConfidenceChip(confidenceLevel(conf))
                }
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
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                cycle.focus.joinToString(" + ") { it.quality.label() }.ifBlank { "No stated focus" },
                style = FTType.RowTitle,
                color = FT.TextPrimary,
            )
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
private fun TrainingBlockHistoryCard(history: List<TrainingCycle>) {
    FTCard(title = "TRAINING BLOCK HISTORY") {
        if (history.isEmpty()) {
            FTDataState(DataAvailability.Unavailable, "No past training blocks yet.")
            return@FTCard
        }
        val maxDuration = history.mapNotNull { cycle ->
            cycle.endDate?.let { ChronoUnit.DAYS.between(cycle.startDate, it).toDouble() }
        }.maxOrNull() ?: 1.0

        history.forEach { cycle ->
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    cycle.focus.joinToString(" + ") { it.quality.label() },
                    style = FTType.RowTitle,
                    color = FT.TextSecondary,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        "${cycle.startDate} — ${cycle.endDate}",
                        style = FTType.MonoCaption,
                        color = FT.TextMuted,
                    )
                    cycle.endDate?.let { end ->
                        val weeks = ChronoUnit.WEEKS.between(cycle.startDate, end)
                        Text(
                            "${weeks}w",
                            style = FTType.Label,
                            color = FT.TextPrimary,
                        )
                    }
                }

                // Proportional duration bar
                cycle.endDate?.let { end ->
                    val duration = ChronoUnit.DAYS.between(cycle.startDate, end).toDouble()
                    RangeBar(
                        value = duration,
                        max = maxDuration,
                        watchBelow = null,
                        color = FT.DomainTraining,
                        height = 5.dp,
                        topPadding = 4.dp,
                    )
                }

                // Goal summary when available
                if (cycle.goalMetric != null && cycle.startingValue != null && cycle.targetValue != null) {
                    Text(
                        "Goal: ${cycle.goalMetric} — %.0f → %.0f".format(cycle.startingValue, cycle.targetValue),
                        style = FTType.MonoCaption,
                        color = FT.TextMuted,
                    )
                }
            }
        }
    }
}

// ---- Helper functions ----

// Bar fill follows the same state as the pill beside it (percentileBandState):
// Optimal = emerald, Neutral = cool neutral, Building = analysis violet.
private fun bandToColor(band: PercentileBand?): Color = when (percentileBandState(band)) {
    MetricState.Optimal -> FT.Emerald
    MetricState.Neutral -> FT.TextSecondary
    MetricState.Building -> FT.Analysis
    else -> FT.TextMuted
}

private fun Achievement.toDisplayValue(): DisplayValue {
    val (primary, unit) = when (this.unit) {
        "sec" -> value.toLong().let { "%d:%02d".format(it / 60, it % 60) } to null
        else -> formatAchievementValue(this) to this.unit
    }
    return DisplayValue(
        primary = primary,
        unit = unit,
        secondary = occurredAt.toLocalDate().toString(),
    )
}

private fun AchievementDomain.label(): String = when (this) {
    AchievementDomain.RUNNING -> "Running"
    AchievementDomain.STRENGTH -> "Strength"
    AchievementDomain.CONDITIONING -> "Conditioning"
    AchievementDomain.MOUNTAIN -> "Mountain"
}

private fun FocusQuality.label(): String = name.replace(Regex("(?<=.)(?=\\p{Upper})"), " ")

private fun Achievement.metricLabel(): String = metric.replace('_', ' ').replaceFirstChar { it.uppercase() }

@Composable
private fun EditLink(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = FTType.Label,
        color = FT.Emerald,
        modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
    )
}

private fun formatAchievementValue(achievement: Achievement): String =
    if (achievement.value == achievement.value.toLong().toDouble()) achievement.value.toLong().toString()
    else "%.1f".format(achievement.value)

private fun Achievement.displayValue(): String = when (unit) {
    "sec" -> value.toLong().let { "%d:%02d".format(it / 60, it % 60) }
    else -> "${formatAchievementValue(this)} $unit"
}

// ---- Mock data ----

private fun mockUserProfileData(): UserProfileData {
    fun comparison(metric: String, percentile: Double, have: Int) = ComparisonResult(
        metric = metric, comparisonType = ComparisonType.POPULATION, state = ComparisonState.OK,
        rawValue = null, normalizedValue = null, referenceValue = null, delta = null, standardizedDelta = null,
        percentile = percentile, rank = null, rankDenominator = null, directionality = Directionality.HIGHER_BETTER,
        referenceIdentity = "population_v1", referenceVersion = "1", confidence = Confidence(have, 14),
        breadth = InputCompleteness(emptySet(), emptySet()), provenance = Provenance("wearable", "population_comparison", "1"),
    )

    val levels = mapOf(
        AchievementDomain.RUNNING to runningLevel(listOf(comparison("vo2max", 78.0, 18))),
        AchievementDomain.STRENGTH to strengthLevel(listOf(comparison("estimated_1rm_deadlift", 92.0, 20))),
        AchievementDomain.CONDITIONING to conditioningLevel(emptyList()),
        AchievementDomain.MOUNTAIN to mountainLevel(listOf(comparison("km_effort", 55.0, 9))),
    )

    fun achievement(domain: AchievementDomain, metric: String, value: Double, unit: String, daysAgo: Long, context: String? = null, confidence: Double? = null) = Achievement(
        domain = domain, metric = metric, activityRef = null, value = value, unit = unit,
        occurredAt = OffsetDateTime.now().minusDays(daysAgo),
        provenance = Provenance("zepp", null, null), comparisonContext = context, confidence = confidence,
    )

    val achievements = mapOf(
        AchievementDomain.RUNNING to listOf(
            achievement(AchievementDomain.RUNNING, "fastest_1km", 232.0, "sec", 14, "Top 10% of last 90 days"),
            achievement(AchievementDomain.RUNNING, "longest_run_km", 32.4, "km", 40),
        ),
        AchievementDomain.STRENGTH to listOf(
            achievement(AchievementDomain.STRENGTH, "heaviest_squat_kg", 100.0, "kg", 21),
            achievement(AchievementDomain.STRENGTH, "best_estimated_1rm_deadlift_kg", 140.0, "kg", 7, "Epley estimate", confidence = 0.75),
        ),
        AchievementDomain.MOUNTAIN to listOf(
            achievement(AchievementDomain.MOUNTAIN, "highest_single_run_gain_m", 1850.0, "m", 60),
        ),
    )

    val activeCycle = TrainingCycle(
        startDate = LocalDate.now().minusWeeks(4),
        endDate = LocalDate.now().plusWeeks(4),
        focus = listOf(FocusEntry(FocusQuality.RaceSpecificEndurance, 1.0, FocusRole.Primary)),
        goalMetric = "5k_time_sec",
        startingValue = 1500.0,
        targetValue = 1320.0,
    )
    val activeCycleCurrentValue = 1410.0

    val history = listOf(
        TrainingCycle(
            startDate = LocalDate.now().minusMonths(4), endDate = LocalDate.now().minusWeeks(4),
            focus = listOf(FocusEntry(FocusQuality.AerobicBase, 1.0, FocusRole.Primary)),
        ),
        TrainingCycle(
            startDate = LocalDate.now().minusMonths(7), endDate = LocalDate.now().minusMonths(4),
            focus = listOf(FocusEntry(FocusQuality.Hypertrophy, 0.7, FocusRole.Primary), FocusEntry(FocusQuality.AerobicBase, 0.3, FocusRole.Maintained)),
        ),
    )

    return UserProfileData(levels, achievements, activeCycle, activeCycleCurrentValue, history)
}
