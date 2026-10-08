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
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.AnalysisRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.model.LogSexualActivityRow
import com.bioscan.fieldterminal.data.model.SleepAnalysisRow
import com.bioscan.fieldterminal.data.model.TrainingLoadSessionRow
import com.bioscan.fieldterminal.data.model.MealRow
import com.bioscan.fieldterminal.data.NutritionRepository
import com.bioscan.fieldterminal.data.buildTrainingSessionLoads
import com.bioscan.fieldterminal.data.model.WearableAnalysisRow
import com.bioscan.fieldterminal.data.model.WellbeingAnalysisRow
import com.bioscan.fieldterminal.domain.DisplayValue
import com.bioscan.fieldterminal.domain.EvalState
import com.bioscan.fieldterminal.domain.MetricState
import com.bioscan.fieldterminal.domain.toMetricState
import com.bioscan.fieldterminal.domain.PersonalRange
import com.bioscan.fieldterminal.domain.RangeKind
import com.bioscan.fieldterminal.domain.RespiratoryAnomalyEvaluation
import com.bioscan.fieldterminal.data.BenchmarkArtifactRepository
import com.bioscan.fieldterminal.domain.analysis.ComparisonResult
import com.bioscan.fieldterminal.domain.analysis.Directionality
import com.bioscan.fieldterminal.domain.comparison.BenchmarkArtifact
import com.bioscan.fieldterminal.domain.comparison.METRIC_DIRECTIONALITY
import com.bioscan.fieldterminal.domain.comparison.PopulationContext
import com.bioscan.fieldterminal.domain.comparison.comparePersonal
import com.bioscan.fieldterminal.domain.comparison.comparePopulation
import com.bioscan.fieldterminal.domain.SleepNight
import com.bioscan.fieldterminal.domain.SriEvaluation
import com.bioscan.fieldterminal.domain.SleepIndexResult
import com.bioscan.fieldterminal.domain.computeSleepIndex
import com.bioscan.fieldterminal.domain.MovementIndexResult
import com.bioscan.fieldterminal.domain.computeMovementIndex
import com.bioscan.fieldterminal.domain.CircadianAlignmentResult
import com.bioscan.fieldterminal.domain.computeCircadianAlignment
import com.bioscan.fieldterminal.data.ZeppStressRepository
import com.bioscan.fieldterminal.domain.stress.StressDay
import com.bioscan.fieldterminal.domain.stress.StressRhythmResult
import com.bioscan.fieldterminal.domain.stress.analyzeStressDay
import com.bioscan.fieldterminal.domain.evaluateTrainingLoad
import com.bioscan.fieldterminal.domain.TrainingLoadEvaluation
import com.bioscan.fieldterminal.domain.DynamicRecoveryResult
import com.bioscan.fieldterminal.domain.computeDynamicRecovery
import com.bioscan.fieldterminal.domain.SubjectiveEvaluation
import com.bioscan.fieldterminal.domain.SwcEvaluation
import com.bioscan.fieldterminal.domain.VitalsTimeframe
import com.bioscan.fieldterminal.domain.evaluateHrv
import com.bioscan.fieldterminal.domain.evaluateRespiratoryAnomaly
import com.bioscan.fieldterminal.domain.evaluateRhr
import com.bioscan.fieldterminal.domain.evaluateSpo2
import com.bioscan.fieldterminal.domain.evaluateSleepDuration
import com.bioscan.fieldterminal.domain.evaluateSri
import com.bioscan.fieldterminal.domain.evaluateSubjective
import com.bioscan.fieldterminal.domain.expValue
import com.bioscan.fieldterminal.domain.interpretSwcEvaluation
import com.bioscan.fieldterminal.domain.vitalsTrendSeries
import com.bioscan.fieldterminal.ui.components.ComparisonStrip
import com.bioscan.fieldterminal.ui.components.DateTrendLine
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTMetricValue
import com.bioscan.fieldterminal.ui.components.FTRangeIndicator
import com.bioscan.fieldterminal.ui.components.FTStatePill
import com.bioscan.fieldterminal.ui.components.stateTreatment
import com.bioscan.fieldterminal.ui.components.SegmentedToggle
import com.bioscan.fieldterminal.ui.components.SubTabRow
import com.bioscan.fieldterminal.ui.components.TileHeader
import com.bioscan.fieldterminal.ui.nav.HeartTab
import com.bioscan.fieldterminal.domain.TimePoint
import com.bioscan.fieldterminal.ui.components.FTMetricRow
import com.bioscan.fieldterminal.ui.components.LineChart
import com.bioscan.fieldterminal.ui.components.FTScoreRow
import com.bioscan.fieldterminal.ui.components.FTSegment
import com.bioscan.fieldterminal.ui.components.FTSegmentBar
import com.bioscan.fieldterminal.ui.components.FTConfidenceChip
import com.bioscan.fieldterminal.ui.components.confidenceLevel
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

