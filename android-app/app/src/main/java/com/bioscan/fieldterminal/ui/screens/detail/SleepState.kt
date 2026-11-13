package com.bioscan.fieldterminal.ui.screens.detail

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bioscan.fieldterminal.data.AnalysisRepository
import com.bioscan.fieldterminal.data.NutritionRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.model.MealRow
import com.bioscan.fieldterminal.data.model.SleepAnalysisRow
import com.bioscan.fieldterminal.data.model.TrainingLoadSessionRow
import com.bioscan.fieldterminal.data.model.WearableAnalysisRow
import com.bioscan.fieldterminal.data.model.WellbeingAnalysisRow
import com.bioscan.fieldterminal.domain.LoadSpans
import com.bioscan.fieldterminal.domain.SeriesBreakStarts
import com.bioscan.fieldterminal.ui.components.LoadGuard
import com.bioscan.fieldterminal.ui.components.parallel

// The Sleep page's data: the nights, and the series its cards read beside them.
@Stable
internal class SleepState {
    val guard = LoadGuard()

    var sleep by mutableStateOf<List<SleepAnalysisRow>?>(null)
    var wearable by mutableStateOf<List<WearableAnalysisRow>?>(null)
    var wellbeing by mutableStateOf<List<WellbeingAnalysisRow>?>(null)
    var sessions by mutableStateOf<List<TrainingLoadSessionRow>?>(null)
    var meals by mutableStateOf<List<MealRow>>(emptyList())
    var breakStarts by mutableStateOf(SeriesBreakStarts.NONE)
    var loadSpans by mutableStateOf(LoadSpans.STANDARD)

    suspend fun load() {
        guard.run {
            val repo = AnalysisRepository(SupabaseClientProvider.client)
            parallel(
                { sleep = repo.loadSleepDaily() },
                { wearable = repo.loadWearableDaily() },
                { wellbeing = repo.loadWellbeingDaily() },
                { sessions = repo.loadExerciseSessionsForTrainingLoad() },
                { meals = NutritionRepository(SupabaseClientProvider.client).loadRecentMeals(limit = 200) },
                { breakStarts = repo.loadSeriesBreakStarts() },
                { loadSpans = repo.loadLoadSpans() },
            )
        }
    }
}
