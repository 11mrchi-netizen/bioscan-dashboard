package com.bioscan.fieldterminal.ui.screens.status

import com.bioscan.fieldterminal.domain.LoadSpans
import com.bioscan.fieldterminal.data.model.PlannedOutcomeRow
import com.bioscan.fieldterminal.domain.compositeInputs
import com.bioscan.fieldterminal.domain.evaluations
import com.bioscan.fieldterminal.domain.recoveryFrom
import androidx.compose.runtime.mutableIntStateOf
import com.bioscan.fieldterminal.data.ExperimentResultRow
import com.bioscan.fieldterminal.data.ExperimentRow
import com.bioscan.fieldterminal.data.ExperimentsRepository
import com.bioscan.fieldterminal.domain.DailyChoice
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.AnalysisRepository
import com.bioscan.fieldterminal.data.EveningCheckinRepository
import com.bioscan.fieldterminal.data.EvidenceRows
import com.bioscan.fieldterminal.data.NutritionOverview
import com.bioscan.fieldterminal.data.NutritionRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.ZeppStressRepository
import com.bioscan.fieldterminal.data.model.ExpectedBandRow
import com.bioscan.fieldterminal.data.model.NewRegisteredQuestion
import com.bioscan.fieldterminal.data.model.RegisteredQuestion
import com.bioscan.fieldterminal.data.model.RelationshipResultRow
import com.bioscan.fieldterminal.data.model.SleepAnalysisRow
import com.bioscan.fieldterminal.data.model.TrainingLoadSessionRow
import com.bioscan.fieldterminal.data.model.TypicalityResultRow
import com.bioscan.fieldterminal.data.model.WearableAnalysisRow
import com.bioscan.fieldterminal.data.model.WellbeingAnalysisRow
import com.bioscan.fieldterminal.domain.DisplayValue
import com.bioscan.fieldterminal.domain.EveningCheckin
import com.bioscan.fieldterminal.domain.SeriesBreakStarts
import com.bioscan.fieldterminal.domain.checkinDateFor
import com.bioscan.fieldterminal.domain.computeTrainingReadiness
import com.bioscan.fieldterminal.domain.isComplete
import com.bioscan.fieldterminal.domain.lastEveningDate
import com.bioscan.fieldterminal.domain.contributors
import com.bioscan.fieldterminal.domain.defaultZone
import com.bioscan.fieldterminal.domain.evidenceBoard
import com.bioscan.fieldterminal.domain.stress.StressDay
import com.bioscan.fieldterminal.domain.stress.analyzeStressDay
import com.bioscan.fieldterminal.domain.summary
import com.bioscan.fieldterminal.domain.trainingLoadStateWord
import com.bioscan.fieldterminal.domain.typicalityView
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTCardEmphasis
import com.bioscan.fieldterminal.ui.components.FTMetricValue
import com.bioscan.fieldterminal.ui.components.HydrationSourceDonutChart
import com.bioscan.fieldterminal.ui.components.LoadErrorCard
import com.bioscan.fieldterminal.ui.components.describeLoadFailure
import com.bioscan.fieldterminal.ui.components.launchSafe
import com.bioscan.fieldterminal.ui.components.MacroDonutChart
import com.bioscan.fieldterminal.ui.components.TileHeader
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.components.metricStateLabel
import com.bioscan.fieldterminal.ui.components.parallel
import com.bioscan.fieldterminal.ui.components.rememberLoadGuard
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import com.bioscan.fieldterminal.util.Perf
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.math.roundToInt