// DAV-97: 7 tabs collapsed to 4 (Cardio/Recovery/Wellbeing/Injury) per
// design/FIELD_TERMINAL_IA_CONTRACT.md section 7. Every card here (except
// Arousal, which has no Analysis Layer evaluation yet -- only ever logged,
// never evaluated) is unchanged from before; only which tab shows it moved.
// Stool/Bristol content moved out entirely to FuelTileScreen's new Digestion
// tab (DAV-96) -- no longer loaded or rendered here at all. One shared load
// for the whole page so switching tabs is instant, matching the Fuel/
// Training tile pattern.
// DAV-216 (24/9 fixes): Injury moved out to TrainingTab -- down to 3 tabs
// (Cardio/Recovery/Wellbeing) now.
@Composable
fun HeartTileScreen(onBack: () -> Unit) {
    var tab by remember { mutableStateOf(HeartTab.Cardio) }

    var wearable by remember { mutableStateOf<List<WearableAnalysisRow>?>(null) }
    var sleep by remember { mutableStateOf<List<SleepAnalysisRow>?>(null) }
    var wellbeing by remember { mutableStateOf<List<WellbeingAnalysisRow>?>(null) }
    var sexualActivity by remember { mutableStateOf<List<LogSexualActivityRow>?>(null) }
    var stepsBenchmark by remember { mutableStateOf<List<BenchmarkArtifact>>(emptyList()) }
    var exerciseSessions by remember { mutableStateOf<List<TrainingLoadSessionRow>>(emptyList()) }
    var recentMealsForTiming by remember { mutableStateOf<List<MealRow>>(emptyList()) }
    var stressDays by remember { mutableStateOf<List<StressDay>>(emptyList()) }

    LaunchedEffect(Unit) {
        val repo = AnalysisRepository(SupabaseClientProvider.client)
        wearable = repo.loadWearableDaily()
        sleep = repo.loadSleepDaily()
        wellbeing = repo.loadWellbeingDaily()
        // Arousal folded into wellbeing_daily -- see `wellbeing` above.
        sexualActivity = repo.loadSexualActivityDaily()
        // DAV-196: first real population artifact (Tudor-Locke & Bassett's
        // steps/day categories) -- see docs/analysis-layer-2/25-population-benchmark-engine.md.
        stepsBenchmark = BenchmarkArtifactRepository(SupabaseClientProvider.client).loadArtifacts("steps")
        // 09.3 Movement Index: reuses the exact same session query the Load
        // tab's own CTL/ATL/TSB already uses -- no second exercise-session
        // read path.
        exerciseSessions = repo.loadExerciseSessionsForTrainingLoad()
        // 09.6 Circadian Alignment: same "generous margin" limit convention
        // this app's other 60-90 day lookbacks already use, not the
        // 10-row default loadRecentMeals() ships with for its usual
        // "repeat a recent meal" caller.
        recentMealsForTiming = NutritionRepository(SupabaseClientProvider.client).loadRecentMeals(limit = 200)
        // 05.1 Stress Rhythm: real payload confirmed 2026-09-30 (extractor
        // v8) -- decoded client-side from zepp_raw_extracts, see
        // ZeppStressRepository's own header comment for why no new table.
        stressDays = ZeppStressRepository(SupabaseClientProvider.client).loadStressDays()
    }

    Column(modifier = Modifier.fillMaxSize().background(FT.Base).verticalScroll(rememberScrollState())) {
        TileHeader(onBack = onBack)
        SubTabRow(items = HeartTab.entries, selected = tab, label = { it.label }, onSelect = { tab = it })

        val w = wearable
        val s = sleep
        val wb = wellbeing
        val sa = sexualActivity
        if (w == null || s == null || wb == null || sa == null) {
            Box(Modifier.fillMaxWidth().padding(vertical = 60.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = FT.DomainHeart)
            }
            return@Column
        }

        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            when (tab) {
                HeartTab.Cardio -> {
                    // DAV-285: one shared timeframe for both HRV and RHR's trend
                    // charts -- they're the same kind of daily wearable stream, so
                    // a person comparing them wants the same window on both, not
                    // two independent toggles.
                    var vitalsTimeframe by remember { mutableStateOf(VitalsTimeframe.Month) }
                    SegmentedToggle(options = VitalsTimeframe.entries, selected = vitalsTimeframe, labelOf = { it.label }, onSelect = { vitalsTimeframe = it })

                    val hrvPoints = w.mapNotNull { row -> row.hrv?.let { LocalDate.parse(row.date) to it } }
                    // DAV-200: first live wiring of the new comparison layer --
                    // real ms values (not the SWC evaluation's own log-space
                    // baseline), the latest reading compared against every
                    // earlier one.
                    val hrvComparison = hrvPoints.maxByOrNull { it.first }?.let { latest ->
                        comparePersonal(
                            metric = "hrv",
                            current = latest.second,
                            history = hrvPoints.filter { it.first != latest.first },
                            presentSources = setOf("wearable_daily"),
                            idealSources = setOf("wearable_daily"),
                            origin = "health_connect",
                        )
                    }
                    EvalCard("HRV", evaluateHrv(hrvPoints).toExpSpace(), "ms", directionality = METRIC_DIRECTIONALITY.getValue("hrv"), points = hrvPoints, comparison = hrvComparison, timeframe = vitalsTimeframe)
                    // v12: intraday HR from Zepp band_data. Show for the most
                    // recent day that has data; silently absent when not yet synced.
                    val todayHr = w.firstOrNull { it.hrTimeseries?.isNotEmpty() == true }?.hrTimeseries
                    if (todayHr != null) {
                        val hrPoints = todayHr.map { TimePoint(it.offsetMinutes * 60L, it.bpm.toDouble()) }
                        FTCard(title = "DAILY HEART RATE") {
                            LineChart(points = hrPoints, color = FT.DomainHeart, modifier = androidx.compose.ui.Modifier.fillMaxWidth().height(120.dp), filled = true)
                        }
                    }
                    val rhrPoints = w.mapNotNull { row -> row.rhr?.let { LocalDate.parse(row.date) to it } }
                    // Same live comparison wiring as HRV above (DAV-200) --
                    // "resting_heart_rate" is the canonical registry name
                    // (docs/analysis-layer-2/02-metric-registry.md), not "rhr".
                    val rhrComparison = rhrPoints.maxByOrNull { it.first }?.let { latest ->
                        comparePersonal(
                            metric = "resting_heart_rate",
                            current = latest.second,
                            history = rhrPoints.filter { it.first != latest.first },
                            presentSources = setOf("wearable_daily"),
                            idealSources = setOf("wearable_daily"),
                            origin = "health_connect",
                        )
                    }
                    EvalCard("RESTING HEART RATE", evaluateRhr(rhrPoints), "bpm", directionality = METRIC_DIRECTIONALITY.getValue("resting_heart_rate"), points = rhrPoints, comparison = rhrComparison, timeframe = vitalsTimeframe)
                    val spo2Points = w.mapNotNull { row -> row.spo2?.let { LocalDate.parse(row.date) to it } }
                    EvalCard("SPO2", evaluateSpo2(spo2Points), "%", directionality = Directionality.NON_DIRECTIONAL, points = spo2Points, timeframe = vitalsTimeframe)
                    val rrPoints = s.mapNotNull { row -> row.respiratoryRate?.let { LocalDate.parse(row.date) to it } }
                    RespiratoryCard(evaluateRespiratoryAnomaly(rrPoints))
                    val stepsPoints = w.mapNotNull { row -> row.steps?.let { LocalDate.parse(row.date) to it } }
                    StepsCard(stepsPoints, stepsBenchmark)
                    val sessionStarts = exerciseSessions.mapNotNull { runCatching { OffsetDateTime.parse(it.startTime) }.getOrNull() }
                    MovementIndexCard(computeMovementIndex(stepsPoints, sessionStarts))
                }
                HeartTab.Recovery -> {
                    val hoursPoints = s.mapNotNull { row -> row.hours?.let { LocalDate.parse(row.date) to it } }
                    val nights = s.mapNotNull { row ->
                        val bedtime = row.bedtime?.let { runCatching { Instant.parse(it) }.getOrNull() }
                        val wake = row.wakeTime?.let { runCatching { Instant.parse(it) }.getOrNull() }
                        if (bedtime != null && wake != null) SleepNight(LocalDate.parse(row.date), bedtime, wake) else null
                    }
                    val deepPoints = s.mapNotNull { row -> row.deepMin?.let { LocalDate.parse(row.date) to it } }
                    val respiratoryForIndex = s.mapNotNull { row -> row.respiratoryRate?.let { LocalDate.parse(row.date) to it } }
                    val sleepIndexResult = computeSleepIndex(hoursPoints, deepPoints, respiratoryForIndex, nights)

                    // 09.2 Dynamic Recovery: a hero card above everything else
                    // in this tab, same placement pattern AgingCard already
                    // uses atop UserProfileScreen.kt -- reuses every
                    // contributor's existing evaluation, recomputes nothing.
                    val exerciseWindowsForRecovery = exerciseSessions.mapNotNull { session ->
                        val start = runCatching { OffsetDateTime.parse(session.startTime) }.getOrNull()?.toInstant() ?: return@mapNotNull null
                        val durationMin = session.durationMin ?: return@mapNotNull null
                        start to start.plusSeconds((durationMin * 60).toLong())
                    }
                    val physiologicalStressForRecovery = stressDays.firstOrNull()?.let { analyzeStressDay(it, stressDays.drop(1), exerciseWindowsForRecovery) }
                    val sessionLoadsForRecovery = buildTrainingSessionLoads(exerciseSessions, w.lastOrNull()?.rhr)
                    DynamicRecoveryCard(
                        computeDynamicRecovery(
                            sleepIndex = sleepIndexResult,
                            hrvEval = evaluateHrv(w.mapNotNull { row -> row.hrv?.let { LocalDate.parse(row.date) to it } }),
                            rhrEval = evaluateRhr(w.mapNotNull { row -> row.rhr?.let { LocalDate.parse(row.date) to it } }),
                            trainingLoadEval = evaluateTrainingLoad(sessionLoadsForRecovery),
                            energyEval = evaluateSubjective(wb.mapNotNull { row -> row.energy?.let { LocalDate.parse(row.date) to it.toDouble() } }),
                            stressEval = evaluateSubjective(wb.mapNotNull { row -> row.stress?.let { LocalDate.parse(row.date) to it.toDouble() } }),
                            sorenessEval = evaluateSubjective(wb.mapNotNull { row -> row.soreness?.let { LocalDate.parse(row.date) to it.toDouble() } }),
                            physiologicalStress = physiologicalStressForRecovery,
                            morningWoodEval = evaluateSubjective(wb.mapNotNull { row -> row.morningErectionQuality?.let { LocalDate.parse(row.date) to it.toDouble() } }),
                            arousalEval = evaluateSubjective(wb.mapNotNull { row -> row.arousalLevel?.let { LocalDate.parse(row.date) to it.toDouble() } }),
                        ),
                    )

                    // 09.1 Sleep Index: a headline aggregate above the same
                    // component cards below it -- an aggregation layer, never
                    // a replacement (DAV-235's own rule).
                    SleepIndexCard(sleepIndexResult)
                    EvalCard("SLEEP DURATION", evaluateSleepDuration(hoursPoints), "h", directionality = METRIC_DIRECTIONALITY.getValue("sleep_duration"), points = hoursPoints)
                    val sriEval = evaluateSri(nights)
                    SriCard(sriEval)
                    SleepPhasesCard(s)
                    // 09.6 Circadian Alignment: sleep timing reuses the same
                    // SriEvaluation above (no second algorithm); meal/exercise
                    // timing reuse the meals/exercise-session data already
                    // loaded for logging and Movement Index respectively.
                    val mealTimestamps = recentMealsForTiming.mapNotNull { runCatching { OffsetDateTime.parse(it.loggedAt) }.getOrNull() }
                    val exerciseTimestamps = exerciseSessions.mapNotNull { runCatching { OffsetDateTime.parse(it.startTime) }.getOrNull() }
                    CircadianAlignmentCard(computeCircadianAlignment(sriEval, mealTimestamps, exerciseTimestamps))
                }
                HeartTab.Wellbeing -> {
                    // DAV-101: small multiples, not one collapsed score.
                    // DAV-214 (24/9 fixes): was a 2-column grid of compact
                    // cards -- switched to 4 full-width stacked bands so each
                    // card's existing DateTrendLine (SubjectiveCard already
                    // rendered one) gets the full width instead of being
                    // squeezed to half-screen.
                    val dimensions = listOf(
                        "ENERGY" to wb.mapNotNull { row -> row.energy?.let { LocalDate.parse(row.date) to it.toDouble() } },
                        "MOOD" to wb.mapNotNull { row -> row.mood?.let { LocalDate.parse(row.date) to it.toDouble() } },
                        "STRESS" to wb.mapNotNull { row -> row.stress?.let { LocalDate.parse(row.date) to it.toDouble() } },
                        "SORENESS" to wb.mapNotNull { row -> row.soreness?.let { LocalDate.parse(row.date) to it.toDouble() } },
                        // Arousal fold-in: morning-wood/arousal are now part
                        // of the same daily wellness survey, shown the same
                        // way as the other four dimensions here.
                        "MORNING WOOD" to wb.mapNotNull { row -> row.morningErectionQuality?.let { LocalDate.parse(row.date) to it.toDouble() } },
                        "AROUSAL" to wb.mapNotNull { row -> row.arousalLevel?.let { LocalDate.parse(row.date) to it.toDouble() } },
                    )
                    dimensions.forEach { (title, points) -> SubjectiveCard(title, points, modifier = Modifier.fillMaxWidth()) }
                    // 05.1 Stress Rhythm: labeled distinctly from the
                    // subjective STRESS card above so the two are never
                    // conflated (DAV-246's own explicit requirement).
                    val exerciseWindows = exerciseSessions.mapNotNull { session ->
                        val start = runCatching { OffsetDateTime.parse(session.startTime) }.getOrNull()?.toInstant() ?: return@mapNotNull null
                        val durationMin = session.durationMin ?: return@mapNotNull null
                        start to start.plusSeconds((durationMin * 60).toLong())
                    }
                    stressDays.firstOrNull()?.let { today ->
                        PhysiologicalStressCard(analyzeStressDay(today, stressDays.drop(1), exerciseWindows), today)
                    }
                    SexualActivityHistory(sa)
                }
            }
        }
    }
}

