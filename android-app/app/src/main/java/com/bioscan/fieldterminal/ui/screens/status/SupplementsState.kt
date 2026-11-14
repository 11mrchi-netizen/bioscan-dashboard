package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bioscan.fieldterminal.data.EvidenceRepository
import com.bioscan.fieldterminal.data.ExperimentResultRow
import com.bioscan.fieldterminal.data.ExperimentRow
import com.bioscan.fieldterminal.data.ExperimentsRepository
import com.bioscan.fieldterminal.data.InteractionRepository
import com.bioscan.fieldterminal.data.RegimenReferenceRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.SupplementsOverview
import com.bioscan.fieldterminal.data.SupplementsRepository
import com.bioscan.fieldterminal.data.catching
import com.bioscan.fieldterminal.domain.InteractionRecord
import com.bioscan.fieldterminal.domain.LabelMention
import com.bioscan.fieldterminal.domain.RosterItem
import com.bioscan.fieldterminal.domain.labelMentions

// LAB › SUPPLEMENTS: the regimen, with the evidence, experiments, interactions and label notes that go with it.
@Stable
internal class SupplementsState {
    var overview by mutableStateOf<SupplementsOverview?>(null)
    var outcomes by mutableStateOf<Map<Long, String>>(emptyMap())
    var isLoading by mutableStateOf(true)
    var experiments by mutableStateOf<List<ExperimentRow>>(emptyList())
    var experimentResults by mutableStateOf<Map<String, ExperimentResultRow>>(emptyMap())
    // Curated interaction registry checked against the active roster.
    var interactionRecords by mutableStateOf<List<InteractionRecord>>(emptyList())
    var rosterItems by mutableStateOf<List<RosterItem>>(emptyList())
    // Reference cards: the other active items each FDA label names (owner's name, mention).
    var labelNotes by mutableStateOf<List<Pair<String, LabelMention>>>(emptyList())
    var error by mutableStateOf<String?>(null)

    suspend fun load() {
        // The kept roster stays on screen while a fresher one loads.
        isLoading = overview == null
        error = null
        try {
            val loaded = SupplementsRepository(SupabaseClientProvider.client).loadOverview()
            overview = loaded
            // Evidence is enrichment: a failed lookup must not hide the roster.
            outcomes = catching { EvidenceRepository(SupabaseClientProvider.client).outcomeTextBySupplement(loaded.active) }
                .getOrDefault(emptyMap())
            // Enrichment too: no registry or a failed lookup just hides the card.
            catching {
                val interactionRepo = InteractionRepository(SupabaseClientProvider.client)
                interactionRecords = interactionRepo.loadRecords()
                rosterItems = interactionRepo.rosterItems(loaded.active)
            }.onFailure { interactionRecords = emptyList(); rosterItems = emptyList() }
            labelNotes = catching {
                val refs = RegimenReferenceRepository(SupabaseClientProvider.client).loadAll()
                val active = loaded.active.associateBy { it.id }
                refs.flatMap { ref ->
                    val owner = active[ref.supplementId] ?: return@flatMap emptyList()
                    val label = ref.label ?: return@flatMap emptyList()
                    labelMentions(label.sections, loaded.active.filter { it.id != owner.id }.map { it.id to it.name })
                        .map { owner.name to it }
                }
            }.getOrDefault(emptyList())
            val expRepo = ExperimentsRepository(SupabaseClientProvider.client)
            // Daily tests of a behaviour live on the Today page; supplements and fueling products here.
            experiments = expRepo.loadExperiments().filter { it.protocol?.behaviour == null }
            experimentResults = expRepo.loadResults()
        } catch (e: Exception) {
            error = e.message ?: "Unknown error"
        }
        isLoading = false
    }
}
