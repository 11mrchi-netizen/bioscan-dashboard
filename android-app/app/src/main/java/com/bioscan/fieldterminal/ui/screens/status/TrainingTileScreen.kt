package com.bioscan.fieldterminal.ui.screens.status

import com.bioscan.fieldterminal.domain.LoadSpans
import com.bioscan.fieldterminal.data.model.FitnessFatigueRow
import com.bioscan.fieldterminal.data.model.PlannedOutcomeRow
import com.bioscan.fieldterminal.domain.compositeInputs
import com.bioscan.fieldterminal.domain.evaluations
import com.bioscan.fieldterminal.domain.recoveryFrom
import com.bioscan.fieldterminal.domain.demotes
import com.bioscan.fieldterminal.domain.ScorecardResult
import com.bioscan.fieldterminal.domain.recoveryStateWord
import com.bioscan.fieldterminal.domain.trainingLoadStateWord
import com.bioscan.fieldterminal.util.Perf
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.AnalysisRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.TrainingCyclesRepository
import com.bioscan.fieldterminal.data.buildTrainingSessionLoads
import com.bioscan.fieldterminal.data.restingSeries
import com.bioscan.fieldterminal.data.model.OstrcAnalysisRow
import com.bioscan.fieldterminal.data.model.TrainingLoadSessionRow
import com.bioscan.fieldterminal.data.model.WearableAnalysisRow
import com.bioscan.fieldterminal.data.model.WellbeingAnalysisRow
import com.bioscan.fieldterminal.domain.ExpectationTier
import com.bioscan.fieldterminal.domain.MetricCategory
import com.bioscan.fieldterminal.domain.OstrcEvaluation
import com.bioscan.fieldterminal.domain.TrainingCycle
import com.bioscan.fieldterminal.domain.TrainingLoadEvaluation
import com.bioscan.fieldterminal.domain.evaluateOstrc
import com.bioscan.fieldterminal.domain.evaluateRestCadence
import com.bioscan.fieldterminal.domain.WeeklyRestCadencePoint
import com.bioscan.fieldterminal.domain.weeklyRestCadenceHistory
import com.bioscan.fieldterminal.domain.evaluateSubjective
import com.bioscan.fieldterminal.domain.evaluateTrainingLoad
import com.bioscan.fieldterminal.domain.resolveTier
import com.bioscan.fieldterminal.domain.PerformanceTimeframe
import com.bioscan.fieldterminal.domain.toPerformanceTimeframe
import com.bioscan.fieldterminal.domain.TSB_DETRAINED_ABOVE
import com.bioscan.fieldterminal.domain.TSB_FRESHENED_FROM
import com.bioscan.fieldterminal.domain.TSB_HEAVILY_LOADED_BELOW
import com.bioscan.fieldterminal.domain.TSB_LOADED_BELOW
import com.bioscan.fieldterminal.domain.TrainingLoadPoint
import com.bioscan.fieldterminal.domain.trainingLoadSeries
import com.bioscan.fieldterminal.data.model.SleepAnalysisRow
import com.bioscan.fieldterminal.data.ZeppStressRepository
import com.bioscan.fieldterminal.domain.SeriesBreakStarts
import com.bioscan.fieldterminal.domain.computeTrainingReadiness
import com.bioscan.fieldterminal.domain.TrainingReadinessResult
import com.bioscan.fieldterminal.domain.TrainingDemandTier
import com.bioscan.fieldterminal.domain.stress.StressDay
import com.bioscan.fieldterminal.domain.stress.analyzeStressDay
import com.bioscan.fieldterminal.domain.EvalState
import com.bioscan.fieldterminal.ui.components.BandedGauge
import com.bioscan.fieldterminal.ui.components.DateTrendLine
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTCardEmphasis
import com.bioscan.fieldterminal.ui.components.FTDataChip
import com.bioscan.fieldterminal.ui.components.FTGlanceStrip
import com.bioscan.fieldterminal.ui.components.FTMetricRow
import com.bioscan.fieldterminal.ui.components.FTStatePill
import com.bioscan.fieldterminal.ui.components.GaugeBand
import com.bioscan.fieldterminal.ui.components.GlanceAnchors
import com.bioscan.fieldterminal.ui.components.GlanceChip
import com.bioscan.fieldterminal.ui.components.InfoHelpButton
import com.bioscan.fieldterminal.ui.components.LoadErrorCard
import com.bioscan.fieldterminal.ui.components.PinnedPage
import com.bioscan.fieldterminal.ui.components.SegmentedToggle
import com.bioscan.fieldterminal.ui.components.SubTabRow
import com.bioscan.fieldterminal.ui.components.TrendSeries
import com.bioscan.fieldterminal.ui.components.glanceAnchor
import com.bioscan.fieldterminal.ui.components.parallel
import com.bioscan.fieldterminal.ui.components.rememberGlanceAnchors
import com.bioscan.fieldterminal.ui.components.rememberLoadGuard
import com.bioscan.fieldterminal.ui.nav.TrainingTab
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.LocalDate