// Arousal folded into the wellbeing_daily SubjectiveCards above (DAV-215's
// original arousal_daily rows are gone) -- this history is masturbation/
// intercourse-only now, reading sexual_activity_daily's per-instance detail.
@Composable
private fun SexualActivityHistory(rows: List<LogSexualActivityRow>) {
    if (rows.isEmpty()) {
        Text("No entries logged yet.", style = FTType.Body, color = FT.TextSecondary)
        return
    }
    val sorted = rows.sortedByDescending { it.date }
    FTCard(title = "RECENT ENTRIES", info = "No Analysis Layer evaluation exists for this yet -- this is real recent log history only.") {
        sorted.forEach { row ->
            val orgasmCount = row.instances.count { it.orgasm }
            val text = "${row.activityType.replaceFirstChar { it.uppercase() }} · ${row.instances.size} instance${if (row.instances.size == 1) "" else "s"}" +
                if (orgasmCount > 0) " · $orgasmCount with orgasm" else ""
            Row(modifier = Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(row.date, style = FTType.Value, color = FT.TextSecondary)
                Text(text, style = FTType.BodySmall, color = FT.TextPrimary)
            }
        }
    }
}

internal fun SwcEvaluation.toExpSpace(): SwcEvaluation = copy(baseline7d = expValue(baseline7d), mean60d = expValue(mean60d))

