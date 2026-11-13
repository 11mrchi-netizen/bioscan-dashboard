package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bioscan.fieldterminal.data.LabWatchRepository
import com.bioscan.fieldterminal.data.LabsOverview
import com.bioscan.fieldterminal.data.LabsRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.catching
import com.bioscan.fieldterminal.domain.BloodworkMarkerEvaluation
import com.bioscan.fieldterminal.domain.LabWatch

// LAB › TESTS: the draws, each marker's change against its own noise, and the markers worth watching.
@Stable
internal class LabsState {
    var overview by mutableStateOf<LabsOverview?>(null)
    // Category 9: each marker's change between its last two draws against its own noise threshold (RCV), by name. A
    // failed read leaves the rows without it.
    var rcv by mutableStateOf<Map<String, BloodworkMarkerEvaluation>>(emptyMap())
    var isLoading by mutableStateOf(true)
    var error by mutableStateOf<String?>(null)
    // Lab watch: markers worth watching for what the regimen holds. Enrichment: a failed read hides it.
    var watches by mutableStateOf<List<LabWatch>>(emptyList())

    suspend fun load() {
        isLoading = overview == null
        error = null
        try {
            overview = LabsRepository(SupabaseClientProvider.client).loadOverview()
            rcv = catching { loadRcv() }.getOrDefault(emptyMap())
            watches = catching { LabWatchRepository(SupabaseClientProvider.client).watches() }.getOrDefault(watches)
        } catch (e: Exception) {
            error = e.message ?: "Unknown error"
        }
        isLoading = false
    }
}
