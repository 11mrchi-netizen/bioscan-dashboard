package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.bioscan.fieldterminal.data.AnalysisRepository
import com.bioscan.fieldterminal.data.EvidenceRows
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.catching
import com.bioscan.fieldterminal.data.model.LogSexualActivityRow
import com.bioscan.fieldterminal.data.model.PlannedOutcomeRow
import com.bioscan.fieldterminal.data.model.RegisteredQuestion
import com.bioscan.fieldterminal.data.model.RelationshipResultRow
import com.bioscan.fieldterminal.data.model.SleepAnalysisRow
import com.bioscan.fieldterminal.data.model.TrainingLoadSessionRow
import com.bioscan.fieldterminal.data.model.TypicalityResultRow
import com.bioscan.fieldterminal.data.model.WearableAnalysisRow
import com.bioscan.fieldterminal.data.model.WellbeingAnalysisRow
import com.bioscan.fieldterminal.domain.LoadSpans
import com.bioscan.fieldterminal.domain.SeriesBreakStarts
import com.bioscan.fieldterminal.ui.components.LoadGuard
import com.bioscan.fieldterminal.ui.components.parallel
import com.bioscan.fieldterminal.util.Perf

// LAB › N=1: the series the relationship cards and scorecards are worked out from.
@Stable
internal class N1State {
    val guard = LoadGuard()

    var sessions by mutableStateOf<List<TrainingLoadSessionRow>?>(null)
    var wearable by mutableStateOf<List<WearableAnalysisRow>?>(null)
    var sleep by mutableStateOf<List<SleepAnalysisRow>?>(null)
    var wellbeing by mutableStateOf<List<WellbeingAnalysisRow>?>(null)
    var sexualActivity by mutableStateOf<List<LogSexualActivityRow>>(emptyList())
    var breakStarts by mutableStateOf(SeriesBreakStarts.NONE)
    var loadSpans by mutableStateOf(LoadSpans.STANDARD)
    var planned by mutableStateOf<List<PlannedOutcomeRow>?>(null)
    var relationships by mutableStateOf<List<RelationshipResultRow>>(emptyList())
    var typicality by mutableStateOf<TypicalityResultRow?>(null)
    var evidence by mutableStateOf(EvidenceRows())
    var registeredResults by mutableStateOf<List<RelationshipResultRow>>(emptyList())
    var registeredQuestions by mutableStateOf<List<RegisteredQuestion>>(emptyList())

    suspend fun load() {
        Perf.timed("N1.load") {
            guard.run {
                val repo = AnalysisRepository(SupabaseClientProvider.client)
                parallel(
                    { sessions = repo.loadExerciseSessionsForTrainingLoad() },
                    { wearable = repo.loadWearableDaily() },
                    { sleep = repo.loadSleepDaily() },
                    { wellbeing = repo.loadWellbeingDaily() },
                    { sexualActivity = catching { repo.loadSexualActivityDaily() }.getOrDefault(emptyList()) },
                    { breakStarts = repo.loadSeriesBreakStarts() },
                    { loadSpans = repo.loadLoadSpans() },
                    { planned = catching { repo.loadPlannedOutcomes() }.getOrNull() },
                    { relationships = repo.loadRelationships() },
                    { typicality = repo.loadTypicality() },
                    { evidence = repo.loadEvidence() },
                    { registeredResults = repo.loadRegisteredResults() },
                    { registeredQuestions = repo.loadRegisteredQuestions() },
                )
            }
        }
    }
}