// DAV-98: LOAD first (dominant instrument), then PERFORMANCE, then SESSIONS
// -- per design/FIELD_TERMINAL_IA_CONTRACT.md section 5, a hierarchy read
// rather than the old equal-weight card stack. Every card here already
// existed (Category 6/7's CTL/ATL/TSB + rest cadence were previously
// stranded on the old Analysis sub-tab -- DAV-71 moved them here, unordered;
// this only reorders what TrainingLoadSection()/TrainingScreen() already
// render, nothing new).
// DAV-216 (24/9 fixes): Injury moved in from Heart as a second subtab (see
// InjuryTab() below). Health concerns replaced its old injury/illness list with
// the concerns of the body (ui/screens/health/ConcernsTab.kt); the OSTRC card stays.
// DAV-206 (24/9 fixes): the standalone REST CADENCE card is gone -- its
// underlying evaluateRestCadence() is still used (InjuryTab()'s "days
// without rest" stat on the OSTRC card reads from it), just no longer as
// its own dedicated card here.
@Composable
fun TrainingTileScreen(
    onBack: () -> Unit,
    onOpenSessionDetail: (Long) -> Unit = {},
    onOpenConcern: (String) -> Unit = {},
    // Set by a deep link (Status's injury flag -> INJURY, the User tab's level buttons -> RUNNING / STRENGTH); applied
    // once, then cleared via the callback.
    requestedTab: TrainingTab? = null,
    onRequestedTabConsumed: () -> Unit = {},
) {
    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(requestedTab ?: TrainingTab.Load) }
    LaunchedEffect(requestedTab) {
        if (requestedTab != null) {
            tab = requestedTab
            onRequestedTabConsumed()
        }
    }
    // Redesign: the global range (top bar) sets the window of every Training chart.
    val timeframe = com.bioscan.fieldterminal.ui.components.GlobalTimeRange.selected.toPerformanceTimeframe()

    // Contract A4: title, context line and sub-tab rail stay pinned; only the tab's content scrolls, from the top on a tab change.
    val scroll = rememberScrollState()
    LaunchedEffect(tab) { scroll.scrollTo(0) }
    PinnedPage(
        onBack = onBack,
        title = "TRAINING",
        accent = FT.DomainTraining,
        rail = { SubTabRow(items = TrainingTab.entries, selected = tab, label = { it.label }, onSelect = { tab = it }) },
        headerAction = { com.bioscan.fieldterminal.ui.components.TimeRangeDropdown() },
        scrollState = scroll,
    ) {

        when (tab) {
            // App restructure: TRAINING LOAD then INTENSITY. READY FOR lives on Today only; the running and strength
            // rollups gave way to their own tabs.
            TrainingTab.Load -> TrainingLoadSection(timeframe)
            TrainingTab.Running -> com.bioscan.fieldterminal.ui.screens.user.RunningTab()
            TrainingTab.Strength -> com.bioscan.fieldterminal.ui.screens.user.StrengthTab()
            TrainingTab.Sessions -> TrainingSessionsTab(onOpenSessionDetail)
            TrainingTab.Injury -> InjuryTab(onOpenConcern)
        }
    }
}