// DAV-75/101: baseline/mean/CV were plain numbers with no sense of the real
// day-to-day shape behind them, and no personal-range context -- the raw
// series each call site already computes (to feed evaluateHrv/evaluateRhr/
// evaluateSleepDuration) gets plotted here too (capped to the same 90-day
// window WeightCard/VO2max already established), and the 60-day mean +/-
// SWC band becomes a real FTRangeIndicator personal baseline rather than
// two disconnected StatLine rows.
@Composable
internal fun EvalCard(
    title: String,
    eval: SwcEvaluation,
    unit: String,
    directionality: Directionality,
    points: List<Pair<LocalDate, Double>>? = null,
    comparison: ComparisonResult? = null,
    // DAV-285: HRV/RHR pass a real VitalsTimeframe selection (7D shows raw
    // daily points, longer windows a weekly-mean rollup -- vitalsTrendSeries).
    // Every other EvalCard caller (Sleep Duration, etc.) leaves this null and
    // keeps the original fixed 30-day window unchanged.
    timeframe: VitalsTimeframe? = null,
    modifier: Modifier = Modifier,
) {
    FTCard(title = title, modifier = modifier) {
        FTStatePill(eval.state.toMetricState())
        FTConfidenceChip(confidenceLevel(eval.confidence))
        comparison?.let { ComparisonStrip(it) }
        var bandLow: Double? = null
        var bandHigh: Double? = null
        if (eval.mean60d != null && eval.swcPct != null) {
            val band = eval.mean60d * (eval.swcPct / 100.0)
            bandLow = eval.mean60d - band
            bandHigh = eval.mean60d + band
            FTRangeIndicator(
                PersonalRange(
                    kind = RangeKind.PersonalBaseline,
                    lower = bandLow,
                    upper = bandHigh,
                    baseline = eval.mean60d,
                    current = eval.baseline7d,
                    label = "60-DAY BASELINE ± SWC ($unit)",
                    sufficientHistory = true,
                ),
            )
        }
        eval.cv7d?.let { FTMetricRow("7-day CV", "%.1f%%".format(it)) }
        val series = points?.let { pts ->
            if (timeframe != null) vitalsTrendSeries(pts, timeframe).takeIf { it.size >= 2 }
            else recentTrendWindow(pts)
        }
        series?.let {
            DateTrendLine(
                points = it,
                color = FT.DomainHeart,
                refLow = bandLow,
                refHigh = bandHigh,
                highlightColor = stateTreatment(eval.state.toMetricState()).color,
                modifier = Modifier.padding(top = 8.dp),
                unit = unit,
            )
        }
        interpretSwcEvaluation(title, eval, directionality)?.let { sentence ->
            Text(sentence, style = FTType.Caption, color = FT.TextMuted, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

// Live check: 90 days diluted the visible trend with old history and made
// the recent, actually-relevant month hard to read -- narrowed to 30,
// shared by every EvalCard on both the Cardio and Recovery tabs.
private const val TREND_WINDOW_DAYS = 30L

private fun recentTrendWindow(points: List<Pair<LocalDate, Double>>): List<Pair<LocalDate, Double>>? {
    if (points.size < 2) return null
    val today = LocalDate.now()
    val recent = points.filter { ChronoUnit.DAYS.between(it.first, today) <= TREND_WINDOW_DAYS }
    return recent.takeIf { it.size >= 2 }
}

@Composable
private fun DynamicRecoveryCard(result: DynamicRecoveryResult) {
    FTCard(title = "TODAY'S RECOVERY") {
        FTStatePill(result.state.toMetricState())
        FTConfidenceChip(confidenceLevel(result.confidence))
        if (result.contributors.isEmpty()) {
            Text(
                "Not enough contributors yet -- needs at least 2 of sleep/HRV/RHR/training load/subjective state.",
                style = FTType.BodySmall,
                color = FT.TextSecondary,
            )
        } else {
            result.contributors.forEach { contributor ->
                // Arrow + word, so direction never rests on glyph or color alone.
                val effect = when (contributor.direction) {
                    1 -> "↑ SUPPORTS RECOVERY"
                    -1 -> "↓ HOLDS RECOVERY BACK"
                    0 -> "= NEUTRAL"
                    else -> "— NO SIGNAL YET"
                }
                FTMetricRow(contributor.dimension.replace('_', ' ').replaceFirstChar { it.uppercase() }, effect)
            }
        }
    }
}

@Composable
private fun SleepIndexCard(result: SleepIndexResult) {
    FTCard(title = "SLEEP INDEX") {
        FTConfidenceChip(confidenceLevel(result.confidence))
        if (result.score != null) {
            FTMetricValue(DisplayValue(primary = "%.0f".format(result.score), unit = "/ 100", secondary = result.band))
            result.components.forEach { (component, componentScore) ->
                FTScoreRow(component.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }, componentScore.score, FT.Category.Sleep.c500)
            }
        } else {
            Text(
                "Not enough components yet -- needs at least 2 of duration/regularity/respiratory/stage-composition.",
                style = FTType.BodySmall,
                color = FT.TextSecondary,
            )
        }
    }
}

@Composable
private fun CircadianAlignmentCard(result: CircadianAlignmentResult) {
    FTCard(title = "CIRCADIAN ALIGNMENT", info = "A behavioral regularity proxy, not a measured circadian phase.") {
        FTConfidenceChip(confidenceLevel(result.confidence))
        if (result.score != null) {
            FTMetricValue(DisplayValue(primary = "%.0f".format(result.score), unit = "/ 100", secondary = result.band))
            result.components.forEach { (component, componentScore) ->
                FTScoreRow(component.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }, componentScore.score, FT.Category.Sleep.c300)
            }
        } else {
            Text(
                "Not enough sleep/meal/exercise timing history yet.",
                style = FTType.BodySmall,
                color = FT.TextSecondary,
            )
        }
    }
}

@Composable
private fun PhysiologicalStressCard(result: StressRhythmResult, today: StressDay) {
    // "PHYSIOLOGICAL STRESS" not "STRESS" -- never visually conflated with
    // the subjective SubjectiveCard("STRESS") above it (DAV-246's own rule).
    FTCard(title = "PHYSIOLOGICAL STRESS") {
        FTConfidenceChip(confidenceLevel(result.confidence))
        val summary = today.summary
        if (summary.avg != null) {
            FTMetricValue(DisplayValue(primary = "${summary.avg}", unit = "/ 100 avg today"))
        }
        val relax = summary.relaxProportion
        val normal = summary.normalProportion
        val medium = summary.mediumProportion
        val high = summary.highProportion
        if (relax != null && normal != null && medium != null && high != null) {
            // Ordinal intensity (relax -> high): one hue, rising opacity, not a
            // state ramp -- the legend carries the labels and percentages.
            val stressHue = FT.Category.Wellbeing.c500
            FTSegmentBar(
                listOf(
                    FTSegment("Relax", relax.toDouble(), stressHue.copy(alpha = 0.30f)),
                    FTSegment("Normal", normal.toDouble(), stressHue.copy(alpha = 0.50f)),
                    FTSegment("Medium", medium.toDouble(), stressHue.copy(alpha = 0.75f)),
                    FTSegment("High", high.toDouble(), stressHue),
                ),
            )
        }
        if (result.peakMagnitude != null) {
            FTMetricRow("Today's baseline", "%.0f".format(result.baseline))
            FTMetricRow("Peak above baseline", "+${result.peakMagnitude}")
            result.peakDurationMinutes?.let { FTMetricRow("Peak duration", "${it} min") }
            result.eveningDownRegulationPct?.let { FTMetricRow("Evening wind-down", "%.0f%%".format(it)) }
            result.deviationFromPersonalPattern?.let { FTMetricRow("Vs. your own recent average", "%+.0f".format(it)) }
        } else {
            Text(
                "Not enough intraday samples yet for the diurnal pattern -- daily summary above is still real.",
                style = FTType.Caption,
                color = FT.TextSecondary,
            )
        }
    }
}

@Composable
private fun SriCard(eval: SriEvaluation) {
    FTCard(title = "SLEEP REGULARITY (SRI)") {
        FTConfidenceChip(confidenceLevel(eval.confidence))
        if (eval.value != null) {
            FTMetricValue(DisplayValue(primary = "%.0f".format(eval.value), unit = "/ 100"))
        } else {
            Text(
                "Not enough consecutive nights yet (gaps over 2 nights reset the count).",
                style = FTType.BodySmall,
                color = FT.TextSecondary,
            )
        }
    }
}

// Live check: HealthConnectDailySyncRepository.syncSleep() already computes
// real per-night deep/rem/light minutes from SleepSessionRecord.stages and
// syncs them to sleep_daily -- nothing in the app ever displayed them. Shows
// the last 14 real nights' average as a proportion bar rather than one more
// day-by-day trend line (this is a composition question -- "where does sleep
// time go" -- not a day-to-day trend one).
private const val SLEEP_PHASE_WINDOW_DAYS = 14L

@Composable
private fun SleepPhasesCard(nights: List<SleepAnalysisRow>) {
    val today = LocalDate.now()
    val recent = nights.filter {
        it.deepMin != null && it.remMin != null && it.lightMin != null &&
            ChronoUnit.DAYS.between(LocalDate.parse(it.date), today) <= SLEEP_PHASE_WINDOW_DAYS
    }
    if (recent.isEmpty()) return

    val avgDeep = recent.map { it.deepMin!! }.average()
    val avgRem = recent.map { it.remMin!! }.average()
    val avgLight = recent.map { it.lightMin!! }.average()
    val avgTotal = (avgDeep + avgRem + avgLight).takeIf { it > 0 } ?: 1.0

    FTCard(title = "SLEEP PHASES") {
        FTSegmentBar(
            listOf(
                FTSegment("Deep", avgDeep, FT.Category.Sleep.c700),
                FTSegment("REM", avgRem, FT.Category.Sleep.c500),
                FTSegment("Light", avgLight, FT.Category.Sleep.c300),
            ),
            showLegend = false,
        )
        PhaseRow("Deep", avgDeep, avgTotal, FT.Category.Sleep.c700)
        PhaseRow("REM", avgRem, avgTotal, FT.Category.Sleep.c500)
        PhaseRow("Light", avgLight, avgTotal, FT.Category.Sleep.c300)
        FTMetricRow("Nights averaged", "${recent.size}")
    }
}

@Composable
private fun PhaseRow(label: String, minutes: Double, totalMinutes: Double, dotColor: androidx.compose.ui.graphics.Color) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.width(8.dp).height(8.dp).background(dotColor))
            Text(label, style = FTType.Body, color = FT.TextSecondary)
        }
        Text(
            "${minutes.toInt()} min  ·  %.0f%%".format(minutes / totalMinutes * 100),
            style = FTType.Value,
            color = FT.TextPrimary,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
    }
}

