package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.AnalysisRepository
import com.bioscan.fieldterminal.data.ExperimentResultRow
import com.bioscan.fieldterminal.data.ExperimentRow
import com.bioscan.fieldterminal.data.ExperimentsRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.catching
import com.bioscan.fieldterminal.data.model.NewRegisteredQuestion
import com.bioscan.fieldterminal.domain.DailyChoice
import com.bioscan.fieldterminal.domain.defaultZone
import com.bioscan.fieldterminal.domain.evidenceBoard
import com.bioscan.fieldterminal.domain.typicalityView
import com.bioscan.fieldterminal.ui.components.LoadErrorCard
import com.bioscan.fieldterminal.ui.components.RefreshOnCacheUpdates
import com.bioscan.fieldterminal.ui.components.SkeletonSections
import com.bioscan.fieldterminal.ui.components.describeLoadFailure
import com.bioscan.fieldterminal.ui.components.launchSafe
import com.bioscan.fieldterminal.ui.components.rememberCacheVersion
import com.bioscan.fieldterminal.ui.screens.health.HealthN1Section
import com.bioscan.fieldterminal.ui.theme.FTType
import java.time.LocalDate
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// App restructure: Lab › N=1, the personal-experiment cards that were on Today (what goes with what, what came before
// off mornings, today's draw and the daily tests, do the summaries earn their place) and the sexual-health correlations
// (were on Health › Sexual health). One load for the tab, the same reads Today made for them.
@Composable
fun N1Tab() {
    val page = remember { N1State() }
    var registerError by remember { mutableStateOf<String?>(null) }
    var experiments by remember { mutableStateOf<List<ExperimentRow>>(emptyList()) }
    var experimentResults by remember { mutableStateOf<Map<String, ExperimentResultRow>>(emptyMap()) }
    var experimentsKey by remember { mutableIntStateOf(0) }
    var experimentError by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf<DailyChoice?>(null) }
    val scope = rememberCoroutineScope()

    // Optional enrichment, reloaded after a registration or a delete: a failed read just leaves the experiment cards out.
    val cacheVersion = rememberCacheVersion()
    LaunchedEffect(experimentsKey, cacheVersion) {
        val repo = ExperimentsRepository(SupabaseClientProvider.client)
        catching { experiments = repo.loadExperiments() }
        catching { experimentResults = repo.loadResults() }
    }

    val guard = page.guard
    RefreshOnCacheUpdates(guard)
    LaunchedEffect(guard.attempt) { page.load() }

    val s = page.sessions
    val w = page.wearable
    val sl = page.sleep
    val wb = page.wellbeing
    if (s == null || w == null || sl == null || wb == null) {
        if (guard.error != null) {
            LoadErrorCard(guard)
        } else {
            SkeletonSections()
        }
        return
    }
    // Each composite's scorecard on this history, worked out off the main thread (null until ready).
    val scorecards by rememberScorecards(w, sl, wb, s, page.breakStarts, page.loadSpans, page.planned)

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        EvidenceBoardCard(evidenceBoard(page.relationships, page.registeredResults), page.evidence, onTest = { testing = it })
        CaseCrossoverCard(
            page.evidence.crossover,
            registered = page.registeredQuestions,
            registerError = registerError,
            onRegister = { exposure, outcome ->
                registerError = null
                scope.launchSafe(onError = { e -> registerError = describeLoadFailure(e) }) {
                    val repo = AnalysisRepository(SupabaseClientProvider.client)
                    repo.registerQuestion(
                        NewRegisteredQuestion(exposure = exposure, outcome = outcome, noEffect = defaultZone(outcome), registeredOn = LocalDate.now().toString()),
                    )
                    page.registeredQuestions = repo.loadRegisteredQuestions()
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
        experimentError?.let { Text(it, style = FTType.Caption, color = FT.Bad) }
        // Interventions repeated now and then (nasal strips, a lactose meal): events, how each felt, and the evidence so far.
        InterventionEventsSection()
        // Bundles of those around one objective (docs/intervention-protocol-intelligence).
        ProtocolsSection()
        N1ScorecardsCard(scorecards, typicality = typicalityView(page.typicality).scorecard)
        // Health concerns (docs/health-concerns): patterns found in check-ins and the trials testing them.
        HealthN1Section()
        if (page.sexualActivity.isNotEmpty()) SexualHealthCorrelationsCard(wb, page.sexualActivity, w, sl)
    }

    testing?.let { choice ->
        DailyExperimentSheet(choice, onDismiss = { testing = null }, onRegistered = { testing = null; experimentsKey++ })
    }
}
