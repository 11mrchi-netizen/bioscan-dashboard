package com.bioscan.fieldterminal.ui.screens.fuel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.screens.EnduranceFuelForm
import com.bioscan.fieldterminal.ui.screens.TextChipRow
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// Add entry > Run fuel (docs/race-fuel-rework/plan.md): pick the activity, then the debrief against the schedule made for it.
// Logging one intake at a time is still there, as the second choice, for something taken that no schedule covered.

private val WHEN_FMT = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH)

private fun label(s: FuelingSessionRow): String {
    val whenText = runCatching { OffsetDateTime.parse(s.startTime).toLocalDateTime().format(WHEN_FMT) }.getOrDefault(s.startTime.take(16))
    return "${s.type.uppercase()} · $whenText" + (s.durationMin?.let { " · ${it.toInt()} MIN" } ?: "")
}

@Composable
internal fun RunFuelEntry(onSaved: () -> Unit) {
    val repo = remember { FuelDebriefRepository(SupabaseClientProvider.client) }
    var sessions by remember { mutableStateOf<List<FuelingSessionRow>?>(null) }
    var picked by remember { mutableStateOf<FuelingSessionRow?>(null) }
    var single by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        sessions = runCatching { FuelingRepository(SupabaseClientProvider.client).recentSessions() }.getOrDefault(emptyList()).also { if (picked == null) picked = it.firstOrNull() }
    }
    val target by produceState<DebriefTarget?>(initialValue = null, picked?.id) {
        value = picked?.let { runCatching { repo.targetForSession(it) }.getOrNull() }
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        when {
            single -> {
                EnduranceFuelForm(onSaved = onSaved)
                FTButton("BACK TO THE DEBRIEF", emphasis = FTButtonEmphasis.Tertiary, modifier = Modifier.fillMaxWidth()) { single = false }
            }
            sessions == null -> Text("Loading…", style = FTType.BodySmall, color = FT.TextSecondary)
            sessions!!.isEmpty() -> {
                Text("No run, hike or ride in the last three weeks to debrief. Sync your watch or log the activity first.", style = FTType.BodySmall, color = FT.TextSecondary)
            }
            else -> {
                Text("ACTIVITY", color = FT.TextMuted, style = FTType.LabelCaps)
                val labels = sessions!!.associateBy({ label(it) }, { it })
                TextChipRow(labels.keys.toList(), picked?.let { label(it) }, perRow = 1) { chosen -> if (chosen != null) picked = labels[chosen] }
                target?.let { t -> androidx.compose.runtime.key(t.sessionId) { FuelDebriefContent(t, onSaved) } }
                FTButton("LOG ONE INTAKE INSTEAD", emphasis = FTButtonEmphasis.Tertiary, modifier = Modifier.fillMaxWidth()) { single = true }
            }
        }
    }
}