// Live check: wearable_daily.steps has 1,000+ real synced days, never read
// anywhere. No evaluate*() function exists for steps (no baseline/SWC
// concept defined for it, unlike HRV/RHR) -- a plain daily count and trend,
// not a full EvalCard, is the honest treatment.
private const val STEPS_WINDOW_DAYS = 15L

@Composable
private fun MovementIndexCard(result: MovementIndexResult) {
    FTCard(title = "MOVEMENT INDEX") {
        FTConfidenceChip(confidenceLevel(result.confidence))
        if (result.score != null) {
            FTMetricValue(DisplayValue(primary = "%.0f".format(result.score), unit = "/ 100"))
            result.components.forEach { (component, componentScore) ->
                FTScoreRow(component.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }, componentScore.score, FT.Category.Activity.c500)
            }
            if (result.unavailableComponents.isNotEmpty()) {
                Text(
                    "Active calories and zone minutes stay unavailable -- too little of this account's data has them yet.",
                    style = FTType.Caption,
                    color = FT.TextMuted,
                )
            }
        } else {
            Text(
                "Not enough step or session history yet.",
                style = FTType.BodySmall,
                color = FT.TextSecondary,
            )
        }
    }
}

@Composable
private fun StepsCard(points: List<Pair<LocalDate, Double>>, benchmarkArtifacts: List<BenchmarkArtifact>) {
    val today = LocalDate.now()
    val recent = points.filter { ChronoUnit.DAYS.between(it.first, today) <= STEPS_WINDOW_DAYS }
    if (recent.size < 2) return
    val avg = recent.map { it.second }.average()

    // DAV-211: this used to compare today's single (often still-partial) day
    // against the Tudor-Locke bands while the card displayed the 15-day
    // average right above it -- e.g. "17k+ AVG/DAY" next to a "sedentary"
    // badge that was actually describing an unrelated, much lower, in-progress
    // today. Comparing the same `avg` the card shows keeps the two numbers honest.
    val comparison = comparePopulation(
        metric = "steps",
        current = avg,
        context = PopulationContext(ageYears = null, sex = null, geography = null),
        artifacts = benchmarkArtifacts,
    )

    FTCard(title = "STEPS") {
        FTMetricValue(DisplayValue(primary = "${avg.toInt()}", unit = "AVG/DAY", secondary = "last 15 days"))
        // DAV-196: first real population artifact (Tudor-Locke & Bassett's
        // steps/day categories) -- no personal-history comparison wired for
        // steps yet.
        ComparisonStrip(population = comparison)
        DateTrendLine(points = recent, color = FT.DomainHeart, unit = "steps / day")
    }
}

