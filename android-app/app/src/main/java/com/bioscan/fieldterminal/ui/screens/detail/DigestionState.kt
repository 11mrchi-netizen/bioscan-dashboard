package com.bioscan.fieldterminal.ui.screens.detail

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bioscan.fieldterminal.data.AnalysisRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.model.StoolAnalysisRow
import com.bioscan.fieldterminal.ui.components.LoadGuard

// The Digestion page's data: the stool log.
@Stable
internal class DigestionState {
    val guard = LoadGuard()

    var stool by mutableStateOf<List<StoolAnalysisRow>?>(null)

    suspend fun load() {
        guard.run { stool = AnalysisRepository(SupabaseClientProvider.client).loadStoolLog() }
    }
}
