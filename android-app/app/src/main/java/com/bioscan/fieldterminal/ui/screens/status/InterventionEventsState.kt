package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bioscan.fieldterminal.data.ExperimentsRepository
import com.bioscan.fieldterminal.data.InterventionEventsRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.catching
import com.bioscan.fieldterminal.data.model.DrawRow
import com.bioscan.fieldterminal.data.model.EpisodicIntervention
import com.bioscan.fieldterminal.data.model.EventEvidenceRow
import com.bioscan.fieldterminal.data.model.EventRow
import com.bioscan.fieldterminal.data.model.ExpectationRow
import com.bioscan.fieldterminal.data.model.TrackedOutcomeRow

// The interventions, what each is expected to do, their events and the evidence so far.
@Stable
internal class InterventionEventsState {
    private val repo = InterventionEventsRepository(SupabaseClientProvider.client)

    var interventions by mutableStateOf<List<EpisodicIntervention>>(emptyList())
    var expectations by mutableStateOf<List<ExpectationRow>>(emptyList())
    var events by mutableStateOf<List<EventRow>>(emptyList())
    var evidence by mutableStateOf<Map<String, EventEvidenceRow>>(emptyMap())
    var tracked by mutableStateOf<List<TrackedOutcomeRow>>(emptyList())
    // intervention ids that already have a registered experiment
    var tested by mutableStateOf<Set<String>>(emptySet())
    var draws by mutableStateOf<List<DrawRow>>(emptyList())

    suspend fun load() {
        interventions = repo.loadInterventions()
        expectations = repo.loadExpectations()
        events = repo.loadRecentEvents()
        evidence = repo.loadEvidence()
        draws = repo.loadDraws()
        tracked = repo.loadTrackedOutcomes()
        tested = catching { ExperimentsRepository(SupabaseClientProvider.client).loadExperiments().mapNotNull { it.interventionId }.toSet() }.getOrDefault(emptySet())
    }
}
