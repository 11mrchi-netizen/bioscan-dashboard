package com.bioscan.fieldterminal.ui.screens.detail

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bioscan.fieldterminal.data.AnalysisRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.model.LogSexualActivityRow
import com.bioscan.fieldterminal.data.model.WellbeingAnalysisRow
import com.bioscan.fieldterminal.ui.components.LoadGuard
import com.bioscan.fieldterminal.ui.components.parallel

// The Sexual Health page's data: wellbeing and logged activity.
@Stable
internal class SexualHealthState {
    val guard = LoadGuard()

    var wellbeing by mutableStateOf<List<WellbeingAnalysisRow>?>(null)
    var activity by mutableStateOf<List<LogSexualActivityRow>?>(null)

    suspend fun load() {
        guard.run {
            val repo = AnalysisRepository(SupabaseClientProvider.client)
            parallel({ wellbeing = repo.loadWellbeingDaily() }, { activity = repo.loadSexualActivityDaily() })
        }
    }
}
