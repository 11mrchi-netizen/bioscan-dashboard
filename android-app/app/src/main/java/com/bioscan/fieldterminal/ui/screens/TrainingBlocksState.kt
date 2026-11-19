package com.bioscan.fieldterminal.ui.screens

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.TrainingCyclesRepository
import com.bioscan.fieldterminal.data.model.TrainingBlockSummaryRow
import com.bioscan.fieldterminal.ui.components.LoadGuard

// The training blocks, newest first.
@Stable
internal class TrainingBlocksState {
    val guard = LoadGuard()

    var blocks by mutableStateOf<List<TrainingBlockSummaryRow>?>(null)

    suspend fun load() {
        guard.run { blocks = TrainingCyclesRepository(SupabaseClientProvider.client).loadBlockSummaries() }
    }
}
