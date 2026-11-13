package com.bioscan.fieldterminal.ui.screens.detail

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bioscan.fieldterminal.data.AnalysisRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.model.OstrcAnalysisRow
import com.bioscan.fieldterminal.data.model.TrainingLoadSessionRow
import com.bioscan.fieldterminal.data.model.WearableAnalysisRow
import com.bioscan.fieldterminal.data.model.WellbeingAnalysisRow
import com.bioscan.fieldterminal.domain.LoadSpans
import com.bioscan.fieldterminal.ui.components.LoadGuard
import com.bioscan.fieldterminal.ui.components.parallel

// The Injury Risk page's data: sessions, daily readings, wellbeing and the weekly OSTRC answers.
@Stable
internal class InjuryState {
    val guard = LoadGuard()

    var sessions by mutableStateOf<List<TrainingLoadSessionRow>?>(null)
    var wearable by mutableStateOf<List<WearableAnalysisRow>?>(null)
    var wellbeing by mutableStateOf<List<WellbeingAnalysisRow>?>(null)
    var ostrc by mutableStateOf<List<OstrcAnalysisRow>?>(null)
    var spans by mutableStateOf(LoadSpans.STANDARD)

    suspend fun load() {
        guard.run {
            val repo = AnalysisRepository(SupabaseClientProvider.client)
            parallel(
                { sessions = repo.loadExerciseSessionsForTrainingLoad() },
                { wearable = repo.loadWearableDaily() },
                { wellbeing = repo.loadWellbeingDaily() },
                { ostrc = repo.loadOstrcCheckins() },
                { spans = repo.loadLoadSpans() },
            )
        }
    }
}
