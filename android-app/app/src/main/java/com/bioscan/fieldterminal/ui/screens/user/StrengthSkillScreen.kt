package com.bioscan.fieldterminal.ui.screens.user

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.StrengthHistory
import com.bioscan.fieldterminal.domain.DataAvailability
import com.bioscan.fieldterminal.domain.LiftSeries
import com.bioscan.fieldterminal.domain.PerformanceTimeframe
import com.bioscan.fieldterminal.domain.WeekAggregate
import com.bioscan.fieldterminal.domain.bucketWeekly
import com.bioscan.fieldterminal.domain.e1rmPrTimeline
import com.bioscan.fieldterminal.domain.levels.LiftTierResult
import com.bioscan.fieldterminal.domain.levels.StandardKind
import com.bioscan.fieldterminal.domain.levels.ratedLifts
import com.bioscan.fieldterminal.domain.levels.strengthSkillLevel
import com.bioscan.fieldterminal.domain.exerciseSlug
import com.bioscan.fieldterminal.domain.liftE1rmSeries
import com.bioscan.fieldterminal.domain.standardKey
import com.bioscan.fieldterminal.domain.trailingWeeklyAverage
import com.bioscan.fieldterminal.domain.weeklyStrengthDays
import com.bioscan.fieldterminal.domain.weeklyTonnageKg
import com.bioscan.fieldterminal.ui.components.DateTrendLine
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTDataState
import com.bioscan.fieldterminal.ui.components.FTMetricRow
import com.bioscan.fieldterminal.ui.components.FTTierLadder
import com.bioscan.fieldterminal.ui.components.FTWeeklyBars
import com.bioscan.fieldterminal.ui.components.SegmentedToggle
import com.bioscan.fieldterminal.ui.components.SubTabChip
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val DATE_FMT = DateTimeFormatter.ofPattern("d MMM yyyy")
private val TIER_LABELS = listOf("Novice", "Interm.", "Advanced", "Elite")

// App restructure: the Strength page is Training's STRENGTH tab (was pushed from the User tab's level buttons).
@Composable
fun StrengthTab() {
    androidx.compose.foundation.layout.Column(
        modifier = androidx.compose.ui.Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(18.dp),
    ) {
        LoadedContent(load = { UserDataCache.strength() }) { StrengthContent(it) }
        GripStrengthCard()
    }
}

@Composable
private fun StrengthContent(history: StrengthHistory) {
    var timeframe by remember { mutableStateOf(PerformanceTimeframe.ThreeMonths) }
    val today = remember { LocalDate.now() }
    val from = today.minusMonths(timeframe.months)
    val series = remember(history) { liftE1rmSeries(history.sets) }
    val rated = remember(series, history.bodyWeightKg) { ratedLifts(series, history.bodyWeightKg, today) }
    val level = remember(rated, history.bodyWeightKg) { strengthSkillLevel(rated, history.bodyWeightKg == null) }

    SkillHeroCard(
        Skill.STRENGTH, level,
        info = "Median ladder position of your best estimated 1RM (last 6 months) over the lifts that have a standard, like the Healthspan " +
            "strength factor. The standards are lifter conventions (bodyweight multiples), not population percentiles and not adjusted for sex or age. " +
            "One lift is enough to show a level; under three lifts it stays PROVISIONAL.",
    ) {
        ratioRows(rated, history.bodyWeightKg).forEach { (label, value) -> FTMetricRow(label, value) }
    }

    if (history.sets.isEmpty()) {
        FTDataState(DataAvailability.Building, "No logged sets yet. Log structured strength sessions to see standards, e1RM trends and PRs.")
        return
    }

    SegmentedToggle(
        options = PerformanceTimeframe.entries, selected = timeframe, labelOf = { it.label }, onSelect = { timeframe = it },
        accentColor = skillColor(Skill.STRENGTH),
    )

    StandardsCard(rated, history.bodyWeightKg)
    E1rmCard(series, from)
    VolumeCard(history, from, today)
    PrCard(series, from)
    IntensityCard(history, from, today)
}

