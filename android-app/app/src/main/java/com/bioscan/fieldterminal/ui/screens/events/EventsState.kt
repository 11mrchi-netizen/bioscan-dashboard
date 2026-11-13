package com.bioscan.fieldterminal.ui.screens.events

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bioscan.fieldterminal.data.AthleteEventsRepository
import com.bioscan.fieldterminal.data.EventSuggestion
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.catching
import com.bioscan.fieldterminal.domain.events.AthleteEvent
import kotlinx.coroutines.CancellationException
import java.time.LocalDate

// The upcoming events, and finished sessions that look like one.
@Stable
internal class EventsState {
    private val repo = AthleteEventsRepository(SupabaseClientProvider.client)

    var events by mutableStateOf<List<AthleteEvent>?>(null)
    var suggestions by mutableStateOf<List<EventSuggestion>>(emptyList())
    var error by mutableStateOf<String?>(null)

    suspend fun load() {
        error = null
        try {
            catching { repo.linkCompletedEvents() }
            events = repo.loadUpcoming(LocalDate.now())
            suggestions = catching { repo.loadSuggestions() }.getOrDefault(emptyList())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Couldn't load events."
        }
    }
}