@Composable
private fun RespiratoryCard(eval: RespiratoryAnomalyEvaluation) {
    FTCard(title = "RESPIRATORY RATE") {
        FTConfidenceChip(confidenceLevel(eval.confidence))
        eval.baseline?.let { FTMetricRow("14-night baseline", "%.1f breaths/min".format(it)) }
        Text(
            if (eval.flagged) {
                "Physiological anomaly flagged — 2 consecutive nights outside your baseline ±2 SD. Not a diagnosis."
            } else {
                "No anomaly flagged."
            },
            style = FTType.BodySmall,
            color = if (eval.flagged) FT.Critical else FT.TextSecondary,
        )
    }
}

@Composable
private fun SubjectiveCard(title: String, points: List<Pair<LocalDate, Double>>, modifier: Modifier = Modifier) {
    val eval = evaluateSubjective(points)
    FTCard(title = title, modifier = modifier) {
        FTStatePill(eval.state.toMetricState())
        FTConfidenceChip(confidenceLevel(eval.confidence))
        eval.median7d?.let { FTMetricRow("7-day median", "%.1f".format(it)) }
        eval.medianBaseline30d?.let { FTMetricRow("30-day baseline", "%.1f".format(it)) }
        eval.iqr7d?.let { FTMetricRow("7-day IQR", "%.1f".format(it)) }
        eval.trendDirection?.let { FTMetricRow("14-day trend", if (it > 0) "↑ rising (p<0.05)" else "↓ falling (p<0.05)") }
        recentTrendWindow(points)?.let { recent ->
            DateTrendLine(points = recent, color = FT.Category.Wellbeing.c500, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