// The Today page (UX review P6; was Daily Readiness), reached from TODAY › on the Status hero: how today is.
// App restructure, in the owner's order: THIS MORNING (each core metric against the band expected for you, with what
// last evening brought), IS TODAY TYPICAL?, TODAY'S RECOVERY (it lives only here now), READY FOR (built on recovery),
// the training-load and cardio links, and NUTRITION TODAY (today's fuel line, progress against the day's targets and the
// meals logged). Last evening's check-in is a header button instead of a card; the evidence board, case-crossover,
// experiments and scorecards moved to Lab › N=1.
// Every section reuses the domain chain and card its own tile already uses -- this screen composes them, loading its
// own data independently per this app's per-screen-loader convention.
@Composable
fun TodayScreen(
    onBack: () -> Unit,
    onOpenTrainingLoad: () -> Unit,
    onOpenCardio: () -> Unit,
    onOpenFuel: () -> Unit = {},
    // Analysis review phase B: the 22:25 reminder lands here with the evening check-in open.
    openCheckIn: Boolean = false,
    onCheckInHandled: () -> Unit = {},
) {
    var sessions by remember { mutableStateOf<List<TrainingLoadSessionRow>?>(null) }
    var wearable by remember { mutableStateOf<List<WearableAnalysisRow>?>(null) }
    var sleep by remember { mutableStateOf<List<SleepAnalysisRow>?>(null) }
    var wellbeing by remember { mutableStateOf<List<WellbeingAnalysisRow>?>(null) }
    var stressDays by remember { mutableStateOf<List<StressDay>>(emptyList()) }
    // Newest day with minute heart rate, for the intraday HEART RATE card (moved here from Health).
    var hrDay by remember { mutableStateOf<com.bioscan.fieldterminal.domain.HeartRateDay?>(null) }
    var nutrition by remember { mutableStateOf<NutritionOverview?>(null) }
    // Analysis Layer 3 / R1: start of each metric's current regime (last confirmed series break); NONE until loaded.
    var breakStarts by remember { mutableStateOf(SeriesBreakStarts.NONE) }
    // Analysis Layer 3 / Phase 3: published confirmatory relationships (read-only).
    var relationships by remember { mutableStateOf<List<RelationshipResultRow>>(emptyList()) }
    // Analysis Layer 3 / Phase 4: "is today typical for you", a quiet drill-down (not part of Today's Recovery).
    var typicality by remember { mutableStateOf<TypicalityResultRow?>(null) }
    // Analysis review phase C: the published expected band per metric (empty before the first run).
    var bands by remember { mutableStateOf<Map<String, ExpectedBandRow>>(emptyMap()) }
    // Analysis review phase D: the event curves behind THIS MORNING's "what last evening brought".
    var evidence by remember { mutableStateOf(EvidenceRows()) }
    // Analysis review phase G: past planned sessions, for READY FOR's scorecard (null while loading or if the read fails).
    var planned by remember { mutableStateOf<List<PlannedOutcomeRow>?>(null) }
    // Phase G: the fitness and fatigue spans load is drawn with (standard unless the personal pair was adopted).
    var loadSpans by remember { mutableStateOf(LoadSpans.STANDARD) }
    // Analysis review phase B: last evening's check-in (null until answered or if the read fails) and the open sheet.
    var lastEvening by remember { mutableStateOf<EveningCheckin?>(null) }
    var checkInDate by remember { mutableStateOf<LocalDate?>(null) }
    LaunchedEffect(openCheckIn) {
        if (openCheckIn) {
            checkInDate = checkinDateFor(LocalDateTime.now())
            onCheckInHandled()
        }
    }

    val guard = rememberLoadGuard()
    LaunchedEffect(guard.attempt) {
        // Perf pass: the loads are independent, so they run together (was one after another).
        Perf.timed("Today.load") {
            guard.run {
                val repo = AnalysisRepository(SupabaseClientProvider.client)
                parallel(
                    { sessions = repo.loadExerciseSessionsForTrainingLoad() },
                    { wearable = repo.loadWearableDaily() },
                    { sleep = repo.loadSleepDaily() },
                    { wellbeing = repo.loadWellbeingDaily() },
                    { hrDay = runCatching { repo.loadHeartRateDay() }.getOrNull() },
                    { stressDays = ZeppStressRepository(SupabaseClientProvider.client).loadStressDays() },
                    { nutrition = NutritionRepository(SupabaseClientProvider.client).loadOverview() },
                    { breakStarts = repo.loadSeriesBreakStarts() },
                    { relationships = repo.loadRelationships() },
                    { typicality = repo.loadTypicality() },
                    { bands = repo.loadExpectedBands() },
                    { evidence = repo.loadEvidence() },
                    { planned = runCatching { repo.loadPlannedOutcomes() }.getOrNull() },
                    { loadSpans = repo.loadLoadSpans() },
                    // A missing table (migration not yet applied) or a failed read just leaves the card asking.
                    { lastEvening = runCatching { EveningCheckinRepository(SupabaseClientProvider.client).load(lastEveningDate(LocalDate.now())) }.getOrNull() },
                )
            }
        }
    }

    val s = sessions
    val w = wearable
    val sl = sleep
    val wb = wellbeing
    val nut = nutrition
    if (s == null || w == null || sl == null || wb == null || nut == null) {
        Column(modifier = Modifier.fillMaxSize().background(FT.Base)) {
            TileHeader(onBack = onBack, title = "TODAY", action = { LastEveningButton(lastEvening) { checkInDate = lastEveningDate(LocalDate.now()) } })
            if (guard.error != null) {
                LoadErrorCard(guard)
            } else {
                Box(Modifier.fillMaxWidth().padding(vertical = 60.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = FT.Emerald)
                }
            }
        }
        return
    }

    val hrvPoints = w.mapNotNull { row -> row.hrv?.let { LocalDate.parse(row.date) to it } }
    val rhrPoints = w.mapNotNull { row -> row.rhr?.let { LocalDate.parse(row.date) to it } }
    // Perf pass: pure functions of the loaded rows, computed once per load instead of on every recomposition.
    // Analysis review phase G: assembled by the shared builder (domain/Composites.kt) the Heart and Training tiles use too.
    val (trainingLoadEval, recovery) = remember(s, w, sl, wb, stressDays, breakStarts, loadSpans) {
        val evaluations = compositeInputs(w, sl, wb, s, breakStarts, loadSpans).evaluations()
        val exerciseWindows = s.mapNotNull { session ->
            val start = runCatching { java.time.OffsetDateTime.parse(session.startTime) }.getOrNull()?.toInstant() ?: return@mapNotNull null
            val durationMin = session.durationMin ?: return@mapNotNull null
            start to start.plusSeconds((durationMin * 60).toLong())
        }
        val physiologicalStress = stressDays.firstOrNull()?.let { analyzeStressDay(it, stressDays.drop(1), exerciseWindows) }
        evaluations.load to recoveryFrom(evaluations, physiologicalStress)
    }
    // Phase G: each composite's scorecard on this history, worked out off the main thread (null until ready).
    val scorecards by rememberScorecards(w, sl, wb, s, breakStarts, loadSpans, planned)

    val fuel = com.bioscan.fieldterminal.ui.screens.fuel.rememberTodayFuel(nut)

    Column(modifier = Modifier.fillMaxSize().background(FT.Base).verticalScroll(rememberScrollState())) {
        TileHeader(onBack = onBack, title = "TODAY", action = { LastEveningButton(lastEvening) { checkInDate = lastEveningDate(LocalDate.now()) } })
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            // Analysis review phase C: the answer first -- each core metric this morning against what was expected.
            val bandInputs = buildMap {
                bands["hrv"]?.let { put("hrv", BandInputs(it, hrvPoints)) }
                bands["rhr"]?.let { put("rhr", BandInputs(it, rhrPoints)) }
                bands["sleep_hours"]?.let { put("sleep_hours", BandInputs(it, sl.mapNotNull { row -> row.hours?.let { v -> LocalDate.parse(row.date) to v } })) }
            }
            ThisMorningCard(
                bandInputs,
                LocalDate.now(),
                onOpenCardio = onOpenCardio,
                // Analysis review phase D: what last evening brought, with the board's evidence about it.
                contributors = contributors(lastEvening, relationships, evidence.events.map { it.payload }),
            )
            TypicalityCompactCard(typicalityView(typicality), typicality)
            DynamicRecoveryCard(recovery, emphasis = if (bandInputs.isEmpty()) FTCardEmphasis.Hero else FTCardEmphasis.Supporting, scorecard = scorecards?.recovery)
            TrainingReadinessCard(computeTrainingReadiness(recovery), scorecard = scorecards?.readyFor)
            NutritionTodayCard(nut, fuel, onOpenPlan = onOpenFuel)
            GlucoseSpikingCard(nut.todaysMeals)
            IntradayHeartRateCard(hrDay)
            HypnogramCard(sl.lastOrNull())
            stressDays.firstOrNull()?.let { today ->
                val windows = s.mapNotNull { session ->
                    val start = runCatching { java.time.OffsetDateTime.parse(session.startTime) }.getOrNull()?.toInstant() ?: return@mapNotNull null
                    val durationMin = session.durationMin ?: return@mapNotNull null
                    start to start.plusSeconds((durationMin * 60).toLong())
                }
                PhysiologicalStressCard(analyzeStressDay(today, stressDays.drop(1), windows), today)
            }
        }
    }

    checkInDate?.let { date ->
        EveningCheckInSheet(
            date = date,
            onDismiss = { checkInDate = null },
            onSaved = { saved ->
                if (saved.date == lastEveningDate(LocalDate.now())) lastEvening = saved
                checkInDate = null
            },
        )
    }
}