// DAV-216 (24/9 fixes): moved from HeartTileScreen.kt's HeartTab.Injury
// branch verbatim -- independently loaded rather than sharing
// TrainingLoadSection()'s load, matching this file's existing pattern of
// each subtab loading its own data (same as FuelTileScreen's Nutrition vs
// Digestion tabs).
@Composable
private fun InjuryTab(onOpenConcern: (String) -> Unit) {
    var sessions by remember { mutableStateOf<List<TrainingLoadSessionRow>?>(null) }
    var wearable by remember { mutableStateOf<List<WearableAnalysisRow>?>(null) }
    var wellbeing by remember { mutableStateOf<List<WellbeingAnalysisRow>?>(null) }
    var ostrc by remember { mutableStateOf<List<OstrcAnalysisRow>?>(null) }
    // Analysis review phase G: the same spans the Load tab draws with.
    var injuryLoadSpans by remember { mutableStateOf(LoadSpans.STANDARD) }

    val guard = rememberLoadGuard()
    LaunchedEffect(guard.attempt) {
        Perf.timed("InjuryTab.load") {
            guard.run {
                val repo = AnalysisRepository(SupabaseClientProvider.client)
                parallel(
                    { sessions = repo.loadExerciseSessionsForTrainingLoad() },
                    { wearable = repo.loadWearableDaily() },
                    { wellbeing = repo.loadWellbeingDaily() },
                    { ostrc = repo.loadOstrcCheckins() },
                    { injuryLoadSpans = repo.loadLoadSpans() },
                )
            }
        }
    }

    // Health concerns of the body (docs/health-concerns): the same list as Health › CONCERNS, without general symptoms.
    // It replaces the old injury and illness list and loads on its own, so it shows before the load context below.
    com.bioscan.fieldterminal.ui.screens.health.ConcernsTab(onOpenConcern = onOpenConcern, anatomicalOnly = true)

    val s = sessions
    val w = wearable
    val wb = wellbeing
    val os = ostrc
    if (s == null || w == null || wb == null || os == null) {
        if (guard.error != null) {
            LoadErrorCard(guard)
        } else {
            Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = FT.DomainTraining)
            }
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        // DAV-205 (24/9 fixes): TRIMP fallback, same shared builder Load tab uses.
        val sessionLoads = buildTrainingSessionLoads(s, restingSeries(w))
        val trainingLoadEval = evaluateTrainingLoad(sessionLoads, spans = injuryLoadSpans)
        val restCadenceEval = evaluateRestCadence(sessionLoads, trainingLoadEval.tsb, trainingLoadEval.confidence.met)
        val sorenessEval = evaluateSubjective(wb.mapNotNull { row -> row.soreness?.let { LocalDate.parse(row.date) to it.toDouble() } })
        val ostrcByBodyArea = os.mapNotNull { row -> row.severityScore?.let { row.bodyArea to (LocalDate.parse(row.checkDate) to it) } }
            .groupBy({ it.first }, { it.second })
        // DAV-51: 8-week rest-cadence strip, same weekly load/deload data
        // evaluateRestCadence's own weeksSinceDeload search already uses.
        val weeklyRestCadence = weeklyRestCadenceHistory(sessionLoads, weeks = 8)
        if (ostrcByBodyArea.isEmpty()) {
            OstrcCard(evaluateOstrc("—", emptyList()), trainingLoadEval.tsb, restCadenceEval.consecutiveDaysWithoutRest, sorenessEval.median7d, weeklyRestCadence)
        } else {
            ostrcByBodyArea.forEach { (bodyArea, entries) ->
                OstrcCard(evaluateOstrc(bodyArea, entries), trainingLoadEval.tsb, restCadenceEval.consecutiveDaysWithoutRest, sorenessEval.median7d, weeklyRestCadence)
            }
        }
    }
}

// DAV-216 (24/9 fixes): moved verbatim from HeartTileScreen.kt.
@Composable
private fun OstrcCard(eval: OstrcEvaluation, tsb: Double?, daysWithoutRest: Int, sorenessMedian: Double?, weeklyRestCadence: List<WeeklyRestCadencePoint>) {
    FTCard(dataQuality = eval.confidence,
        title = "OSTRC-H2 · ${eval.bodyArea.uppercase()}",
        info = "No injury risk score — single-factor screening doesn't predict injury. You do the synthesis; this doesn't.",
    ) {
        eval.latestSeverityScore?.let { FTMetricRow("Latest severity", "$it / 100") }
        eval.latestCheckDate?.let { FTMetricRow("Last check-in", it.toString()) }
        Text(
            "LOAD CONTEXT (shown adjacent, never combined into one score)",
            style = FTType.LabelCaps,
            color = FT.TextMuted,
        )
        tsb?.let { FTMetricRow("TSB (form)", "%+.1f".format(it)) }
        FTMetricRow("Days without rest", "$daysWithoutRest")
        sorenessMedian?.let { FTMetricRow("7-day soreness median", "%.1f".format(it)) }
        RestCadenceStrip(weeklyRestCadence)
    }
}

