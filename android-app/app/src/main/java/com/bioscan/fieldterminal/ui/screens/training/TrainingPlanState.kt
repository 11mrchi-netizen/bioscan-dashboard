package com.bioscan.fieldterminal.ui.screens.training

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bioscan.fieldterminal.data.AthleteEventsRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.TrainingCyclesRepository
import com.bioscan.fieldterminal.data.TrainingProgramRepository
import com.bioscan.fieldterminal.data.catching
import com.bioscan.fieldterminal.data.model.GeneratedBlockRow
import com.bioscan.fieldterminal.data.model.TrainingBlockSummaryRow
import com.bioscan.fieldterminal.data.model.UpcomingSessionRow
import com.bioscan.fieldterminal.domain.TrainingCycle
import com.bioscan.fieldterminal.ui.components.LoadGuard
import com.bioscan.fieldterminal.ui.components.parallel
import java.time.LocalDate

// PLAN › TRAINING: the blocks, what is coming up, event days and the cycles behind them.
@Stable
internal class TrainingPlanState {
    val guard = LoadGuard()
    private val repo = TrainingProgramRepository(SupabaseClientProvider.client)
    private val cyclesRepo = TrainingCyclesRepository(SupabaseClientProvider.client)

    var blocks by mutableStateOf<List<GeneratedBlockRow>?>(null)
    var upcoming by mutableStateOf<List<UpcomingSessionRow>>(emptyList())
    // Fuel planning phase 9: a planned session on an event's day is shown as such (the event is the day's real work).
    var eventDays by mutableStateOf<Map<LocalDate, String>>(emptyMap())
    var cycles by mutableStateOf<List<TrainingCycle>?>(null)
    var history by mutableStateOf<List<TrainingBlockSummaryRow>?>(null)

    suspend fun load() {
        guard.run {
            parallel(
                { blocks = repo.loadGeneratedBlocks() },
                { upcoming = repo.loadUpcoming(LocalDate.now()) },
                { cycles = cyclesRepo.loadCycles() },
                { history = cyclesRepo.loadBlockSummaries() },
                {
                    eventDays = catching {
                        AthleteEventsRepository(SupabaseClientProvider.client).loadUpcoming(LocalDate.now(), days = 120)
                            .associate { it.date to it.name }
                    }.getOrDefault(emptyMap())
                },
            )
        }
    }
}
