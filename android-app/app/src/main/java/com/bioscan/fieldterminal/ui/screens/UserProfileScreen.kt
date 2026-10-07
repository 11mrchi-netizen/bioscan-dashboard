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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bioscan.fieldterminal.data.AchievementsRepository
import com.bioscan.fieldterminal.data.AgingProfileOverview
import com.bioscan.fieldterminal.data.AgingProfileRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.TrainingCyclesRepository
import com.bioscan.fieldterminal.data.model.TrainingBlockSummaryRow
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
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import com.bioscan.fieldterminal.ui.theme.Inter
import com.bioscan.fieldterminal.ui.theme.RobotoMono
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
fun UserProfileScreen(
    data: UserProfileData? = null,
    onOpenAging: () -> Unit = {},
    onOpenTrainingBlocks: () -> Unit = {},
) {
    val profile = data ?: remember { mockUserProfileData() }

    var agingOverview by remember { mutableStateOf<AgingProfileOverview?>(null) }
    LaunchedEffect(Unit) {
        agingOverview = AgingProfileRepository(SupabaseClientProvider.client).loadOverview()
    }

    var cycles by remember { mutableStateOf<List<TrainingCycle>?>(null) }
    var blockSummaries by remember { mutableStateOf<List<TrainingBlockSummaryRow>?>(null) }
    val cyclesRepo = remember { TrainingCyclesRepository(SupabaseClientProvider.client) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) {
        cycles = cyclesRepo.loadCycles()
        blockSummaries = cyclesRepo.loadBlockSummaries()
    }

    var realAchievements by remember { mutableStateOf<Map<AchievementDomain, List<Achievement>>?>(null) }
    var bodyWeightKg by remember { mutableStateOf<Double?>(null) }
    val achievementsRepo = remember { AchievementsRepository(SupabaseClientProvider.client) }
    LaunchedEffect(Unit) {
        val (strength, running) = achievementsRepo.loadAchievements()
        bodyWeightKg = achievementsRepo.loadBodyWeight()
        realAchievements = mapOf(
            AchievementDomain.STRENGTH to strength,
            AchievementDomain.RUNNING to running,
            AchievementDomain.CONDITIONING to emptyList(),
            AchievementDomain.MOUNTAIN to emptyList(),
        )
    }
    val activeCycle = cycles?.firstOrNull { it.isActiveOn(LocalDate.now()) }
    val cycleHistory = cycles.orEmpty().filter { it.id != activeCycle?.id }

    var editingCycle by remember { mutableStateOf<TrainingCycle?>(null) }
    var addingCycle by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(FT.Base)) {
        ScreenHeader(title = "USER", context = "LEVELS · ACHIEVEMENTS · TRAINING BLOCK")
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        ) {
            // -- Aging --
            AgingCard(agingOverview, onOpenAging)

            Spacer(Modifier.height(24.dp))

            // -- Domain Levels --
            FTCard(title = "DOMAIN LEVELS") {
                AchievementDomain.entries.forEach { domain ->
                    DomainLevelRow(domain, profile.domainLevels[domain])
                }
                Text(
                    if (realAchievements == null)
                        "Loading achievements from your training history…"
                    else
                        "Domain levels require population benchmarks and are not yet live.",
                    style = TextStyle(fontFamily = Inter, fontSize = 12.sp),
                    color = FT.TextMuted,
                )
            }

            Spacer(Modifier.height(24.dp))

            // -- Achievements (only domains that have data) --
            val achievements = realAchievements ?: profile.achievements
            val populatedDomains = AchievementDomain.entries.filter { achievements[it].orEmpty().isNotEmpty() }
            populatedDomains.forEachIndexed { i, domain ->
                if (domain == AchievementDomain.STRENGTH) {
                    StrengthAchievementSection(achievements[domain].orEmpty(), bodyWeightKg)
                } else {
                    AchievementSection(domain, achievements[domain].orEmpty())
                }
                if (i < populatedDomains.lastIndex) Spacer(Modifier.height(12.dp))
            }

            Spacer(Modifier.height(24.dp))

            // -- Training --
            TrainingBlockCard(
                cycle = activeCycle,
                currentValue = null,
                onEdit = { editingCycle = activeCycle },
                onAdd = { addingCycle = true },
            )
            Spacer(Modifier.height(12.dp))
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
                    style = TextStyle(fontFamily = RobotoMono, fontSize = 10.5.sp),
                    color = FT.TextMuted,
                )
            }
        }
    }
}

// ---- Section 2: Domain Levels ----