// Analysis review phase B: the morning catch-up for last evening's check-in. App restructure, fix 2: a header button
// instead of a card -- ADD ABOUT LAST EVENING until answered, then LAST EVENING ✓, which reopens it to edit.
@Composable
private fun LastEveningButton(checkin: EveningCheckin?, onOpen: () -> Unit) {
    if (checkin?.isComplete == true) {
        FTButton("LAST EVENING ✓", emphasis = FTButtonEmphasis.Tertiary, onClick = onOpen)
    } else {
        FTButton("ADD ABOUT LAST EVENING", emphasis = FTButtonEmphasis.Primary, onClick = onOpen)
    }
}

// A titled link to the tile page that holds the full card: section accent dot, title, the one value that matters here.
@Composable
private fun LinkTile(title: String, value: String, accent: Color, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(FT.RadiusModule)
    Column(
        modifier = modifier
            .clip(shape)
            .background(FT.GlassFill)
            .border(FT.BorderWidth, FT.GlassBorder, shape)
            .ftClickable(shape = shape, role = Role.Button, onClick = onClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
            Text(title, style = FTType.LabelCaps, color = FT.TextPrimary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("›", style = FTType.LabelCaps, color = FT.TextSecondary)
        }
        Text(value, style = FTType.Label, color = FT.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

// "WED 14 OCT": the Today page's context line.
private fun todayContextLine(): String =
    java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM", java.util.Locale.ENGLISH)).uppercase()
