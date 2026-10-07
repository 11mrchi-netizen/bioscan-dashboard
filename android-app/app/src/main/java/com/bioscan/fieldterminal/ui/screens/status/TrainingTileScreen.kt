package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.bioscan.fieldterminal.data.AnalysisRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.TrainingCyclesRepository
import com.bioscan.fieldterminal.data.buildTrainingSessionLoads
import com.bioscan.fieldterminal.data.model.OstrcAnalysisRow
import com.bioscan.fieldterminal.data.model.TrainingLoadSessionRow
import com.bioscan.fieldterminal.data.model.WearableAnalysisRow
import com.bioscan.fieldterminal.data.model.WellbeingAnalysisRow
import com.bioscan.fieldterminal.domain.toMetricState
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
import com.bioscan.fieldterminal.domain.TSB_DETRAINED_ABOVE
import com.bioscan.fieldterminal.domain.TSB_FRESHENED_FROM
import com.bioscan.fieldterminal.domain.TSB_HEAVILY_LOADED_BELOW
import com.bioscan.fieldterminal.domain.TSB_LOADED_BELOW
import com.bioscan.fieldterminal.domain.TrainingLoadPoint
import com.bioscan.fieldterminal.domain.trainingLoadSeries
import com.bioscan.fieldterminal.data.model.SleepAnalysisRow
import com.bioscan.fieldterminal.data.ZeppStressRepository
import com.bioscan.fieldterminal.domain.SleepNight
import com.bioscan.fieldterminal.domain.computeSleepIndex
import com.bioscan.fieldterminal.domain.evaluateHrv
import com.bioscan.fieldterminal.domain.evaluateRhr
import com.bioscan.fieldterminal.domain.computeDynamicRecovery
import com.bioscan.fieldterminal.domain.computeTrainingReadiness
import com.bioscan.fieldterminal.domain.TrainingReadinessResult
import com.bioscan.fieldterminal.domain.TrainingDemandTier
import com.bioscan.fieldterminal.domain.stress.StressDay
import com.bioscan.fieldterminal.domain.stress.analyzeStressDay
import com.bioscan.fieldterminal.domain.EvalState
import com.bioscan.fieldterminal.ui.components.BandedGauge
import com.bioscan.fieldterminal.ui.components.DateTrendLine
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTStatePill
import com.bioscan.fieldterminal.ui.components.GaugeBand
import com.bioscan.fieldterminal.ui.components.InfoHelpButton
import com.bioscan.fieldterminal.ui.components.SegmentedToggle
import com.bioscan.fieldterminal.ui.components.SubTabRow
import com.bioscan.fieldterminal.ui.components.TileHeader
import com.bioscan.fieldterminal.ui.nav.TrainingTab
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import com.bioscan.fieldterminal.ui.theme.Inter
import com.bioscan.fieldterminal.ui.theme.RobotoMono
import java.time.LocalDate

// DAV-98: LOAD first (dominant instrument), then PERFORMANCE, then SESSIONS
// -- per design/FIELD_TERMINAL_IA_CONTRACT.md section 5, a hierarchy read
// rather than the old equal-weight card stack. Every card here already
// existed (Category 6/7's CTL/ATL/TSB + rest cadence were previously
// stranded on the old Analysis sub-tab -- DAV-71 moved them here, unordered;
// this only reorders what TrainingLoadSection()/TrainingScreen() already
// render, nothing new).
// DAV-216 (24/9 fixes): Injury moved in from Heart as a second subtab --
// HealthEventsScreen()/OstrcCard content is unchanged, just relocated (see
// InjuryTab() below).
// DAV-206 (24/9 fixes): the standalone REST CADENCE card is gone -- its
// underlying evaluateRestCadence() is still used (InjuryTab()'s "days
// without rest" stat on the OSTRC card reads from it), just no longer as
// its own dedicated card here.
@Composable
fun TrainingTileScreen(onBack: () -> Unit, onOpenSessionDetail: (Long) -> Unit = {}) {
    var tab by remember { mutableStateOf(TrainingTab.Load) }
    // 25/9 rework: one switch for the whole Load tab -- drives the CTL/ATL/TSB
    // charts and the PERFORMANCE chart (RUNNING keeps its own 7D-1Y toggle).
    var timeframe by remember { mutableStateOf(PerformanceTimeframe.ThreeMonths) }

    Column(modifier = Modifier.fillMaxSize().background(FT.Base).verticalScroll(rememberScrollState())) {
        TileHeader(onBack = onBack)
        SubTabRow(items = TrainingTab.entries, selected = tab, label = { it.label }, onSelect = { tab = it })

        when (tab) {
            TrainingTab.Load -> {
                SegmentedToggle(
                    options = PerformanceTimeframe.entries,
                    selected = timeframe,
                    labelOf = { it.label },
                    onSelect = { timeframe = it },
                    accentColor = FT.DomainTraining,
                    modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp),
                )
                TrainingLoadSection(timeframe)
                TrainingScreen(timeframe, onOpenSessionDetail)
            }
            TrainingTab.Injury -> InjuryTab()
        }
    }
}

