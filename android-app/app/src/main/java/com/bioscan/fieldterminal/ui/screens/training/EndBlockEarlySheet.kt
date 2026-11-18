package com.bioscan.fieldterminal.ui.screens.training

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.BlockForEnding
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.TrainingProgramRepository
import com.bioscan.fieldterminal.data.endBlockEarly
import com.bioscan.fieldterminal.data.loadBlockForEnding
import com.bioscan.fieldterminal.domain.training.generate.MaxStep
import com.bioscan.fieldterminal.domain.training.generate.endEarlyPreview
import com.bioscan.fieldterminal.domain.training.generate.stepUpMaxes
import com.bioscan.fieldterminal.domain.training.loadStepKg
import com.bioscan.fieldterminal.domain.training.movementKey
import com.bioscan.fieldterminal.ui.components.DateField
import com.bioscan.fieldterminal.ui.components.FTFlow
import com.bioscan.fieldterminal.ui.components.FlowField
import com.bioscan.fieldterminal.ui.components.FlowPage
import com.bioscan.fieldterminal.ui.components.FlowQuestion
import com.bioscan.fieldterminal.ui.components.FlowTile
import com.bioscan.fieldterminal.ui.components.describeLoadFailure
import com.bioscan.fieldterminal.ui.components.launchSafe
import com.bioscan.fieldterminal.ui.components.rememberFlowState
import com.bioscan.fieldterminal.ui.theme.FTType
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.TemporalAdjusters
import java.util.Locale
import kotlin.math.roundToInt
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// Ending a block early (Nov 2026): LAST DAY (end of this week, end of next week, or a day), with what that keeps and
// drops, then MAXES (keep today's, the default: the cycle was not completed; or step them up by the program's increments).
// Sessions after the last day are kept, marked superseded; the next block is planned from the day after.

private val DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)

@Composable
fun EndBlockEarlySheet(blockId: Long, onDismiss: () -> Unit, onDone: () -> Unit) {
    val supabase = SupabaseClientProvider.client
    val repo = remember { TrainingProgramRepository(supabase) }
    val scope = rememberCoroutineScope()
    val today = remember { LocalDate.now() }
    var block by remember { mutableStateOf<BlockForEnding?>(null) }
    var steps by remember { mutableStateOf<List<MaxStep>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var stepUp by remember { mutableStateOf(false) }
    val thisSunday = remember { today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY)) }
    var lastDay by remember { mutableStateOf(thisSunday.plusWeeks(1)) }

    LaunchedEffect(blockId) {
        scope.launchSafe(onError = { error = describeLoadFailure(it) }) {
            val b = loadBlockForEnding(supabase, blockId)
            block = b
            val end = LocalDate.parse(b.row.endDate)
            val start = LocalDate.parse(b.row.startDate)
            lastDay = lastDay.coerceIn(start, end.minusDays(1).coerceAtLeast(start))
            val m = b.module
            if (m != null) {
                val maxes = repo.loadMaxes()
                val plates = repo.loadSettings().platesKg
                steps = stepUpMaxes(m, b.choice, { maxes[movementKey(it)]?.entry?.oneRmKg }, loadStepKg(plates))
            }
        }
    }

    val b = block
    val preview = b?.let { endEarlyPreview(it.sessions, lastDay) }
    val start = b?.let { LocalDate.parse(it.row.startDate) }
    val end = b?.let { LocalDate.parse(it.row.endDate) }
    val valid = b != null && start != null && end != null && !lastDay.isBefore(start) && lastDay.isBefore(end)
    val pages = listOf(FlowPage("LAST DAY", ready = valid), FlowPage("MAXES"))
    FTFlow(
        title = "END ${b?.row?.name?.uppercase() ?: "THE BLOCK"} EARLY", pages = pages, state = rememberFlowState(), onClose = onDismiss,
        canSave = valid && !saving, saving = saving, saveLabel = "END THE BLOCK", wash = FT.DomainTraining, error = error,
        onSave = {
            val bb = block ?: return@FTFlow
            saving = true
            scope.launchSafe(onError = { error = describeLoadFailure(it) }, onDone = { saving = false }) {
                endBlockEarly(supabase, bb, lastDay, if (stepUp) steps else emptyList())
                onDone()
            }
        },
    ) { page ->
        if (b == null || start == null || end == null) {
            Text(if (error == null) "Loading the block…" else "", style = FTType.BodySmall, color = FT.TextSecondary)
            return@FTFlow
        }
        if (page == 0) {
            FlowQuestion("When is the last day of this block?", "Planned ${start.format(DAY)} to ${end.format(DAY)}.")
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(thisSunday to "END OF THIS WEEK", thisSunday.plusWeeks(1) to "END OF NEXT WEEK").filter { (d, _) -> !d.isBefore(start) && d.isBefore(end) }
                    .forEach { (d, label) -> FlowTile("$label · ${d.format(DAY)}", lastDay == d) { lastDay = d } }
            }
            DateField("OR A DAY", lastDay, { lastDay = it })
            if (!valid) Text("Choose a day from ${start.format(DAY)} to ${end.minusDays(1).format(DAY)}.", style = FTType.Caption, color = FT.Warn)
            preview?.let { p ->
                Text(
                    "Keeps ${p.kept} session${if (p.kept == 1) "" else "s"}; ${p.dropped} still planned after ${lastDay.format(DAY)} are set aside (kept, not deleted).",
                    style = FTType.BodySmall, color = FT.TextPrimary,
                )
                p.droppedWeeks.take(5).forEach { w ->
                    Text("Week ${w.weekIndex}: ${w.sessions} session${if (w.sessions == 1) "" else "s"}" + (w.topPct?.let { " · up to ${it.roundToInt()} %" } ?: ""), style = FTType.MonoCaption, color = FT.TextSecondary)
                }
                Text("The next block can start ${lastDay.plusDays(1).format(DAY)}: PLAN A BLOCK starts there.", style = FTType.Caption, color = FT.TextMuted)
            }
        } else {
            FlowQuestion("The maxes for the next block", "The program steps maxes up after a completed cycle; this one stops before its end.")
            FlowField("MAXES") {
                FlowTile("KEEP TODAY'S MAXES (RECOMMENDED)", !stepUp) { stepUp = false }
                FlowTile("STEP THEM UP AS IF THE CYCLE WAS DONE", stepUp) { stepUp = true }
            }
            if (stepUp) {
                if (steps.isEmpty()) Text("No max to step up: the program has no progression, or no max is recorded.", style = FTType.Caption, color = FT.TextMuted)
                steps.take(6).forEach { s ->
                    Text("${s.exercise}: ${"%.1f".format(s.fromKg)} → ${"%.1f".format(s.toKg)} kg", style = FTType.MonoCaption, color = FT.TextSecondary)
                }
            }
        }
    }
}