// DAV-51 (Category 7's "rest-cadence 8-week strip"): one bar per week, sized
// by that week's session load, deload weeks (same rule
// evaluateRestCadence()'s own weeksSinceDeload search uses) picked out in
// Emerald -- oldest week on the left, matching every other chart's
// left-to-right time convention.
@Composable
private fun RestCadenceStrip(history: List<WeeklyRestCadencePoint>) {
    if (history.isEmpty()) return
    val maxLoad = history.maxOf { it.load }.coerceAtLeast(1.0)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "8-WEEK LOAD (deload weeks marked)",
            style = FTType.LabelCaps,
            color = FT.TextMuted,
        )
        Row(modifier = Modifier.fillMaxWidth().height(40.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            history.sortedByDescending { it.weekIndex }.forEach { week ->
                val fraction = (week.load / maxLoad).toFloat().coerceIn(0f, 1f).coerceAtLeast(0.04f)
                Box(modifier = Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .fillMaxHeight(fraction)
                            .background(if (week.isDeload) FT.Category.Activity.c500 else FT.GlassStrongFill),
                    )
                }
            }
        }
    }
}

@Composable
internal fun TrainingReadinessCard(result: TrainingReadinessResult, modifier: Modifier = Modifier, scorecard: ScorecardResult? = null) {
    // UX review P6: "READY FOR" (was "TODAY'S READINESS"): readiness per kind of session, built on Today's Recovery.
    // Analysis review phase G: scored against whether planned sessions were done; demoted, it shows no per-session words.
    FTCard(dataQuality = result.confidence, title = "READY FOR", methodology = "dynamic_recovery", modifier = modifier) {
        val demoted = scorecard.demotes()
        if (demoted) FTStatePill(INPUTS_ONLY) else RecoveryStatePill(result.generalState)
        if (demoted) {
            Text("Its per-session words did not foresee your planned sessions better than one of its inputs: see the inputs under TODAY'S RECOVERY.", style = FTType.BodySmall, color = FT.TextSecondary)
        } else result.demandRelative.forEach { (tier, state) ->
            val label = when (tier) {
                TrainingDemandTier.EASY_AEROBIC -> "Easy aerobic"
                TrainingDemandTier.THRESHOLD -> "Threshold"
                TrainingDemandTier.LONG_HIGH_LOAD -> "Long / high-load"
                TrainingDemandTier.STRENGTH -> "Strength"
            }
            // Fix 4: tinted like recovery's movements (faint green ready, faint red caution), always with the word.
            val (direction, word) = when (state) {
                EvalState.ShiftUp -> 1 to "READY"
                EvalState.Stable -> 0 to "MATCHED"
                EvalState.ShiftDown -> -1 to "CAUTION"
                else -> null to "BUILDING"
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = FTType.Body, color = FT.TextSecondary, modifier = Modifier.weight(1f))
                com.bioscan.fieldterminal.ui.components.FTMovementChip(direction, word)
            }
        }
        ScorecardLine(scorecard)
    }
}

@Composable
private fun TrainingLoadSection(timeframe: PerformanceTimeframe) {
    var sessions by remember { mutableStateOf<List<TrainingLoadSessionRow>?>(null) }
    var wearable by remember { mutableStateOf<List<WearableAnalysisRow>?>(null) }
    var activeCycle by remember { mutableStateOf<TrainingCycle?>(null) }
    var cycleLoaded by remember { mutableStateOf(false) }
    // Analysis review phase G: the spans load is drawn with, and the personal fit behind them.
    var fitnessFatigue by remember { mutableStateOf<FitnessFatigueRow?>(null) }
    var loadSpans by remember { mutableStateOf(LoadSpans.STANDARD) }

    val loadGuard = rememberLoadGuard()
    LaunchedEffect(loadGuard.attempt) {
        Perf.timed("TrainingLoad.load") {
            loadGuard.run {
                val repo = AnalysisRepository(SupabaseClientProvider.client)
                parallel(
                    { sessions = repo.loadExerciseSessionsForTrainingLoad() },
                    { wearable = repo.loadWearableDaily() },
                    { activeCycle = TrainingCyclesRepository(SupabaseClientProvider.client).loadActiveCycle(); cycleLoaded = true },
                    { fitnessFatigue = repo.loadFitnessFatigue(); loadSpans = repo.loadLoadSpans() },
                )
            }
        }
    }

    val s = sessions
    val w = wearable
    if (s == null || w == null || !cycleLoaded) {
        if (loadGuard.error != null) {
            LoadErrorCard(loadGuard)
        } else {
            Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = FT.DomainTraining)
            }
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        // DAV-205 (24/9 fixes): TRIMP fallback for sessions with no RPE, the same shared builder Injury uses. Pure functions
        // of the loaded rows, computed once per load, not on every timeframe tap.
        val (sessionLoads, trainingLoadEval) = remember(s, w, loadSpans) {
            val loads = buildTrainingSessionLoads(s, restingSeries(w))
            loads to evaluateTrainingLoad(loads, spans = loadSpans)
        }
        TrainingLoadCard(
            trainingLoadEval,
            resolveTier(MetricCategory.TrainingLoad, activeCycle),
            trainingLoadSeries(sessionLoads, spans = loadSpans),
            timeframe,
            fitnessFatigue = fitnessFatigue,
        )
        // VO2max and lactate threshold, moved here from the Running page (owner's structure, Oct 2026).
        com.bioscan.fieldterminal.ui.screens.user.RunningFitnessCard(timeframe)
        IntensityCard()
    }
}