// DAV-216 (24/9 fixes): moved from HeartTileScreen.kt's HeartTab.Injury
// branch verbatim -- independently loaded rather than sharing
// TrainingLoadSection()'s load, matching this file's existing pattern of
// each subtab loading its own data (same as FuelTileScreen's Nutrition vs
// Digestion tabs).
@Composable
private fun InjuryTab() {
    var sessions by remember { mutableStateOf<List<TrainingLoadSessionRow>?>(null) }
    var wearable by remember { mutableStateOf<List<WearableAnalysisRow>?>(null) }
    var wellbeing by remember { mutableStateOf<List<WellbeingAnalysisRow>?>(null) }
    var ostrc by remember { mutableStateOf<List<OstrcAnalysisRow>?>(null) }

    LaunchedEffect(Unit) {
        val repo = AnalysisRepository(SupabaseClientProvider.client)
        sessions = repo.loadExerciseSessionsForTrainingLoad()
        wearable = repo.loadWearableDaily()
        wellbeing = repo.loadWellbeingDaily()
        ostrc = repo.loadOstrcCheckins()
    }

    val s = sessions
    val w = wearable
    val wb = wellbeing
    val os = ostrc
    if (s == null || w == null || wb == null || os == null) {
        Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = FT.DomainTraining)
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        HealthEventsScreen()
        // DAV-205 (24/9 fixes): TRIMP fallback, same shared builder Load tab uses.
        val sessionLoads = buildTrainingSessionLoads(s, w.lastOrNull()?.rhr)
        val trainingLoadEval = evaluateTrainingLoad(sessionLoads)
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
    FTCard(title = "OSTRC-H2 · ${eval.bodyArea.uppercase()}") {
        StatLine("Confidence", eval.confidence.label)
        eval.latestSeverityScore?.let { StatLine("Latest severity", "$it / 100") }
        eval.latestCheckDate?.let { StatLine("Last check-in", it.toString()) }
        Text(
            "LOAD CONTEXT (shown adjacent, never combined into one score)",
            style = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp, letterSpacing = 0.14f.em),
            color = FT.TextMuted,
        )
        tsb?.let { StatLine("TSB (form)", "%+.1f".format(it)) }
        StatLine("Days without rest", "$daysWithoutRest")
        sorenessMedian?.let { StatLine("7-day soreness median", "%.1f".format(it)) }
        RestCadenceStrip(weeklyRestCadence)
        Text(
            "No injury risk score — single-factor screening doesn't predict injury. You do the synthesis; this doesn't.",
            style = TextStyle(fontFamily = Inter, fontSize = 12.sp),
            color = FT.TextMuted,
        )
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
            style = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp, letterSpacing = 0.14f.em),
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
private fun TrainingReadinessCard(result: TrainingReadinessResult) {
    FTCard(title = "TODAY'S READINESS") {
        FTStatePill(result.generalState.toMetricState())
        StatLine("Confidence", result.confidence.label)
        result.demandRelative.forEach { (tier, state) ->
            val label = when (tier) {
                TrainingDemandTier.EASY_AEROBIC -> "Easy aerobic"
                TrainingDemandTier.THRESHOLD -> "Threshold"
                TrainingDemandTier.LONG_HIGH_LOAD -> "Long / high-load"
                TrainingDemandTier.STRENGTH -> "Strength"
            }
            val symbol = when (state) {
                EvalState.ShiftUp -> "Ready"
                EvalState.Stable -> "Matched"
                EvalState.ShiftDown -> "Caution"
                else -> "Building"
            }
            StatLine(label, symbol)
        }
    }
}

