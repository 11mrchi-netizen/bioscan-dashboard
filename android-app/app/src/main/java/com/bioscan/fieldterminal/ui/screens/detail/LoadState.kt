package com.bioscan.fieldterminal.ui.screens.detail

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bioscan.fieldterminal.data.AnalysisRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.model.FitnessFatigueRow
import com.bioscan.fieldterminal.data.model.TrainingLoadSessionRow
import com.bioscan.fieldterminal.data.model.WearableAnalysisRow
import com.bioscan.fieldterminal.domain.LoadSpans
import com.bioscan.fieldterminal.ui.components.LoadGuard
import com.bioscan.fieldterminal.ui.components.parallel

// The Training Load page's data: sessions, the daily readings beside them, and fitness against fatigue.
@Stable
internal class LoadState {
    val guard = LoadGuard()

    var sessions by mutableStateOf<List<TrainingLoadSessionRow>?>(null)
    var wearable by mutableStateOf<List<WearableAnalysisRow>?>(null)
    var fitnessFatigue by mutableStateOf<FitnessFatigueRow?>(null)
    var spans by mutableStateOf(LoadSpans.STANDARD)

    suspend fun load() {
        guard.run {
            val repo = AnalysisRepository(SupabaseClientProvider.client)
            parallel(
                { sessions = repo.loadExerciseSessionsForTrainingLoad() },
                { wearable = repo.loadWearableDaily() },
                { fitnessFatigue = repo.loadFitnessFatigue(); spans = repo.loadLoadSpans() },
            )
        }
    }
}