@Composable
internal fun TrainingLoadCard(
    eval: TrainingLoadEvaluation,
    tier: ExpectationTier?,
    series: List<TrainingLoadPoint>,
    timeframe: PerformanceTimeframe,
    modifier: Modifier = Modifier,
    fitnessFatigue: FitnessFatigueRow? = null,
) {
    val cutoff = LocalDate.now().minusMonths(timeframe.months)
    val visible = series.filter { it.date >= cutoff }

    // Contract A3: the Load tab's lead card.
    FTCard(dataQuality = eval.confidence, title = "TRAINING LOAD", methodology = "training_load", emphasis = FTCardEmphasis.Hero, modifier = modifier) {
        // One banded gauge on the TSB axis replaces the old State pill and
        // "TSB band" row -- the band name IS the state, and the gauge shows
        // where inside the band you sit. Building/NoData has no TSB yet, so
        // the pill stays as the honest fallback.
        val tsb = eval.tsb
        if (tsb != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("FORM (TSB BAND)", style = FTType.LabelCaps, color = FT.TextMuted)
                InfoHelpButton("TSB band", LOAD_HELP_BAND)
            }
            BandedGauge(
                value = tsb,
                min = -50.0,
                max = 40.0,
                bands = TSB_GAUGE_BANDS,
                valueText = "%+.1f".format(tsb),
                label = (eval.tsbBand ?: "").uppercase(),
            )
        } else {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("State", style = FTType.Body, color = FT.TextSecondary)
                FTStatePill(trainingLoadStateWord(eval.state, eval.tsbBand))
            }
        }
        // Supporting levels beside the TSB hero above: current fitness (CTL)
        // and fatigue (ATL), each at one step below the hero's size.
        if (eval.ctl != null || eval.atl != null) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                eval.ctl?.let { LoadLevel("CTL · FITNESS", "%.1f".format(it), "CTL — fitness", LOAD_HELP_CTL, Modifier.weight(1f)) }
                eval.atl?.let { LoadLevel("ATL · FATIGUE", "%.1f".format(it), "ATL — fatigue", LOAD_HELP_ATL, Modifier.weight(1f)) }
            }
        }
        // Analysis review phase G: which spans the averages use, and why -- the person's own pair only when it forecast
        // tomorrow's HRV better than 42 and 7 on weeks the fit never saw (the PREDICTIVE rung).
        fitnessFatigue?.payload?.let { ff ->
            val personal = ff.personal
            val spans = if (ff.adopted && personal != null) "%.0f AND %.0f DAYS · YOURS (PREDICTIVE)".format(personal.ctl, personal.atl) else "42 AND 7 DAYS · STANDARD"
            Text("SPANS · $spans", style = FTType.LabelCaps, color = FT.TextSecondary)
            if (ff.summary.isNotBlank()) Text(ff.summary, style = FTType.Caption, color = FT.TextMuted)
        }
        // Trend lines: the numbers live above, so these carry only the history. Fitness and fatigue share one chart (same
        // unit, same axis) and are told apart by lightness, dash and a legend (UX review F10: two single-line charts read
        // as the same shape twice).
        if (eval.ctl != null && eval.atl != null && visible.size >= 2) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Fitness and fatigue", style = FTType.Body, color = FT.TextSecondary)
                DateTrendLine(
                    series = listOf(
                        TrendSeries(visible.map { p -> p.date to p.ctl }, FT.Category.Activity.c500, label = "CTL · FITNESS"),
                        TrendSeries(visible.map { p -> p.date to p.atl }, FT.Category.Activity.c300, label = "ATL · FATIGUE", dashed = true),
                    ),
                    valueFormat = { v -> "%.1f".format(v) },
                    unit = "training load (AU)",
                    legend = true,
                )
            }
        }
        eval.tsb?.let {
            LoadMetricBlock("TSB trend", visible.map { p -> p.date to p.tsb }, FT.Category.Activity.c500, helpTitle = "TSB — form", helpBody = LOAD_HELP_TSB, refLow = TSB_LOADED_BELOW, refHigh = TSB_FRESHENED_FROM, signed = true)
        }
        // Phase A3: a pure re-label of the state above, never a
        // recomputation -- only rendered when a training cycle is actually
        // active. No active cycle means no framing applies, not "unmanaged."
        tier?.let { FTMetricRow("This cycle", tierLabel(it)) }
    }
}

