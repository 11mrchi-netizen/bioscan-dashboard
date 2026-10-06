package com.bioscan.fieldterminal.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.TrainingCyclesRepository
import com.bioscan.fieldterminal.data.model.TrainingBlockSummaryRow
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.TileHeader
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import com.bioscan.fieldterminal.ui.theme.Inter
import com.bioscan.fieldterminal.ui.theme.RobotoMono
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun TrainingBlocksScreen(onBack: () -> Unit) {
    var blocks by remember { mutableStateOf<List<TrainingBlockSummaryRow>?>(null) }
    LaunchedEffect(Unit) {
        blocks = TrainingCyclesRepository(SupabaseClientProvider.client).loadBlockSummaries()
    }

    val b = blocks
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(FT.Base),
        contentPadding = PaddingValues(bottom = 24.dp),
    ) {
        item { TileHeader(onBack = onBack) }
        if (b == null) {
            item {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 48.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(color = FT.DomainTraining) }
            }
        } else if (b.isEmpty()) {
            item {
                Text(
                    "No training blocks found.",
                    color = FT.TextSecondary, fontFamily = Inter, fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 32.dp),
                )
            }
        } else {
            items(b, key = { it.id }) { block ->
                TrainingBlockCard(
                    block = block,
                    modifier = Modifier.padding(horizontal = 22.dp).padding(top = 18.dp),
                )
            }
        }
    }
}

@Composable
private fun TrainingBlockCard(block: TrainingBlockSummaryRow, modifier: Modifier = Modifier) {
    val weeks = block.blockDays?.let { "${it / 7}W" } ?: ""
    val categoryLabel = when (block.tbCategory) {
        "strength_first" -> "STR FIRST"
        "concurrent"     -> "CONCURRENT"
        "aerobic_base"   -> "AEROBIC BASE"
        else             -> block.tbCategory?.uppercase() ?: ""
    }
    val templateLabel = (block.template ?: "")
        .replace(Regex("\\s*\\(\\d+\\s+Weeks?\\)", RegexOption.IGNORE_CASE), "")
        .trim()
    val cardTitle = buildString {
        append(templateLabel)
        if (weeks.isNotEmpty()) append(" · $weeks")
        if (categoryLabel.isNotEmpty()) append(" · $categoryLabel")
    }

    FTCard(title = cardTitle, modifier = modifier) {
        Text(
            formatBlockDateRange(block.startDate, block.endDate),
            color = FT.TextSecondary, fontFamily = RobotoMono, fontSize = 11.sp,
        )
        Spacer(Modifier.height(4.dp))

        // Tier 1 — Session counts
        BlockSection("SESSIONS") {
            Text(
                "${block.strengthSessions} STR  ${block.conditioningSessions} COND  ${block.enduranceSessions} END",
                color = FT.TextPrimary, fontFamily = RobotoMono, fontSize = 13.sp,
            )
        }

        // Tier 1 — Conditioning HR (only when linked wearable sessions exist)
        if (block.linkedWearableCount > 0 && block.avgConditioningHr != null) {
            BlockSection("CONDITIONING HR") {
                val earlyLate = if (block.earlyHr != null && block.lateHr != null)
                    "  ${block.earlyHr.toInt()}→${block.lateHr.toInt()} early→late"
                else ""
                val delta = block.conditioningHrDelta?.let { d ->
                    "  [${if (d > 0) "+" else ""}${d.toInt()}]"
                } ?: ""
                Text(
                    "avg ${block.avgConditioningHr.toInt()} bpm$earlyLate$delta",
                    color = FT.TextPrimary, fontFamily = RobotoMono, fontSize = 13.sp,
                )
                Text(
                    "n=${block.linkedWearableCount} linked sessions",
                    color = FT.TextMuted, fontFamily = RobotoMono, fontSize = 10.sp,
                )
            }
        }

        // Tier 2 — Daily wearable metrics
        val hasTier2 = block.rhrDaysCount > 0 || block.hrvDaysCount > 0 || block.vo2maxDaysCount > 0
        if (hasTier2) {
            BlockSection("DAILY METRICS") {
                if (block.rhrDaysCount > 0 && block.rhrAvg != null) {
                    val delta = block.rhrDelta?.let { d -> " ${if (d > 0) "+" else ""}${"%.1f".format(d)}" } ?: ""
                    MetricRow("RHR", "${block.rhrAvg.toInt()} bpm avg$delta", "n=${block.rhrDaysCount}d")
                }
                if (block.hrvDaysCount > 0 && block.hrvAvg != null) {
                    val delta = block.hrvDelta?.let { d -> " ${if (d > 0) "+" else ""}${"%.1f".format(d)}" } ?: ""
                    MetricRow("HRV", "${"%.1f".format(block.hrvAvg)} ms avg$delta", "n=${block.hrvDaysCount}d")
                }
                if (block.vo2maxDaysCount > 0 && block.vo2maxLast != null) {
                    val range = if (block.vo2maxFirst != null && block.vo2maxFirst != block.vo2maxLast)
                        "${"%.1f".format(block.vo2maxFirst)}→${"%.1f".format(block.vo2maxLast)}"
                    else "${"%.1f".format(block.vo2maxLast)}"
                    val delta = block.vo2maxDelta?.let { d -> " (${if (d > 0) "+" else ""}${"%.1f".format(d)})" } ?: ""
                    MetricRow("VO2MAX", "$range$delta", "n=${block.vo2maxDaysCount}")
                }
            }
        }

        // Tier 3 — Cross-block comparison (same template only)
        val prevHr = block.prevAvgConditioningHr
        val prevRhr = block.prevRhrAvg
        val prevHrv = block.prevHrvAvg
        val prevVo2 = block.prevVo2max
        val hasTier3 = (prevHr != null && block.avgConditioningHr != null) ||
                (prevRhr != null && block.rhrAvg != null) ||
                (prevHrv != null && block.hrvAvg != null) ||
                (prevVo2 != null && block.vo2maxLast != null)
        if (hasTier3) {
            BlockSection("VS PREV ${templateLabel.uppercase()} BLOCK") {
                if (prevHr != null && block.avgConditioningHr != null) {
                    val diff = block.avgConditioningHr - prevHr
                    MetricRow("COND HR", "${if (diff > 0) "+" else ""}${"%.1f".format(diff)} bpm", "prev ${prevHr.toInt()}")
                }
                if (prevRhr != null && block.rhrAvg != null) {
                    val diff = block.rhrAvg - prevRhr
                    MetricRow("RHR", "${if (diff > 0) "+" else ""}${"%.1f".format(diff)} bpm", "prev ${prevRhr.toInt()}")
                }
                if (prevHrv != null && block.hrvAvg != null) {
                    val diff = block.hrvAvg - prevHrv
                    MetricRow("HRV", "${if (diff > 0) "+" else ""}${"%.1f".format(diff)} ms", "prev ${"%.1f".format(prevHrv)}")
                }
                if (prevVo2 != null && block.vo2maxLast != null) {
                    val diff = block.vo2maxLast - prevVo2
                    MetricRow("VO2MAX", "${if (diff > 0) "+" else ""}${"%.1f".format(diff)}", "prev ${"%.1f".format(prevVo2)}")
                }
            }
        }
    }
}

