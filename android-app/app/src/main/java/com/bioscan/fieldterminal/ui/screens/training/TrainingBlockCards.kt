package com.bioscan.fieldterminal.ui.screens.training

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.model.TrainingBlockSummaryRow
import com.bioscan.fieldterminal.domain.DataAvailability
import com.bioscan.fieldterminal.domain.DisplayValue
import com.bioscan.fieldterminal.domain.FocusQuality
import com.bioscan.fieldterminal.domain.FocusRole
import com.bioscan.fieldterminal.domain.PersonalRange
import com.bioscan.fieldterminal.domain.RangeComparison
import com.bioscan.fieldterminal.domain.RangeKind
import com.bioscan.fieldterminal.domain.TrainingCycle
import com.bioscan.fieldterminal.domain.progressFraction
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTCardEmphasis
import com.bioscan.fieldterminal.ui.components.FTDataState
import com.bioscan.fieldterminal.ui.components.FTMetricValue
import com.bioscan.fieldterminal.ui.components.FTRangeIndicator
import com.bioscan.fieldterminal.ui.components.RangeBar
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.LocalDate
import java.time.temporal.ChronoUnit

// The current block, moved here from the User tab (UX review P5): Training › Plan is the one place a block is seen,
// started, edited and deleted. It reads the active training_cycles row, which planned blocks are mirrored into
// (docs/training-programming/01-architecture.md). Without one, the Plan tab's PLAN A NEW BLOCK is the way in; recording a
// block run elsewhere stays possible as a low-emphasis action.
@Composable
internal fun CurrentBlockCard(cycle: TrainingCycle?, currentValue: Double?, onEdit: () -> Unit, onAdd: () -> Unit) {
    // Contract A3: the Plan tab's lead card.
    FTCard(title = "CURRENT BLOCK", emphasis = FTCardEmphasis.Hero) {
        if (cycle == null) {
            FTDataState(DataAvailability.Unavailable, "No active training block.")
            FTButton("+ RECORD A BLOCK RUN ELSEWHERE", emphasis = FTButtonEmphasis.Tertiary, onClick = onAdd)
            return@FTCard
        }
        val blockTitle = currentBlockTitle(cycle)
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
            FTButton("EDIT", emphasis = FTButtonEmphasis.Tertiary, onClick = onEdit)
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

// The last three blocks with their session counts, and VIEW ALL (moved from the User tab with the current block).
@Composable
internal fun BlockHistoryCard(
    summaries: List<TrainingBlockSummaryRow>?,
    onViewAll: () -> Unit = {},
) {
    FTCard(title = "HISTORY") {
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
        FTButton("VIEW ALL BLOCKS", emphasis = FTButtonEmphasis.Tertiary, onClick = onViewAll)
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

// The block's name: its template, else its focus qualities.
internal fun currentBlockTitle(cycle: TrainingCycle): String =
    cycle.tbTemplate ?: cycle.focus.joinToString(" + ") { it.quality.label() }.ifBlank { "No stated focus" }

// "WEEK 3 OF 8 · 34 DAYS LEFT", or the start date of an open-ended block.
internal fun currentBlockProgress(cycle: TrainingCycle, today: LocalDate = LocalDate.now()): String {
    val end = cycle.endDate ?: return "SINCE ${cycle.startDate} · ONGOING"
    val totalDays = ChronoUnit.DAYS.between(cycle.startDate, end)
    if (totalDays <= 0) return "${cycle.startDate} — $end"
    val elapsed = ChronoUnit.DAYS.between(cycle.startDate, today).coerceIn(0, totalDays)
    val weeks = maxOf(1L, totalDays / 7)
    return "WEEK ${minOf(elapsed / 7 + 1, weeks)} OF $weeks · ${totalDays - elapsed} DAYS LEFT"
}

private fun FocusQuality.label(): String = name.replace(Regex("(?<=.)(?=\\p{Upper})"), " ")

private fun String.toFocusLabel(): String =
    split("_").joinToString(" ") { it.replaceFirstChar { c -> c.uppercaseChar() } }
