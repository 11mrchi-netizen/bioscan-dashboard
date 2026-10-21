package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.AnalysisRepository
import com.bioscan.fieldterminal.data.EvidenceRows
import com.bioscan.fieldterminal.data.ExperimentResultRow
import com.bioscan.fieldterminal.data.ExperimentRow
import com.bioscan.fieldterminal.data.ExperimentsRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.model.LogSexualActivityRow
import com.bioscan.fieldterminal.data.model.NewRegisteredQuestion
import com.bioscan.fieldterminal.data.model.PlannedOutcomeRow
import com.bioscan.fieldterminal.data.model.RegisteredQuestion
import com.bioscan.fieldterminal.data.model.RelationshipResultRow
import com.bioscan.fieldterminal.data.model.SleepAnalysisRow
import com.bioscan.fieldterminal.data.model.TrainingLoadSessionRow
import com.bioscan.fieldterminal.data.model.TypicalityResultRow
import com.bioscan.fieldterminal.data.model.WearableAnalysisRow
import com.bioscan.fieldterminal.data.model.WellbeingAnalysisRow
import com.bioscan.fieldterminal.domain.DailyChoice
import com.bioscan.fieldterminal.domain.LoadSpans
import com.bioscan.fieldterminal.domain.SeriesBreakStarts
import com.bioscan.fieldterminal.domain.defaultZone
import com.bioscan.fieldterminal.domain.evidenceBoard
import com.bioscan.fieldterminal.domain.typicalityView
import com.bioscan.fieldterminal.ui.components.LoadErrorCard
import com.bioscan.fieldterminal.ui.components.describeLoadFailure
import com.bioscan.fieldterminal.ui.components.launchSafe
import com.bioscan.fieldterminal.ui.components.parallel
import com.bioscan.fieldterminal.ui.components.rememberLoadGuard
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import com.bioscan.fieldterminal.util.Perf
import java.time.LocalDate

// App restructure: Lab › N=1, the personal-experiment cards that were on Today (what goes with what, what came before
// off mornings, today's draw and the daily tests, do the summaries earn their place) and the sexual-health correlations
// (were on Health › Sexual health). One load for the tab, the same reads Today made for them.
@Composable
fun N1Tab() {
    var sessions by remember { mutableStateOf<List<TrainingLoadSessionRow>?>(null) }
    var wearable by remember { mutableStateOf<List<WearableAnalysisRow>?>(null) }
    var sleep by remember { mutableStateOf<List<SleepAnalysisRow>?>(null) }
    var wellbeing by remember { mutableStateOf<List<WellbeingAnalysisRow>?>(null) }
    var sexualActivity by remember { mutableStateOf<List<LogSexualActivityRow>>(emptyList()) }
    var breakStarts by remember { mutableStateOf(SeriesBreakStarts.NONE) }
    var loadSpans by remember { mutableStateOf(LoadSpans.STANDARD) }
    var planned by remember { mutableStateOf<List<PlannedOutcomeRow>?>(null) }
    var relationships by remember { mutableStateOf<List<RelationshipResultRow>>(emptyList()) }
    var typicality by remember { mutableStateOf<TypicalityResultRow?>(null) }
    var evidence by remember { mutableStateOf(EvidenceRows()) }
    var registeredResults by remember { mutableStateOf<List<RelationshipResultRow>>(emptyList()) }
    var registeredQuestions by remember { mutableStateOf<List<RegisteredQuestion>>(emptyList()) }
    var registerError by remember { mutableStateOf<String?>(null) }
    var experiments by remember { mutableStateOf<List<ExperimentRow>>(emptyList()) }
    var experimentResults by remember { mutableStateOf<Map<String, ExperimentResultRow>>(emptyMap()) }
    var experimentsKey by remember { mutableIntStateOf(0) }
    var experimentError by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf<DailyChoice?>(null) }
    val scope = rememberCoroutineScope()

    // Optional enrichment, reloaded after a registration or a delete: a failed read just leaves the experiment cards out.
    LaunchedEffect(experimentsKey) {
        val repo = ExperimentsRepository(SupabaseClientProvider.client)
        runCatching { experiments = repo.loadExperiments() }
        runCatching { experimentResults = repo.loadResults() }
    }

    val guard = rememberLoadGuard()
    LaunchedEffect(guard.attempt) {
        Perf.timed("N1.load") {
            guard.run {
                val repo = AnalysisRepository(SupabaseClientProvider.client)
                parallel(
                    { sessions = repo.loadExerciseSessionsForTrainingLoad() },
                    { wearable = repo.loadWearableDaily() },
                    { sleep = repo.loadSleepDaily() },
                    { wellbeing = repo.loadWellbeingDaily() },
                    { sexualActivity = runCatching { repo.loadSexualActivityDaily() }.getOrDefault(emptyList()) },
                    { breakStarts = repo.loadSeriesBreakStarts() },
                    { loadSpans = repo.loadLoadSpans() },
                    { planned = runCatching { repo.loadPlannedOutcomes() }.getOrNull() },
                    { relationships = repo.loadRelationships() },
                    { typicality = repo.loadTypicality() },
                    { evidence = repo.loadEvidence() },
                    { registeredResults = repo.loadRegisteredResults() },
                    { registeredQuestions = repo.loadRegisteredQuestions() },
                )
            }
        }
    }

    val s = sessions
    val w = wearable
    val sl = sleep
    val wb = wellbeing
    if (s == null || w == null || sl == null || wb == null) {
        if (guard.error != null) {
            LoadErrorCard(guard)
        } else {
            Box(Modifier.fillMaxWidth().padding(vertical = 60.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = FT.DomainLabs)
            }
        }
        return
    }
    // Phase G: each composite's scorecard on this history, worked out off the main thread (null until ready).
    val scorecards by rememberScorecards(w, sl, wb, s, breakStarts, loadSpans, planned)

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        EvidenceBoardCard(evidenceBoard(relationships, registeredResults), evidence, onTest = { testing = it })
        CaseCrossoverCard(
            evidence.crossover,
            registered = registeredQuestions,
            registerError = registerError,
            onRegister = { exposure, outcome ->
                registerError = null
                scope.launchSafe(onError = { e -> registerError = describeLoadFailure(e) }) {
                    val repo = AnalysisRepository(SupabaseClientProvider.client)
                    repo.registerQuestion(
                        NewRegisteredQuestion(exposure = exposure, outcome = outcome, noEffect = defaultZone(outcome), registeredOn = LocalDate.now().toString()),
                    )
                    registeredQuestions = repo.loadRegisteredQuestions()
                }
            },
        )
        // Fix 6: today's draw sits on top of the experiments card as chips.
        N1ExperimentsCard(
            experiments.filter { it.protocol?.behaviour != null },
            experimentResults,
            title = "DAILY TESTS",
            onDelete = { e ->
                experimentError = null
                scope.launchSafe(onError = { err -> experimentError = describeLoadFailure(err) }) {
                    ExperimentsRepository(SupabaseClientProvider.client).delete(e.id)
                    experimentsKey++
                }
            },
        )
        experimentError?.let { Text(it, style = FTType.Caption, color = FT.Critical) }
        N1ScorecardsCard(scorecards, typicality = typicalityView(typicality).scorecard)
        if (sexualActivity.isNotEmpty()) SexualHealthCorrelationsCard(wb, sexualActivity, w, sl)
    }

    testing?.let { choice ->
        DailyExperimentSheet(choice, onDismiss = { testing = null }, onRegistered = { testing = null; experimentsKey++ })
    }
}