@Composable
private fun BlockSection(label: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            label,
            color = FT.TextMuted, fontFamily = RobotoMono,
            fontWeight = FontWeight.Bold, fontSize = 10.sp,
        )
        content()
    }
}

@Composable
private fun MetricRow(label: String, value: String, note: String = "") {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = FT.TextSecondary, fontFamily = RobotoMono, fontSize = 12.sp,
            modifier = Modifier.width(64.dp))
        Text(value, color = FT.TextPrimary, fontFamily = RobotoMono, fontSize = 12.sp,
            modifier = Modifier.weight(1f))
        if (note.isNotEmpty()) {
            Text(note, color = FT.TextMuted, fontFamily = RobotoMono, fontSize = 10.sp)
        }
    }
}

private val FMT_MONTH_DAY = DateTimeFormatter.ofPattern("MMM d")
private val FMT_YEAR = DateTimeFormatter.ofPattern("yyyy")

private fun formatBlockDateRange(start: String, end: String): String {
    val s = runCatching { LocalDate.parse(start) }.getOrNull() ?: return "$start → $end"
    val e = runCatching { LocalDate.parse(end) }.getOrNull() ?: return "$start → $end"
    return if (s.year == e.year)
        "${s.format(FMT_MONTH_DAY)} → ${e.format(FMT_MONTH_DAY)}, ${s.year}"
    else
        "${s.format(FMT_MONTH_DAY)}, ${s.year} → ${e.format(FMT_MONTH_DAY)}, ${e.year}"
}
