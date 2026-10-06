package com.bioscan.fieldterminal.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bioscan.fieldterminal.data.AgingProfileOverview
import com.bioscan.fieldterminal.data.AgingProfileRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.TrainingCyclesRepository
import com.bioscan.fieldterminal.domain.Confidence
import com.bioscan.fieldterminal.domain.DataAvailability
import com.bioscan.fieldterminal.domain.DisplayValue
import com.bioscan.fieldterminal.domain.FocusEntry
import com.bioscan.fieldterminal.domain.FocusQuality
import com.bioscan.fieldterminal.domain.FocusRole
import com.bioscan.fieldterminal.domain.PersonalRange
import com.bioscan.fieldterminal.domain.RangeComparison
import com.bioscan.fieldterminal.domain.RangeKind
import com.bioscan.fieldterminal.domain.TrainingCycle
import com.bioscan.fieldterminal.domain.achievement.Achievement
import com.bioscan.fieldterminal.domain.achievement.AchievementDomain
import com.bioscan.fieldterminal.domain.analysis.ComparisonResult
import com.bioscan.fieldterminal.domain.analysis.ComparisonState
import com.bioscan.fieldterminal.domain.analysis.ComparisonType
import com.bioscan.fieldterminal.domain.analysis.Directionality
import com.bioscan.fieldterminal.domain.analysis.InputCompleteness
import com.bioscan.fieldterminal.domain.analysis.Provenance
import com.bioscan.fieldterminal.domain.levels.DomainLevel
import com.bioscan.fieldterminal.domain.levels.conditioningLevel
import com.bioscan.fieldterminal.domain.levels.mountainLevel
import com.bioscan.fieldterminal.domain.levels.runningLevel
import com.bioscan.fieldterminal.domain.levels.strengthLevel
import com.bioscan.fieldterminal.domain.progressFraction
import com.bioscan.fieldterminal.ui.components.ComparisonStrip
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTDataState
import com.bioscan.fieldterminal.ui.components.FTMetricValue
import com.bioscan.fieldterminal.ui.components.FTRangeIndicator
import com.bioscan.fieldterminal.ui.components.ScreenHeader
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import com.bioscan.fieldterminal.ui.theme.Inter
import com.bioscan.fieldterminal.ui.theme.RobotoMono
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.OffsetDateTime

// DAV-289 + DAV-296 (combined design/build pass, see
// docs/user-profile-milestone/01-canonical-contracts-audit.md decision 1):
// the User page replacing the former Map tab. Renders entirely from the
// canonical shapes Phase 5 defined (DomainLevel, Achievement, TrainingCycle)
// so the later archive milestone only has to replace mockUserProfileData()
// with a real repository call -- no UI rewrite. `data` defaults to the mock
// builder for exactly that reason: a future caller passes real data through
// this same parameter without touching anything below it.
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

    // 09A Aging Profile, Phase 1: real data (not mock) -- the rest of this
    // page stays mock until the historical archive milestone lands, but
    // Aging Profile has real inputs (lab_results, user_profile, VO2max)
    // today, so it's wired straight to AgingProfileRepository.
    var agingOverview by remember { mutableStateOf<AgingProfileOverview?>(null) }
    LaunchedEffect(Unit) {
        agingOverview = AgingProfileRepository(SupabaseClientProvider.client).loadOverview()
    }

    // User request: the training block slice also went real once
    // training_cycles started getting real rows (manual entry + a separate
    // Notion historical import) -- same "wire this one real input in
    // independently of the mock profile" precedent as Aging Profile above.
    // activeCycleCurrentValue stays null: no generic "look up this cycle's
    // goal_metric's live value" resolver exists yet, and goal_metric is a
    // free-text field with no fixed vocabulary today -- a real gap, not
    // silently faked, same as any other missing signal in this app.
    var cycles by remember { mutableStateOf<List<TrainingCycle>?>(null) }
    val cyclesRepo = remember { TrainingCyclesRepository(SupabaseClientProvider.client) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { cycles = cyclesRepo.loadCycles() }
    val activeCycle = cycles?.firstOrNull { it.isActiveOn(LocalDate.now()) }
    val cycleHistory = cycles.orEmpty().filter { it.id != activeCycle?.id }

    // null = sheet hidden; Unit-ish "editing null cycle" is ambiguous with
    // "hidden," so two flags instead of one nullable TrainingCycle?.
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
                style = TextStyle(fontFamily = Inter, fontSize = 12.5.sp),
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

// DAV-226/232 (09A Aging Profile, Phase 1): compact summary, same
// DomainLevelRow-style treatment as the card below it -- taps through to
// AgingProfileScreen for the full Overview/Dimensions/History/Explainability
// breakdown rather than crowding all of it onto this page.
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
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    AgingHeadline("Chronological", overview.chronologicalAgeYears.toString())
                    overview.phenoAge?.let { AgingHeadline("PhenoAge", it.biologicalAge?.let { v -> "%.0f".format(v) } ?: "—") }
                    overview.cardioAge?.let { AgingHeadline("Cardio Age", it.biologicalAge?.let { v -> "%.0f".format(v) } ?: "—") }
                }
                Text("Tap for the full breakdown", style = TextStyle(fontFamily = Inter, fontSize = 12.sp), color = FT.TextMuted)
            }
        }
    }
}

@Composable
private fun AgingHeadline(label: String, value: String) {
    Column {
        Text(label.uppercase(), style = TextStyle(fontFamily = RobotoMono, fontSize = 10.5.sp), color = FT.TextMuted)
        Text(value, style = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.Bold, fontSize = 20.sp), color = FT.TextPrimary)
    }
}