private fun ratioRows(rated: List<LiftTierResult>, bodyWeightKg: Double?): List<Pair<String, String>> {
    fun kg(slug: String): Double? = rated.firstOrNull { standardKey(exerciseSlug(it.name)) == slug }?.e1rmKg
    val squat = kg("barbell_squat")
    val dead = kg("barbell_deadlift")
    return buildList {
        if (squat != null && dead != null && squat > 0) add("Deadlift / squat" to "%.2f".format(dead / squat))
        if (squat != null && bodyWeightKg != null && bodyWeightKg > 0) add("Squat / bodyweight" to "%.2f×".format(squat / bodyWeightKg))
        if (dead != null && bodyWeightKg != null && bodyWeightKg > 0) add("Deadlift / bodyweight" to "%.2f×".format(dead / bodyWeightKg))
        kg("pull_up_weighted")?.let { add("Weighted pull-up" to "+%.0f kg".format(it)) }
    }
}

@Composable
private fun StandardsCard(rated: List<LiftTierResult>, bodyWeightKg: Double?) {
    FTCard(title = "STANDARDS", info = "Each lift's best estimated 1RM over the last 6 months against the lifter-standards ladder (Novice, Intermediate, Advanced, Elite). Bodyweight-multiple lifts need a body weight; weighted pull-ups use added load.") {
        if (rated.isEmpty()) {
            FTDataState(DataAvailability.Building, if (bodyWeightKg == null) "Add a body weight to rate lifts against bodyweight standards." else "No logged lift has a standard yet.")
            return@FTCard
        }
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            rated.sortedByDescending { it.ladderFraction }.forEach { r ->
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(r.name, style = FTType.RowTitle, color = FT.TextPrimary, modifier = Modifier.weight(1f))
                        Text(tierText(r), style = FTType.Label, color = skillColor(Skill.STRENGTH))
                    }
                    FTTierLadder(r.ladderFraction.toFloat(), TIER_LABELS, r.tier.ordinal.coerceAtMost(4), skillColor(Skill.STRENGTH))
                    Text(nextText(r, bodyWeightKg), style = FTType.Caption, color = FT.TextSecondary)
                }
            }
        }
    }
}

private fun tierText(r: LiftTierResult): String = r.tier.label.uppercase()

private fun nextText(r: LiftTierResult, bodyWeightKg: Double?): String {
    val valueText = when (r.kind) {
        StandardKind.BODYWEIGHT_MULTIPLE ->
            (bodyWeightKg?.takeIf { it > 0 }?.let { "${"%.2f".format(r.e1rmKg / it)}× bodyweight · " } ?: "") + "${"%.0f".format(r.e1rmKg)} kg e1RM"
        StandardKind.ADDED_LOAD_KG -> "+${"%.0f".format(r.e1rmKg)} kg added"
    }
    val next = r.nextTier
    val target = r.nextKg
    if (next == null || target == null) return "$valueText · top of the ladder"
    val targetText = when (r.kind) {
        StandardKind.BODYWEIGHT_MULTIPLE -> "${"%.0f".format(target)} kg"
        StandardKind.ADDED_LOAD_KG -> "+${"%.0f".format(target)} kg"
    }
    return "$valueText · next: ${next.label} at $targetText"
}

