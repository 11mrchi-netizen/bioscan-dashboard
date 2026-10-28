package com.bioscan.fieldterminal.ui.screens.user

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.ConditioningHistory
import com.bioscan.fieldterminal.domain.DataAvailability
import com.bioscan.fieldterminal.domain.PerformanceTimeframe
import com.bioscan.fieldterminal.domain.toPerformanceTimeframe
import com.bioscan.fieldterminal.domain.bucketWeekly
import com.bioscan.fieldterminal.domain.trailingWeeklyAverage
import com.bioscan.fieldterminal.ui.components.DateTrendLine
import com.bioscan.fieldterminal.ui.components.FTBar
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTDataState
import com.bioscan.fieldterminal.ui.components.FTMetricRow
import com.bioscan.fieldterminal.ui.components.FTSegment
import com.bioscan.fieldterminal.ui.components.FTSegmentBar
import com.bioscan.fieldterminal.ui.components.FTSmallMultiples
import com.bioscan.fieldterminal.ui.components.FTWeeklyBars
import com.bioscan.fieldterminal.ui.components.SegmentedToggle
import com.bioscan.fieldterminal.ui.components.SmallMultiple
import com.bioscan.fieldterminal.ui.components.TrendSeries
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.LocalDate
import java.time.temporal.ChronoUnit

@Composable
fun ConditioningSkillScreen(onBack: () -> Unit) {
    SkillPage("CONDITIONING", "CONSISTENCY · HEART RATE · BLOCKS · MIX", onBack) {
        LoadedContent(load = { UserDataCache.conditioning() }) { ConditioningContent(it) }
    }
}

@Composable
private fun ConditioningContent(history: ConditioningHistory) {
    val timeframe = com.bioscan.fieldterminal.ui.components.GlobalTimeRange.selected.toPerformanceTimeframe()
    val today = remember { LocalDate.now() }
    val from = today.minusMonths(timeframe.months)
    val level = remember(history) { conditioningLevelOf(history, today) }
    val inWindow = history.entries.filter { !it.date.isBefore(from) }

    SkillHeroCard(
        Skill.CONDITIONING, level,
        info = "Compares your last 4 weeks of conditioning sessions with your own best rolling 4 weeks over the last 90 days. It is a consistency level " +
            "against yourself (VS YOU), not a fitness claim: intervals, power and zones are not recorded for these sessions. PROVISIONAL until 8 sessions exist in the window.",
    ) {
        val last28 = history.entries.count { ChronoUnit.DAYS.between(it.date, today) in 0..27 }
        FTMetricRow("Last 28 days", "$last28 ${if (last28 == 1) "session" else "sessions"}")
        history.entries.lastOrNull()?.let { FTMetricRow("Most recent", "${it.date} · ${it.template ?: "session"}") }
    }

    if (history.entries.isEmpty()) {
        FTDataState(DataAvailability.Building, "No conditioning sessions imported yet.")
        return
    }


    ConsistencyCard(history, from, today)
    HeartRateCard(inWindow)
    BlocksCard(history)
    MixCard(inWindow)
}

@Composable
private fun ConsistencyCard(history: ConditioningHistory, from: LocalDate, today: LocalDate) {
    FTCard(title = "CONSISTENCY", info = "Conditioning sessions per Monday-to-Sunday week; quiet weeks are real zeros. The line is the trailing 4-week average.") {
        val weekly = bucketWeekly(history.entries, { it.date }, { 1.0 }, from, today)
        FTWeeklyBars(weekly, skillColor(Skill.CONDITIONING), "sessions", average = trailingWeeklyAverage(weekly), valueFormat = { "%.0f".format(it) })
        val total = weekly.sumOf { it.value }
        FTMetricRow("In window", "${"%.0f".format(total)} sessions · ${"%.1f".format(total / weekly.size.coerceAtLeast(1))} per week")
    }
}

@Composable
private fun HeartRateCard(inWindow: List<com.bioscan.fieldterminal.data.ConditioningEntry>) {
    FTCard(title = "HEART RATE", info = "Average and peak heart rate for sessions that are linked to a same-day wearable recording. Sessions without a linked recording have no heart-rate data and are not estimated.") {
        val withHr = inWindow.filter { it.avgHr != null }
        val linked = inWindow.count { it.avgHr != null }
        Text("$linked of ${inWindow.size} sessions in this window have linked wearable heart rate.", style = FTType.Caption, color = FT.TextSecondary)
        if (withHr.size < 2) {
            FTDataState(DataAvailability.Building, "Needs at least 2 sessions with linked heart rate to draw a trend.")
            return@FTCard
        }
        DateTrendLine(
            series = listOf(
                TrendSeries(withHr.mapNotNull { e -> e.maxHr?.let { e.date to it } }, FT.TextMuted, "PEAK"),
                TrendSeries(withHr.map { it.date to it.avgHr!! }, FT.DomainHeart, "AVG"),
            ),
            valueFormat = { "%.0f".format(it) },
            unit = "bpm",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LegendDot("AVG HR", FT.DomainHeart)
            LegendDot("PEAK HR", FT.TextMuted)
        }
        withHr.mapNotNull { it.durationMin }.takeIf { it.isNotEmpty() }?.let { FTMetricRow("Typical duration (linked)", "${"%.0f".format(it.average())} min") }
    }
}

