package com.bioscan.fieldterminal.ui.screens.events

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.AthleteEventsRepository
import com.bioscan.fieldterminal.data.EventSuggestion
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.draftEventFrom
import com.bioscan.fieldterminal.domain.events.AthleteEvent
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.LocalDate

// Training › Plan's EVENTS card: what is coming up outside the training block (races, long days, matches), ADD EVENT,
// and calendar entries that read like a race (MAKE THIS AN EVENT). Loading also links finished events to the session
// that recorded them.
@Composable
fun EventsCard(onOpenEvent: (Long) -> Unit) {
    val repo = remember { AthleteEventsRepository(SupabaseClientProvider.client) }
    var events by remember { mutableStateOf<List<AthleteEvent>?>(null) }
    var suggestions by remember { mutableStateOf<List<EventSuggestion>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableStateOf(0) }
    var adding by remember { mutableStateOf<AthleteEvent?>(null) }
    var addingBlank by remember { mutableStateOf(false) }

    LaunchedEffect(attempt) {
        error = null
        try {
            runCatching { repo.linkCompletedEvents() }
            events = repo.loadUpcoming(LocalDate.now())
            suggestions = runCatching { repo.loadSuggestions() }.getOrDefault(emptyList())
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Couldn't load events."
        }
    }

    FTCard(
        title = "EVENTS",
        info = "Races, long days, matches and other hard days outside your training plan. Fuel and hydration plans are built " +
            "around them, and a finished event is linked to the session that recorded it.",
    ) {
        error?.let {
            Text("Couldn't load events: $it", style = FTType.BodySmall, color = FT.Critical)
            FTButton("RETRY", emphasis = FTButtonEmphasis.Tertiary) { attempt++ }
        }
        val list = events.orEmpty()
        if (events != null && list.isEmpty()) Text("No events planned.", style = FTType.BodySmall, color = FT.TextSecondary)
        list.forEach { e ->
            Row(
                Modifier.fillMaxWidth().ftClickable(onClickLabel = "Open ${e.name}") { e.id?.let(onOpenEvent) }.padding(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(e.name, style = FTType.BodySmall, color = FT.TextPrimary)
                    Text(eventWhenLabel(e), style = FTType.MonoCaption, color = FT.DomainTraining)
                    Text(eventSummaryLine(e), style = FTType.MonoCaption, color = FT.TextSecondary)
                }
                Text("›", style = FTType.LabelCaps, color = FT.TextSecondary)
            }
        }
        suggestions.take(3).forEach { s ->
            Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("FROM YOUR CALENDAR", style = FTType.LabelCaps, color = FT.TextSecondary)
                Text("${s.calendarEvent.title.orEmpty()} · ${s.start.toLocalDate()}", style = FTType.BodySmall, color = FT.TextPrimary)
                FTButton("MAKE THIS AN EVENT", emphasis = FTButtonEmphasis.Tertiary) { adding = draftEventFrom(s) }
            }
        }
        FTButton("ADD EVENT", modifier = Modifier.padding(top = 6.dp)) { addingBlank = true }
    }

    if (addingBlank || adding != null) {
        EventFormSheet(
            initial = adding,
            onDismiss = { addingBlank = false; adding = null },
            onSaved = { saved ->
                addingBlank = false
                adding = null
                attempt++
                saved.id?.let(onOpenEvent)
            },
        )
    }
}