@Composable
private fun DomainLevelRow(domain: AchievementDomain, level: DomainLevel?) {
    Column {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(domain.label(), style = TextStyle(fontFamily = RobotoMono, fontSize = 12.5.sp), color = FT.TextSecondary)
            Text(
                level?.label ?: "Not enough data yet",
                style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
                color = if (level?.band != null) FT.TextPrimary else FT.TextMuted,
                textAlign = TextAlign.End,
            )
        }
        level?.evidence?.let { ComparisonStrip(population = it) }
    }
}

@Composable
private fun AchievementSection(domain: AchievementDomain, achievements: List<Achievement>) {
    FTCard(title = "${domain.label()} ACHIEVEMENTS") {
        if (achievements.isEmpty()) {
            FTDataState(DataAvailability.Unavailable, "No ${domain.label().lowercase()} achievements yet.")
            return@FTCard
        }
        achievements.forEach { achievement ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text(achievement.metricLabel(), style = TextStyle(fontFamily = Inter, fontSize = 14.sp), color = FT.TextSecondary)
                    achievement.comparisonContext?.let {
                        Text(it, style = TextStyle(fontFamily = Inter, fontSize = 11.5.sp), color = FT.TextMuted)
                    }
                }
                Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
                    Text(
                        achievement.displayValue(),
                        style = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.Medium, fontSize = 14.sp),
                        color = FT.TextPrimary,
                    )
                    Text(
                        achievement.occurredAt.toLocalDate().toString(),
                        style = TextStyle(fontFamily = RobotoMono, fontSize = 11.sp),
                        color = FT.TextMuted,
                    )
                }
            }
        }
    }
}

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
                style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
                color = FT.TextPrimary,
            )
            EditLink("EDIT", onEdit)
        }
        Text(
            "${cycle.startDate} — ${cycle.endDate ?: "ongoing"}",
            style = TextStyle(fontFamily = RobotoMono, fontSize = 12.sp),
            color = FT.TextMuted,
        )
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
                style = TextStyle(fontFamily = Inter, fontSize = 12.5.sp),
                color = FT.TextSecondary,
            )
        }
    }
}

@Composable
private fun TrainingBlockHistoryCard(history: List<TrainingCycle>) {
    FTCard(title = "TRAINING BLOCK HISTORY") {
        if (history.isEmpty()) {
            FTDataState(DataAvailability.Unavailable, "No past training blocks yet.")
            return@FTCard
        }
        history.forEach { cycle ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    cycle.focus.joinToString(" + ") { it.quality.label() },
                    style = TextStyle(fontFamily = Inter, fontSize = 13.5.sp),
                    color = FT.TextSecondary,
                )
                Text(
                    "${cycle.startDate} — ${cycle.endDate}",
                    style = TextStyle(fontFamily = RobotoMono, fontSize = 12.sp),
                    color = FT.TextMuted,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

private fun AchievementDomain.label(): String = when (this) {
    AchievementDomain.RUNNING -> "Running"
    AchievementDomain.STRENGTH -> "Strength"
    AchievementDomain.CONDITIONING -> "Conditioning"
    AchievementDomain.MOUNTAIN -> "Mountain"
}

private fun FocusQuality.label(): String = name.replace(Regex("(?<=.)(?=\\p{Upper})"), " ")

// e.g. "fastest_1km" -> "Fastest 1km".
private fun Achievement.metricLabel(): String = metric.replace('_', ' ').replaceFirstChar { it.uppercase() }

@Composable
private fun EditLink(label: String, onClick: () -> Unit) {
    Text(
        label,
        style = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp),
        color = FT.Emerald,
        modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick),
    )
}

private fun formatAchievementValue(achievement: Achievement): String =
    if (achievement.value == achievement.value.toLong().toDouble()) achievement.value.toLong().toString()
    else "%.1f".format(achievement.value)

// "sec" achievements (e.g. fastest_1km = 232.0) read as raw seconds ("232 sec")
// otherwise -- a real time deserves m:ss, same divmod TrainingScreen.kt's own
// formatPace() uses for pace, just keyed off whole seconds instead of a
// minutes-per-km double.
private fun Achievement.displayValue(): String = when (unit) {
    "sec" -> value.toLong().let { "%d:%02d".format(it / 60, it % 60) }
    else -> "${formatAchievementValue(this)} $unit"
}

// ---- Mock data (DAV-296): plausible, clearly-labeled example values in the
// exact shapes Phase 5 defined -- no lorem, no invented UI-only fields. ----

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

    fun achievement(domain: AchievementDomain, metric: String, value: Double, unit: String, daysAgo: Long, context: String? = null) = Achievement(
        domain = domain, metric = metric, activityRef = null, value = value, unit = unit,
        occurredAt = OffsetDateTime.now().minusDays(daysAgo),
        provenance = Provenance("zepp", null, null), comparisonContext = context,
    )

    val achievements = mapOf(
        AchievementDomain.RUNNING to listOf(
            achievement(AchievementDomain.RUNNING, "fastest_1km", 232.0, "sec", 14, "Top 10% of last 90 days"),
            achievement(AchievementDomain.RUNNING, "longest_run_km", 32.4, "km", 40),
        ),
        AchievementDomain.STRENGTH to listOf(
            achievement(AchievementDomain.STRENGTH, "heaviest_squat_kg", 100.0, "kg", 21),
            achievement(AchievementDomain.STRENGTH, "best_estimated_1rm_deadlift_kg", 140.0, "kg", 7, "Epley estimate"),
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