@Composable
private fun E1rmCard(series: List<LiftSeries>, from: LocalDate) {
    FTCard(title = "E1RM EVOLUTION", info = "Best estimated one-rep max per training day (Epley, sets of 1-12 reps), one line per lift. Spellings of the same lift are merged.") {
        val candidates = series.filter { it.points.size >= 2 }.take(8)
        if (candidates.isEmpty()) {
            FTDataState(DataAvailability.Building, "A lift needs at least 2 logged training days to draw a trend.")
            return@FTCard
        }
        var selected by remember(candidates) { mutableStateOf(candidates.first()) }
        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            candidates.forEach { s -> SubTabChip(label = s.name, selected = s.key == selected.key, onClick = { selected = s }) }
        }
        val windowed = selected.points.filter { !it.first.isBefore(from) }
        if (windowed.size < 2) {
            Text("Fewer than 2 training days for ${selected.name} in this window. Showing all-time best: ${"%.1f".format(selected.points.maxOf { it.second })} kg.", style = FTType.Caption, color = FT.TextSecondary)
        } else {
            DateTrendLine(windowed, skillColor(Skill.STRENGTH), highlightColor = skillColor(Skill.STRENGTH), valueFormat = { "%.1f".format(it) }, unit = "kg · estimated 1RM")
            FTMetricRow("Best in window", "${"%.1f".format(windowed.maxOf { it.second })} kg")
            FTMetricRow("Change over window", "%+.1f kg".format(windowed.last().second - windowed.first().second))
        }
    }
}

@Composable
private fun VolumeCard(history: StrengthHistory, from: LocalDate, today: LocalDate) {
    FTCard(title = "VOLUME", info = "Tonnage = reps × load summed per week, and training days per week. Sets without a load (bodyweight) add no tonnage.") {
        val sets = history.sets.filter { !it.date.isBefore(from) }
        if (sets.isEmpty()) {
            FTDataState(DataAvailability.Building, "No logged sets in this window.")
            return@FTCard
        }
        val tonnage = weeklyTonnageKg(history.sets, from, today)
        FTWeeklyBars(tonnage, skillColor(Skill.STRENGTH), "kg", average = trailingWeeklyAverage(tonnage), valueFormat = { "%.0f".format(it) })
        SectionLabel("TRAINING DAYS PER WEEK")
        FTWeeklyBars(weeklyStrengthDays(history.sets, from, today), skillColor(Skill.CONDITIONING), "days", valueFormat = { "%.0f".format(it) }, height = 70.dp)
    }
}

@Composable
private fun PrCard(series: List<LiftSeries>, from: LocalDate) {
    FTCard(title = "PR TIMELINE", info = "Every time a lift's estimated 1RM beat its previous best, newest first. The first logged day of a lift counts as its first PR.") {
        val prs = e1rmPrTimeline(series).filter { !it.date.isBefore(from) }.sortedByDescending { it.date }.take(12)
        if (prs.isEmpty()) {
            FTDataState(DataAvailability.Building, "No new e1RM PRs in this window.")
            return@FTCard
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            prs.forEach { pr ->
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(pr.name, style = FTType.Body, color = FT.TextPrimary)
                        Text(DATE_FMT.format(pr.date), style = FTType.MonoCaption, color = FT.TextMuted)
                    }
                    Text("${"%.1f".format(pr.e1rmKg)} kg", style = FTType.Telemetry, color = FT.TextPrimary)
                    Text(
                        "  " + (pr.previousKg?.let { "%+.1f".format(pr.e1rmKg - it) } ?: "first"),
                        style = FTType.MonoCaption, color = if (pr.previousKg != null) FT.Emerald else FT.TextMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun IntensityCard(history: StrengthHistory, from: LocalDate, today: LocalDate) {
    FTCard(title = "INTENSITY", info = "Average logged RPE per week, from sets where you recorded one. Hidden until enough RPE has been logged; missing RPE is never filled in.") {
        val withRpe = history.sets.filter { it.rpe != null && !it.date.isBefore(from) }
        if (withRpe.size < 10) {
            FTDataState(DataAvailability.Building, "Needs RPE on at least 10 sets in this window (${withRpe.size} so far).")
            return@FTCard
        }
        FTWeeklyBars(
            bucketWeekly(withRpe, { it.date }, { it.rpe!!.toDouble() }, from, today, WeekAggregate.MEAN),
            color = skillColor(Skill.STRENGTH), unit = "RPE", valueFormat = { "%.1f".format(it) }, height = 90.dp,
        )
    }
}