@Composable
private fun TrainingLoadSection(timeframe: PerformanceTimeframe) {
    var sessions by remember { mutableStateOf<List<TrainingLoadSessionRow>?>(null) }
    var wearable by remember { mutableStateOf<List<WearableAnalysisRow>?>(null) }
    var activeCycle by remember { mutableStateOf<TrainingCycle?>(null) }
    var cycleLoaded by remember { mutableStateOf(false) }
    // 09.4 Training Readiness needs Dynamic Recovery's full contributor set
    // -- each subtab in this file already loads its own data independently
    // (see InjuryTab()'s own comment), so this follows the same convention
    // rather than sharing state with HeartTileScreen.kt.
    var sleep by remember { mutableStateOf<List<SleepAnalysisRow>?>(null) }
    var wellbeing by remember { mutableStateOf<List<WellbeingAnalysisRow>?>(null) }
    var stressDays by remember { mutableStateOf<List<StressDay>>(emptyList()) }

    LaunchedEffect(Unit) {
        val repo = AnalysisRepository(SupabaseClientProvider.client)
        sessions = repo.loadExerciseSessionsForTrainingLoad()
        wearable = repo.loadWearableDaily()
        activeCycle = TrainingCyclesRepository(SupabaseClientProvider.client).loadActiveCycle()
        cycleLoaded = true
        sleep = repo.loadSleepDaily()
        wellbeing = repo.loadWellbeingDaily()
        stressDays = ZeppStressRepository(SupabaseClientProvider.client).loadStressDays()
    }

    val s = sessions
    val w = wearable
    val sl = sleep
    val wb = wellbeing
    if (s == null || w == null || !cycleLoaded || sl == null || wb == null) {
        Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = FT.DomainTraining)
        }
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp).padding(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        // DAV-205 (24/9 fixes): TRIMP fallback for sessions with no RPE --
        // see AnalysisRepository.buildTrainingSessionLoads()'s own comment.
        val sessionLoads = buildTrainingSessionLoads(s, w.lastOrNull()?.rhr)
        val trainingLoadEval = evaluateTrainingLoad(sessionLoads)

        // 09.4 Training Readiness: consumes Dynamic Recovery (09.2) directly,
        // never recomputes CTL/ATL/TSB itself (DAV-250's own rule) -- the
        // same trainingLoadEval the card below already renders.
        val nights = sl.mapNotNull { row ->
            val bedtime = row.bedtime?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }
            val wake = row.wakeTime?.let { runCatching { java.time.Instant.parse(it) }.getOrNull() }
            if (bedtime != null && wake != null) SleepNight(LocalDate.parse(row.date), bedtime, wake) else null
        }
        val sleepIndexResult = computeSleepIndex(
            sl.mapNotNull { row -> row.hours?.let { LocalDate.parse(row.date) to it } },
            sl.mapNotNull { row -> row.deepMin?.let { LocalDate.parse(row.date) to it } },
            sl.mapNotNull { row -> row.respiratoryRate?.let { LocalDate.parse(row.date) to it } },
            nights,
        )
        val exerciseWindows = s.mapNotNull { session ->
            val start = runCatching { java.time.OffsetDateTime.parse(session.startTime) }.getOrNull()?.toInstant() ?: return@mapNotNull null
            val durationMin = session.durationMin ?: return@mapNotNull null
            start to start.plusSeconds((durationMin * 60).toLong())
        }
        val physiologicalStress = stressDays.firstOrNull()?.let { analyzeStressDay(it, stressDays.drop(1), exerciseWindows) }
        val recovery = computeDynamicRecovery(
            sleepIndex = sleepIndexResult,
            hrvEval = evaluateHrv(w.mapNotNull { row -> row.hrv?.let { LocalDate.parse(row.date) to it } }),
            rhrEval = evaluateRhr(w.mapNotNull { row -> row.rhr?.let { LocalDate.parse(row.date) to it } }),
            trainingLoadEval = trainingLoadEval,
            energyEval = evaluateSubjective(wb.mapNotNull { row -> row.energy?.let { LocalDate.parse(row.date) to it.toDouble() } }),
            stressEval = evaluateSubjective(wb.mapNotNull { row -> row.stress?.let { LocalDate.parse(row.date) to it.toDouble() } }),
            sorenessEval = evaluateSubjective(wb.mapNotNull { row -> row.soreness?.let { LocalDate.parse(row.date) to it.toDouble() } }),
            physiologicalStress = physiologicalStress,
        )
        TrainingReadinessCard(computeTrainingReadiness(recovery))

        TrainingLoadCard(trainingLoadEval, resolveTier(MetricCategory.TrainingLoad, activeCycle), trainingLoadSeries(sessionLoads), timeframe)
    }
}

