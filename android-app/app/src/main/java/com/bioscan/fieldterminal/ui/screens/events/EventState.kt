package com.bioscan.fieldterminal.ui.screens.events

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bioscan.fieldterminal.data.AthleteEventsRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.domain.events.AthleteEvent
import com.bioscan.fieldterminal.ui.components.LoadGuard

// One event: what it is, and what was planned around it.
@Stable
internal class EventState {
    val guard = LoadGuard()
    private val repo = AthleteEventsRepository(SupabaseClientProvider.client)

    var event by mutableStateOf<AthleteEvent?>(null)

    suspend fun load(eventId: Long) {
        guard.run { event = repo.load(eventId) }
    }
}