@Composable
private fun DomainLevelRow(domain: AchievementDomain, level: DomainLevel?) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(domain.label(), style = TextStyle(fontFamily = RobotoMono, fontSize = 12.5.sp), color = FT.TextSecondary)
            if (level?.band != null) {
                FTStatePill(percentileBandState(level.band))
            } else {
                Text(
                    "Not enough data yet",
                    style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp),
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
                    style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
                    color = FT.TextSecondary,
                )
                Spacer(Modifier.height(4.dp))
                FTMetricValue(achievement.toDisplayValue())

                achievement.comparisonContext?.let {
                    Text(
                        it,
                        style = TextStyle(fontFamily = Inter, fontSize = 12.sp),
                        color = FT.Emerald,
                    )
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
        val blockTitle = cycle.tbTemplate
            ?: cycle.focus.joinToString(" + ") { it.quality.label() }.ifBlank { "No stated focus" }
        val blockSubtitle = listOfNotNull(cycle.tbPrimary, cycle.tbSecondary)
            .joinToString(" · ") { it.toFocusLabel() }
            .ifBlank { null }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(
                    blockTitle,
                    style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
                    color = FT.TextPrimary,
                )
                blockSubtitle?.let {
                    Text(it, style = TextStyle(fontFamily = RobotoMono, fontSize = 11.sp), color = FT.TextMuted)
                }
            }
            EditLink("EDIT", onEdit)
        }
        Text(
            "${cycle.startDate} — ${cycle.endDate ?: "ongoing"}",
            style = TextStyle(fontFamily = RobotoMono, fontSize = 12.sp),
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
                    style = TextStyle(fontFamily = RobotoMono, fontSize = 11.sp),
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
                        style = TextStyle(fontFamily = Inter, fontSize = 13.sp),
                        color = FT.TextSecondary,
                        modifier = Modifier.weight(0.45f),
                    )
                    Text(
                        "${(entry.weight * 100).toInt()}%",
                        style = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
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
                style = TextStyle(fontFamily = Inter, fontSize = 12.5.sp),
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
                        style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp),
                        color = FT.TextPrimary,
                        modifier = Modifier.weight(1f),
                    )
                    if (weeks.isNotEmpty()) {
                        Text(
                            weeks,
                            style = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.SemiBold, fontSize = 12.sp),
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
                        style = TextStyle(fontFamily = RobotoMono, fontSize = 11.sp),
                        color = FT.TextMuted,
                    )
                    Text(
                        sessions,
                        style = TextStyle(fontFamily = RobotoMono, fontSize = 11.sp),
                        color = FT.TextSecondary,
                    )
                }
            }
        }
        EditLink("VIEW ALL BLOCKS →", onViewAll)
    }
}

// ---- Strength achievements ----

@Composable
private fun StrengthAchievementSection(achievements: List<Achievement>, bodyWeightKg: Double?) {
    FTCard(title = "STRENGTH ACHIEVEMENTS") {
        if (achievements.isEmpty()) {
            FTDataState(DataAvailability.Unavailable, "No strength data yet.")
            return@FTCard
        }
        achievements.forEachIndexed { i, ach ->
            if (i > 0) Spacer(Modifier.height(14.dp))

            val slug = ach.metric.removePrefix("best_1rm_")
            val exerciseLabel = slug.split("_").joinToString(" ") { w ->
                if (w.length <= 3) w.uppercase() else w.replaceFirstChar { it.uppercaseChar() }
            }
            val delta = ach.previousValue?.let { ach.value - it }
            val weeksSince = ach.previousOccurredAt?.let {
                ChronoUnit.WEEKS.between(it.toLocalDate(), ach.occurredAt.toLocalDate()).toInt()
            }
            val progressText = when {
                delta != null -> "${if (delta >= 0) "+" else ""}${"%.1f".format(delta)}kg${weeksSince?.let { " · ${it}w ago" } ?: ""}"
                else -> "first PR"
            }
            val tier = strengthTier(slug, ach.value, bodyWeightKg)

            // Row 1: name | best | delta
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Text(
                    exerciseLabel,
                    style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp),
                    color = FT.TextPrimary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "${"%.1f".format(ach.value)} kg",
                    style = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.Bold, fontSize = 13.sp),
                    color = FT.TextPrimary,
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    progressText,
                    style = TextStyle(fontFamily = RobotoMono, fontSize = 11.sp),
                    color = if (delta != null && delta > 0) FT.Emerald else FT.TextMuted,
                )
            }

            // Row 2: tier + bar + next target (indented)
            if (tier != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 8.dp, top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        tier.tier.label,
                        style = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.SemiBold, fontSize = 10.sp),
                        color = tier.tierColor,
                        modifier = Modifier.width(60.dp),
                    )
                    Column(Modifier.weight(1f)) {
                        RangeBar(value = tier.barFraction, max = 1.0, watchBelow = null, color = tier.tierColor, height = 5.dp, topPadding = 1.dp)
                    }
                    tier.nextLabel?.let {
                        Spacer(Modifier.width(8.dp))
                        Text(it, style = TextStyle(fontFamily = RobotoMono, fontSize = 10.sp), color = FT.TextMuted)
                    }
                }
            }
        }

        // Ratios footer
        val dlBest = achievements.firstOrNull { "barbell_deadlift" in it.metric }?.value
        val sqBest = achievements.firstOrNull { "barbell_squat" in it.metric && "front" !in it.metric }?.value
        val pullBest = achievements.firstOrNull { "pull_up_weighted" in it.metric || "weighted_pull_ups" in it.metric }?.value
        val ratios = buildList {
            if (dlBest != null && sqBest != null && sqBest > 0) add("DL/SQ ${"%.2f".format(dlBest / sqBest)}")
            if (sqBest != null && bodyWeightKg != null && bodyWeightKg > 0) add("SQ/BW ${"%.2f".format(sqBest / bodyWeightKg)}")
            if (pullBest != null) add("Pull +${"%.0f".format(pullBest)}kg")
        }
        if (ratios.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Spacer(Modifier.fillMaxWidth().height(1.dp).background(FT.GlassBorder))
            Spacer(Modifier.height(8.dp))
            Text(
                "RATIOS   ${ratios.joinToString("   ")}",
                style = TextStyle(fontFamily = RobotoMono, fontSize = 11.5.sp),
                color = FT.TextSecondary,
            )
        }
    }
}

