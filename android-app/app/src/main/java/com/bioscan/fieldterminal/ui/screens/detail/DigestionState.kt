package com.bioscan.fieldterminal.ui.screens.detail

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bioscan.fieldterminal.data.AnalysisRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.softLoad
import com.bioscan.fieldterminal.data.model.StoolAnalysisRow
import com.bioscan.fieldterminal.ui.components.LoadGuard
import java.time.LocalDate

// The Digestion page's data: the stool log and the daily digestion score (derived_daily, written nightly).
@Stable
internal class DigestionState {
    val guard = LoadGuard()

    var stool by mutableStateOf<List<StoolAnalysisRow>?>(null)
    var score by mutableStateOf<Map<LocalDate, Double>>(emptyMap())

    suspend fun load() {
        val repo = AnalysisRepository(SupabaseClientProvider.client)
        guard.run { stool = repo.loadStoolLog() }
        // The score is extra: the page still works when it cannot be read.
        score = softLoad { repo.loadDerivedDaily("gut_day_score") }.orEmpty()
    }
}
