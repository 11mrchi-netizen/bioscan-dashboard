package com.bioscan.fieldterminal.ui.screens.detail

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bioscan.fieldterminal.data.AnalysisRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.ZeppStressRepository
import com.bioscan.fieldterminal.data.model.WellbeingAnalysisRow
import com.bioscan.fieldterminal.domain.stress.StressDay
import com.bioscan.fieldterminal.ui.components.LoadGuard
import com.bioscan.fieldterminal.ui.components.parallel

// The Wellbeing page's data: the daily check-ins and the stress days.
@Stable
internal class WellbeingState {
    val guard = LoadGuard()

    var wellbeing by mutableStateOf<List<WellbeingAnalysisRow>?>(null)
    var stress by mutableStateOf<List<StressDay>>(emptyList())

    suspend fun load() {
        guard.run {
            parallel(
                { wellbeing = AnalysisRepository(SupabaseClientProvider.client).loadWellbeingDaily() },
                { stress = ZeppStressRepository(SupabaseClientProvider.client).loadStressDays() },
            )
        }
    }
}
