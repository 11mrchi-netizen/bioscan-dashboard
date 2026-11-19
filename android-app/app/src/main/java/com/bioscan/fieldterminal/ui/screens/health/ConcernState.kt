package com.bioscan.fieldterminal.ui.screens.health

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bioscan.fieldterminal.data.HealthConcernsRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.model.ConcernStateDto
import com.bioscan.fieldterminal.data.model.FindingDto
import com.bioscan.fieldterminal.data.model.HealthConcernRow
import com.bioscan.fieldterminal.data.model.HealthObservationRow
import com.bioscan.fieldterminal.data.model.TrialReadDto
import com.bioscan.fieldterminal.data.softLoad
import com.bioscan.fieldterminal.ui.components.LoadGuard
import com.bioscan.fieldterminal.ui.components.parallel

// One health concern: its state, patterns, trials and latest entry.
@Stable
internal class ConcernState {
    val guard = LoadGuard()
    private val repo = HealthConcernsRepository(SupabaseClientProvider.client)

    var concern by mutableStateOf<HealthConcernRow?>(null)
    var state by mutableStateOf<ConcernStateDto?>(null)
    var findings by mutableStateOf<List<FindingDto>>(emptyList())
    var trials by mutableStateOf<List<TrialReadDto>>(emptyList())
    var lastEntry by mutableStateOf<HealthObservationRow?>(null)

    suspend fun load(concernId: String) {
        guard.run {
            parallel(
                { concern = repo.loadConcerns().firstOrNull { it.id == concernId } },
                { state = repo.loadConcernState(concernId) },
                { findings = repo.loadFindings(concernId) },
                { trials = repo.loadTrialReads().filter { it.concernId == concernId } },
                { lastEntry = softLoad { repo.loadLatestObservations(20).firstOrNull { it.concernId == concernId } } },
            )
        }
    }
}