@Composable
private fun TrainingLoadCard(eval: TrainingLoadEvaluation, tier: ExpectationTier?, series: List<TrainingLoadPoint>, timeframe: PerformanceTimeframe) {
    val cutoff = LocalDate.now().minusMonths(timeframe.months)
    val visible = series.filter { it.date >= cutoff }

    FTCard(title = "TRAINING LOAD") {
        // One banded gauge on the TSB axis replaces the old State pill and
        // "TSB band" row -- the band name IS the state, and the gauge shows
        // where inside the band you sit. Building/NoData has no TSB yet, so
        // the pill stays as the honest fallback.
        val tsb = eval.tsb
        if (tsb != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("FORM (TSB BAND)", style = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp, letterSpacing = 0.14f.em), color = FT.TextMuted)
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
                Text("State", style = TextStyle(fontFamily = Inter, fontSize = 14.5.sp), color = FT.TextSecondary)
                FTStatePill(eval.state.toMetricState())
            }
        }
        StatLine("Confidence", eval.confidence.label)
        eval.ctl?.let { LoadMetricBlock("CTL (fitness)", "%.1f".format(it), "CTL — fitness", LOAD_HELP_CTL, visible.map { p -> p.date to p.ctl }, FT.Category.Activity.c500) }
        eval.atl?.let { LoadMetricBlock("ATL (fatigue)", "%.1f".format(it), "ATL — fatigue", LOAD_HELP_ATL, visible.map { p -> p.date to p.atl }, FT.Category.Activity.c500) }
        eval.tsb?.let {
            LoadMetricBlock("TSB (form)", "%+.1f".format(it), "TSB — form", LOAD_HELP_TSB, visible.map { p -> p.date to p.tsb }, FT.Category.Activity.c500, refLow = TSB_LOADED_BELOW, refHigh = TSB_FRESHENED_FROM, signed = true)
        }
        // Phase A3: a pure re-label of the state above, never a
        // recomputation -- only rendered when a training cycle is actually
        // active. No active cycle means no framing applies, not "unmanaged."
        tier?.let { StatLine("This cycle", tierLabel(it)) }
    }
}

// Label + value + "?" on one row, a small history line of the same metric under it.
@Composable
private fun LoadMetricBlock(
    label: String,
    value: String,
    helpTitle: String,
    helpBody: String,
    points: List<Pair<LocalDate, Double>>,
    color: androidx.compose.ui.graphics.Color,
    refLow: Double? = null,
    refHigh: Double? = null,
    signed: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(label, style = TextStyle(fontFamily = Inter, fontSize = 14.5.sp), color = FT.TextSecondary)
            InfoHelpButton(helpTitle, helpBody)
            Text(
                value,
                style = TextStyle(fontFamily = RobotoMono, fontSize = 14.5.sp),
                color = FT.TextPrimary,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
        }
        if (points.size >= 2) {
            DateTrendLine(
                points = points,
                color = color,
                refLow = refLow,
                refHigh = refHigh,
                valueFormat = { v -> if (signed) "%+.1f".format(v) else "%.1f".format(v) },
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

// DAV-108: label is unweighted (always a short fixed phrase in practice) so
// it never shrinks; value takes the rest of the row via weight(1f) so a long
// value (e.g. "NEEDS 8 WEEKS OF LOAD HISTORY") wraps within its own bounded
// width and stays right-aligned instead of colliding with the label -- the
// bug this exact Row+SpaceBetween-with-two-unweighted-Texts shape produced
// identically across every screen's own private StatLine copy.
@Composable
private fun StatLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = TextStyle(fontFamily = Inter, fontSize = 14.5.sp), color = FT.TextSecondary)
        Text(
            value,
            style = TextStyle(fontFamily = RobotoMono, fontSize = 14.5.sp),
            color = FT.TextPrimary,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
    }
}