private enum class StrTier(val label: String) {
    Untrained("Untrained"), Novice("Novice"), Intermediate("Interm."), Advanced("Advanced"), Elite("Elite")
}

private data class TierResult(val tier: StrTier, val barFraction: Double, val tierColor: androidx.compose.ui.graphics.Color, val nextLabel: String?)

// bodyweight multiples: [novice, intermediate, advanced, elite]
private val BW_STANDARDS = mapOf(
    "barbell_deadlift"         to doubleArrayOf(1.0, 1.5, 2.0, 2.5),
    "barbell_squat"            to doubleArrayOf(0.75, 1.25, 1.75, 2.25),
    "front_barbell_squat"      to doubleArrayOf(0.6, 1.0, 1.4, 1.8),
    "chest_press"              to doubleArrayOf(0.5, 0.75, 1.25, 1.5),
    "inclined_chest_press_icp" to doubleArrayOf(0.45, 0.7, 1.1, 1.4),
    "military_press"           to doubleArrayOf(0.35, 0.55, 0.8, 1.0),
    "clean_and_press"          to doubleArrayOf(0.5, 0.7, 0.9, 1.2),
    "bent_over_barbell_row"    to doubleArrayOf(0.5, 0.75, 1.0, 1.3),
)
// absolute added-weight thresholds for weighted pull-ups
private val ABS_STANDARDS = mapOf(
    "pull_up_weighted"  to doubleArrayOf(10.0, 25.0, 45.0, 70.0),
    "weighted_pull_ups" to doubleArrayOf(10.0, 25.0, 45.0, 70.0),
)

private fun strengthTier(slug: String, e1rmKg: Double, bodyWeightKg: Double?): TierResult? {
    val thresholds = bodyWeightKg?.let { bw -> BW_STANDARDS[slug]?.let { m -> DoubleArray(4) { m[it] * bw } } }
        ?: ABS_STANDARDS[slug]
        ?: return null
    val tiers = listOf(StrTier.Novice, StrTier.Intermediate, StrTier.Advanced, StrTier.Elite)
    val idx = thresholds.indexOfLast { e1rmKg >= it }
    val tier = if (idx < 0) StrTier.Untrained else tiers[idx]
    val floor = if (idx < 0) 0.0 else thresholds[idx]
    val ceiling = if (idx < 0) thresholds[0] else thresholds.getOrNull(idx + 1)
    val nextTier = if (idx < 0) tiers[0] else tiers.getOrNull(idx + 1)
    val fraction = if (ceiling != null && ceiling > floor) ((e1rmKg - floor) / (ceiling - floor)).coerceIn(0.0, 1.0) else 1.0
    val nextLabel = if (ceiling != null && nextTier != null) "→ ${nextTier.label} ${"%.0f".format(ceiling)}kg" else null
    val color = when (tier) {
        StrTier.Untrained    -> FT.TextMuted
        StrTier.Novice       -> FT.Warning
        StrTier.Intermediate -> FT.Info
        StrTier.Advanced     -> FT.Emerald
        StrTier.Elite        -> FT.DomainUser
    }
    return TierResult(tier, fraction, color, nextLabel)
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

private fun String.toFocusLabel(): String =
    split("_").joinToString(" ") { it.replaceFirstChar { c -> c.uppercaseChar() } }

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
