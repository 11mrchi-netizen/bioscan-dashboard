package com.bioscan.fieldterminal.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.AgentRepository
import com.bioscan.fieldterminal.data.AgentShareGroup
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTConfirmDestructive
import com.bioscan.fieldterminal.ui.components.describeLoadFailure
import com.bioscan.fieldterminal.ui.components.launchSafe
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import kotlinx.coroutines.CancellationException

// Personal health agent, phase 1 (docs/personal-health-agent): the assistant's switch and what it may read, in the same
// per-stream style as the routine capture card. Off by default. Routine and location data have no switch: they are
// never shared with the assistant (agent-core/scope.ts), and the card says so.
@Composable
fun AssistantCard() {
    val repo = remember { AgentRepository(SupabaseClientProvider.client) }
    val scope = rememberCoroutineScope()
    var enabled by remember { mutableStateOf<Boolean?>(null) }
    var groups by remember { mutableStateOf(emptySet<AgentShareGroup>()) }
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleted by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            val s = repo.loadSettings()
            enabled = s.enabled
            groups = AgentShareGroup.entries.filter { it.key in s.optIn }.toSet()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = describeLoadFailure(e)
        }
    }

    fun save(on: Boolean, next: Set<AgentShareGroup>) {
        saving = true
        scope.launchSafe(onError = { error = it.message ?: "Could not save"; saving = false }) {
            repo.saveSettings(on, next)
            enabled = on
            groups = next
            error = null
            saving = false
        }
    }

    FTCard(
        title = "ASSISTANT",
        info = "WHY? on Today's cards asks the assistant to explain what the card says and what it rests on. It reads your " +
            "published results and records with your own sign-in, only the data shared below, and writes nothing but a " +
            "record of each question. Replies are checked against the data before they are shown. The model runs at a " +
            "provider that keeps no data and does not train on it.",
    ) {
        error?.let { Text(it, style = FTType.BodySmall, color = FT.Critical) }
        val on = enabled
        if (on == null && error == null) Text("Loading…", style = FTType.BodySmall, color = FT.TextSecondary)
        if (on != null) {
            Text(if (on) "ON" else "OFF", style = FTType.LabelCaps, color = if (on) FT.Emerald else FT.TextSecondary)
            if (on) {
                FTButton("TURN OFF", modifier = Modifier.fillMaxWidth(), enabled = !saving) { save(false, groups) }
            } else {
                FTButton("TURN ON", modifier = Modifier.fillMaxWidth(), enabled = !saving, emphasis = FTButtonEmphasis.Primary) { save(true, groups) }
            }
            Text("ALSO SHARE", style = FTType.LabelCaps, color = FT.TextSecondary)
            Text(
                "Wearable, sleep, training, nutrition, labs and the analysis results are shared while the assistant is on. " +
                    "Phone use, places, trips, other people and the calendar are never shared.",
                style = FTType.Caption,
                color = FT.TextMuted,
            )
            AgentShareGroup.entries.forEach { g ->
                val shared = g in groups
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(g.label, style = FTType.Body, color = FT.TextPrimary)
                        Text(if (shared) "SHARED" else "NOT SHARED", style = FTType.Micro, color = FT.TextSecondary)
                    }
                    FTButton(if (shared) "STOP" else "SHARE", enabled = !saving) { save(on, if (shared) groups - g else groups + g) }
                }
            }
            when {
                deleted -> Text("History deleted.", style = FTType.BodySmall, color = FT.TextSecondary)
                confirmDelete -> FTConfirmDestructive(
                    message = "Delete every question you asked and every reply? This cannot be undone.",
                    confirmLabel = "DELETE HISTORY",
                    busy = saving,
                    onCancel = { confirmDelete = false },
                ) {
                    saving = true
                    scope.launchSafe(onError = { error = it.message ?: "Could not delete"; saving = false }) {
                        repo.deleteHistory()
                        deleted = true
                        confirmDelete = false
                        saving = false
                    }
                }
                else -> FTButton("DELETE HISTORY", emphasis = FTButtonEmphasis.Destructive, modifier = Modifier.fillMaxWidth()) { confirmDelete = true }
            }
        }
    }
}