@Composable
private fun BlocksCard(history: ConditioningHistory) {
    FTCard(title = "BLOCKS", info = "One point per training block (oldest to newest), each on its own scale, from the block metrics view. Early vs late heart rate compares the first and last conditioning sessions of a block; the app does not judge which direction is better.") {
        val blocks = history.blocks.filter { it.conditioningSessions > 0 }.sortedBy { it.startDate }.takeLast(10)
        if (blocks.size < 2) {
            FTDataState(DataAvailability.Building, "Needs at least 2 training blocks with conditioning sessions to compare.")
            return@FTCard
        }
        fun series(pick: (com.bioscan.fieldterminal.data.model.TrainingBlockSummaryRow) -> Double?) =
            blocks.mapNotNull { b -> pick(b)?.let { v -> runCatching { LocalDate.parse(b.startDate) }.getOrNull()?.to(v) } }
        fun latest(pick: (com.bioscan.fieldterminal.data.model.TrainingBlockSummaryRow) -> Double?, fmt: String) =
            blocks.lastOrNull { pick(it) != null }?.let { fmt.format(pick(it)) }
        FTSmallMultiples(
            listOf(
                SmallMultiple("Avg conditioning HR", "bpm", series { it.avgConditioningHr }, latest({ it.avgConditioningHr }, "%.0f"), FT.DomainHeart),
                SmallMultiple("Sessions per block", "", series { it.conditioningSessions.toDouble() }, latest({ it.conditioningSessions.toDouble() }, "%.0f"), skillColor(Skill.CONDITIONING)),
                SmallMultiple("Resting HR", "bpm", series { it.rhrAvg }, latest({ it.rhrAvg }, "%.0f"), FT.Category.Wellbeing.c500),
                SmallMultiple("HRV", "ms", series { it.hrvAvg }, latest({ it.hrvAvg }, "%.0f"), FT.Category.Sleep.c500),
            ),
        )
        SectionLabel("EARLY VS LATE HEART RATE")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            blocks.takeLast(5).reversed().forEach { b ->
                val early = b.earlyHr
                val late = b.lateHr
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        (b.template ?: b.startDate).replace(Regex("\\s*\\(\\d+\\s+Weeks?\\)", RegexOption.IGNORE_CASE), "").trim(),
                        style = FTType.Body, color = FT.TextSecondary, modifier = Modifier.weight(1f), maxLines = 1,
                    )
                    Text(
                        if (early != null && late != null) "${"%.0f".format(early)} → ${"%.0f".format(late)} bpm" else "no linked HR",
                        style = FTType.MonoCaption, color = if (early != null && late != null) FT.TextPrimary else FT.TextMuted,
                    )
                }
            }
        }
    }
}

@Composable
private fun MixCard(inWindow: List<com.bioscan.fieldterminal.data.ConditioningEntry>) {
    FTCard(title = "MIX", info = "How sessions in this window split by template, and which drills you did most. Counts only; no intensity is inferred from drill names.") {
        val templates = inWindow.groupingBy { it.template?.takeIf { t -> t.isNotBlank() } ?: "Unlabelled" }.eachCount().entries.sortedByDescending { it.value }
        if (templates.isEmpty()) {
            FTDataState(DataAvailability.Building, "No sessions in this window.")
            return@FTCard
        }
        val shades = listOf(FT.Category.Activity.c500, FT.Category.Activity.c300, FT.Category.Activity.c700, FT.Category.Activity.c900)
        FTSegmentBar(templates.take(4).mapIndexed { i, e -> FTSegment(e.key, e.value.toDouble(), shades[i % shades.size]) })
        val drills = inWindow.flatMap { it.drills }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(8)
        if (drills.isNotEmpty()) {
            SectionLabel("MOST-DONE DRILLS")
            val top = drills.first().value.toFloat()
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                drills.forEach { (name, count) ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(name, style = FTType.Body, color = FT.TextSecondary, modifier = Modifier.weight(1f), maxLines = 1)
                            Text("$count×", style = FTType.Telemetry, color = FT.TextPrimary)
                        }
                        FTBar(fraction = count / top, color = skillColor(Skill.CONDITIONING), height = 6.dp)
                    }
                }
            }
        }
    }
}