// A supporting load level: small caps label + help, the current value one step
// below the hero's size.
@Composable
private fun LoadLevel(label: String, value: String, helpTitle: String, helpBody: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, style = FTType.LabelCaps, color = FT.TextMuted)
            InfoHelpButton(helpTitle, helpBody)
        }
        Text(value, style = FTType.MetricMedium, color = FT.TextPrimary)
    }
}

// Label (+ optional help) with a small history line of the metric under it.
@Composable
private fun LoadMetricBlock(
    label: String,
    points: List<Pair<LocalDate, Double>>,
    color: androidx.compose.ui.graphics.Color,
    helpTitle: String? = null,
    helpBody: String? = null,
    refLow: Double? = null,
    refHigh: Double? = null,
    signed: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = FTType.Body, color = FT.TextSecondary)
            if (helpTitle != null && helpBody != null) InfoHelpButton(helpTitle, helpBody)
        }
        if (points.size >= 2) {
            DateTrendLine(
                points = points,
                color = color,
                refLow = refLow,
                refHigh = refHigh,
                valueFormat = { v -> if (signed) "%+.1f".format(v) else "%.1f".format(v) },
                unit = "training load (AU)",
            )
        }
    }
}

private val TSB_GAUGE_BANDS = listOf(
    GaugeBand(TSB_HEAVILY_LOADED_BELOW, FT.Critical),
    GaugeBand(TSB_LOADED_BELOW, FT.Warning),
    GaugeBand(TSB_FRESHENED_FROM, FT.Emerald),
    GaugeBand(TSB_DETRAINED_ABOVE, FT.Info),
    GaugeBand(40.0, FT.TextSecondary),
)

private const val LOAD_HELP_CTL =
    "Chronic Training Load — your \"fitness\". A slow average (about 6 weeks) of your daily training load, " +
        "where a session's load is duration × how hard it felt (RPE), or a heart-rate estimate when no RPE was logged.\n\n" +
        "It climbs when you train consistently and drains when you stop for weeks. Read your own trend, not the absolute number."
private const val LOAD_HELP_ATL =
    "Acute Training Load — your \"fatigue\". A fast average (about 7 days) of the same daily load.\n\n" +
        "It jumps after hard days or blocks and falls quickly with rest. When ATL sits well above CTL you are carrying more recent stress than you have built up."
private const val LOAD_HELP_TSB =
    "Training Stress Balance — your \"form\". Yesterday's CTL minus yesterday's ATL.\n\n" +
        "Negative: more recent fatigue than fitness (you are loaded). Around zero: balanced. Positive: rested. " +
        "The shaded strip on the chart is the neutral band (−10 to +5)."
private const val LOAD_HELP_BAND =
    "The gauge shows where TSB sits, in TrainingPeaks' descriptive bands:\n\n" +
        "• Below −30 — heavily loaded\n• −30 to −10 — loaded\n• −10 to +5 — neutral\n• +5 to +25 — freshened\n• Above +25 — detrained / very fresh\n\n" +
        "These describe load balance, not health or injury risk."

private fun tierLabel(tier: ExpectationTier): String = when (tier) {
    ExpectationTier.PrimaryTarget -> "PRIMARY TARGET"
    ExpectationTier.Maintained -> "MAINTAINED"
    ExpectationTier.Unmanaged -> "NOT A TARGET THIS CYCLE"
}

