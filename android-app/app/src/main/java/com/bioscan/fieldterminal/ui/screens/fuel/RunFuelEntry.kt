package com.bioscan.fieldterminal.ui.screens.fuel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.DebriefTarget
import com.bioscan.fieldterminal.data.FuelDebriefRepository
import com.bioscan.fieldterminal.data.FuelingRepository
import com.bioscan.fieldterminal.data.FuelingSessionRow
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTFlow
import com.bioscan.fieldterminal.ui.components.FlowPage
import com.bioscan.fieldterminal.ui.components.FlowPickGrid
import com.bioscan.fieldterminal.ui.components.FlowQuestion
import com.bioscan.fieldterminal.ui.components.rememberFlowState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.LoadErrorLine
import com.bioscan.fieldterminal.ui.components.LoadingOrError
import com.bioscan.fieldterminal.ui.components.produceGuarded
import com.bioscan.fieldterminal.ui.components.rememberGuarded
import com.bioscan.fieldterminal.ui.components.rememberLoadGuard
import com.bioscan.fieldterminal.ui.screens.EnduranceFuelForm
import com.bioscan.fieldterminal.ui.screens.TextChipRow
import com.bioscan.fieldterminal.ui.theme.FTType
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// Add entry > Run fuel (docs/race-fuel-rework/plan.md): pick the activity, then the debrief against the schedule made for it.
// Logging one intake at a time is still there, as the second choice, for something taken that no schedule covered.

private val WHEN_FMT = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH)

private fun label(s: FuelingSessionRow): String {
    val whenText = runCatching { OffsetDateTime.parse(s.startTime).toLocalDateTime().format(WHEN_FMT) }.getOrDefault(s.startTime.take(16))
    return "${s.type.uppercase()} · $whenText" + (s.durationMin?.let { " · ${it.toInt()} MIN" } ?: "")
}

/**
 * Add entry › Run fuel as a full-screen flow: ACTIVITY (the recent runs, rides and hikes; a tap moves on), then the
 * debrief's two pages for the one picked. LOG ONE INTAKE INSTEAD swaps the flow for the single-intake form.
 */
@Composable
internal fun RunFuelFlow(onDismiss: () -> Unit, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val repo = remember { FuelDebriefRepository(SupabaseClientProvider.client) }
    val (sessions, guard) = rememberGuarded { FuelingRepository(SupabaseClientProvider.client).recentSessions() }
    var picked by remember { mutableStateOf<FuelingSessionRow?>(null) }
    var single by remember { mutableStateOf(false) }
    LaunchedEffect(sessions) { if (picked == null) picked = sessions?.firstOrNull() }
    val targetGuard = rememberLoadGuard(refreshOnUpdates = false)
    val target by produceGuarded<DebriefTarget?>(targetGuard, null, picked?.id) { picked?.let { repo.targetForSession(it) } }
    val flow = rememberFlowState()
    // ACTIVITY is a choice: a tap moves on, once that activity's debrief has loaded.
    var advance by remember { mutableStateOf(false) }
    val t = target
    key(t?.sessionId, single) {
        val d = t?.let { rememberDebriefState(it) }
        val suggestion = d?.suggestion
        LaunchedEffect(advance, d) { if (advance && d != null && flow.page == 0) { advance = false; flow.next() } }
        val pages = when {
            single -> listOf(FlowPage("ONE INTAKE"))
            suggestion != null -> listOf(FlowPage("SUGGESTION"))
            else -> listOf(FlowPage("ACTIVITY", ready = d != null)) + d?.pages.orEmpty()
        }
        FTFlow(
            title = "RUN FUEL", pages = pages, state = flow, onClose = onDismiss,
            canSave = d?.canSave == true, saving = d?.saving == true, saveLabel = "SAVE DEBRIEF", wash = FT.Domain.Fuel, error = d?.error,
            onSave = { d?.save(context, scope, onSaved) },
            bottomBar = when {
                single -> { _ ->
                    Row(Modifier.fillMaxWidth().background(FT.Surface).padding(horizontal = 16.dp, vertical = 10.dp)) {
                        FTButton("BACK TO THE DEBRIEF", modifier = Modifier.weight(1f)) { single = false }
                    }
                }
                suggestion != null -> { _ -> ToleranceBar(suggestion, onSaved) }
                else -> null
            },
        ) { page ->
            when {
                single -> EnduranceFuelForm(onSaved = onSaved)
                suggestion != null -> ToleranceResult(suggestion)
                page == 0 -> when {
                    sessions == null -> LoadingOrError(guard, "Loading…")
                    sessions.isEmpty() -> Text("No run, hike or ride in the last three weeks to debrief. Sync your watch or log the activity first.", style = FTType.BodySmall, color = FT.TextSecondary)
                    else -> {
                        FlowQuestion("Which activity?", "The debrief is checked against the fuel schedule made for it.")
                        FlowPickGrid(sessions.map { it.id.toString() to label(it) }, setOfNotNull(picked?.id?.toString()), { id ->
                            picked = sessions.firstOrNull { it.id.toString() == id }
                            advance = true
                        }, columns = 1, single = true)
                        LoadErrorLine(targetGuard, "the debrief for this activity")
                        FTButton("LOG ONE INTAKE INSTEAD", emphasis = FTButtonEmphasis.Tertiary, modifier = Modifier.fillMaxWidth()) { single = true }
                    }
                }
                else -> d?.Page(page - 1)
            }
        }
    }
}
