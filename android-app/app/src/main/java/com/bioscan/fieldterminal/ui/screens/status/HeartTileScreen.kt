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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bioscan.fieldterminal.data.AnalysisRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.model.LogArousalRow
import com.bioscan.fieldterminal.data.model.LogMasturbationRow
import com.bioscan.fieldterminal.data.model.SleepAnalysisRow
import com.bioscan.fieldterminal.data.model.TrainingLoadSessionRow
import com.bioscan.fieldterminal.data.model.MealRow
import com.bioscan.fieldterminal.data.NutritionRepository
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
import com.bioscan.fieldterminal.domain.comparison.BenchmarkArtifact
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
import com.bioscan.fieldterminal.domain.vitalsTrendSeries
import com.bioscan.fieldterminal.ui.components.ComparisonStrip
import com.bioscan.fieldterminal.ui.components.DateTrendLine
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTMetricValue
import com.bioscan.fieldterminal.ui.components.FTRangeIndicator
import com.bioscan.fieldterminal.ui.components.FTStatePill
import com.bioscan.fieldterminal.ui.components.SegmentedToggle
import com.bioscan.fieldterminal.ui.components.SubTabRow
import com.bioscan.fieldterminal.ui.components.TileHeader
import com.bioscan.fieldterminal.ui.nav.HeartTab
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import com.bioscan.fieldterminal.ui.theme.Inter
import com.bioscan.fieldterminal.ui.theme.RobotoMono
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
    var arousal by remember { mutableStateOf<List<LogArousalRow>?>(null) }
    var masturbation by remember { mutableStateOf<List<LogMasturbationRow>?>(null) }
    var stepsBenchmark by remember { mutableStateOf<List<BenchmarkArtifact>>(emptyList()) }
    var exerciseSessions by remember { mutableStateOf<List<TrainingLoadSessionRow>>(emptyList()) }
    var recentMealsForTiming by remember { mutableStateOf<List<MealRow>>(emptyList()) }

    LaunchedEffect(Unit) {
        val repo = AnalysisRepository(SupabaseClientProvider.client)
        wearable = repo.loadWearableDaily()
        sleep = repo.loadSleepDaily()
        wellbeing = repo.loadWellbeingDaily()
        // DAV-215 (24/9 fixes): both now routed through AnalysisRepository
        // like every other Heart data source, instead of an inline raw query.
        arousal = repo.loadArousalDaily()
        masturbation = repo.loadMasturbationLog()
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
    }

    Column(modifier = Modifier.fillMaxSize().background(FT.Base).verticalScroll(rememberScrollState())) {
        TileHeader(onBack = onBack)
        SubTabRow(items = HeartTab.entries, selected = tab, label = { it.label }, onSelect = { tab = it })

        val w = wearable
        val s = sleep
        val wb = wellbeing
        val ar = arousal
        val m = masturbation
        if (w == null || s == null || wb == null || ar == null || m == null) {
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
                    EvalCard("HRV", evaluateHrv(hrvPoints).toExpSpace(), "ms", points = hrvPoints, comparison = hrvComparison, timeframe = vitalsTimeframe)
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
                    EvalCard("RESTING HEART RATE", evaluateRhr(rhrPoints), "bpm", points = rhrPoints, comparison = rhrComparison, timeframe = vitalsTimeframe)
                    val spo2Points = w.mapNotNull { row -> row.spo2?.let { LocalDate.parse(row.date) to it } }
                    EvalCard("SPO2", evaluateSpo2(spo2Points), "%", points = spo2Points, timeframe = vitalsTimeframe)
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
                    // 09.1 Sleep Index: a headline aggregate above the same
                    // component cards below it -- an aggregation layer, never
                    // a replacement (DAV-235's own rule).
                    val deepPoints = s.mapNotNull { row -> row.deepMin?.let { LocalDate.parse(row.date) to it } }
                    val respiratoryForIndex = s.mapNotNull { row -> row.respiratoryRate?.let { LocalDate.parse(row.date) to it } }
                    SleepIndexCard(computeSleepIndex(hoursPoints, deepPoints, respiratoryForIndex, nights))
                    EvalCard("SLEEP DURATION", evaluateSleepDuration(hoursPoints), "h", points = hoursPoints)
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
                    )
                    dimensions.forEach { (title, points) -> SubjectiveCard(title, points, modifier = Modifier.fillMaxWidth()) }
                    ArousalHistory(ar, m)
                }
            }
        }
    }
}

// DAV-215 (24/9 fixes): merges masturbation_log entries into the same
// timeline instead of only ever showing arousal_daily rows. Encounter
// entries stay out of scope until copulation tracking itself is built.
private data class ArousalHistoryEntry(val date: LocalDate, val dateLabel: String, val text: String)

@Composable
private fun ArousalHistory(rows: List<LogArousalRow>, masturbation: List<LogMasturbationRow>) {
    val arousalEntries = rows.map { row ->
        ArousalHistoryEntry(
            date = LocalDate.parse(row.date),
            dateLabel = row.date,
            text = listOfNotNull(
                row.morningErectionQuality?.let { "Morning wood $it/10" },
                row.arousalLevel?.let { "Arousal $it/10" },
            ).joinToString(" · "),
        )
    }
    val masturbationEntries = masturbation.mapNotNull { row ->
        val at = runCatching { OffsetDateTime.parse(row.occurredAt) }.getOrNull() ?: return@mapNotNull null
        ArousalHistoryEntry(
            date = at.toLocalDate(),
            dateLabel = at.toLocalDate().toString(),
            text = "Masturbation" + (row.orgasmIntensity?.let { " · intensity $it/10" } ?: ""),
        )
    }
    val combined = (arousalEntries + masturbationEntries).sortedByDescending { it.date }

    if (combined.isEmpty()) {
        Text("No arousal entries logged yet.", style = TextStyle(fontFamily = Inter, fontSize = 15.5.sp), color = FT.TextSecondary)
        return
    }
    FTCard(title = "RECENT ENTRIES") {
        Text(
            "No Analysis Layer evaluation exists for arousal yet -- real recent log history only.",
            style = TextStyle(fontFamily = Inter, fontSize = 12.sp),
            color = FT.TextMuted,
        )
        combined.forEach { entry ->
            Row(modifier = Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(entry.dateLabel, style = TextStyle(fontFamily = RobotoMono, fontSize = 13.sp), color = FT.TextSecondary)
                Text(entry.text, style = TextStyle(fontFamily = Inter, fontSize = 13.sp), color = FT.TextPrimary)
            }
        }
    }
}

private fun SwcEvaluation.toExpSpace(): SwcEvaluation = copy(baseline7d = expValue(baseline7d), mean60d = expValue(mean60d))

// DAV-75/101: baseline/mean/CV were plain numbers with no sense of the real
// day-to-day shape behind them, and no personal-range context -- the raw
// series each call site already computes (to feed evaluateHrv/evaluateRhr/
// evaluateSleepDuration) gets plotted here too (capped to the same 90-day
// window WeightCard/VO2max already established), and the 60-day mean +/-
// SWC band becomes a real FTRangeIndicator personal baseline rather than
// two disconnected StatLine rows.
@Composable
private fun EvalCard(
    title: String,
    eval: SwcEvaluation,
    unit: String,
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
        StatLine("Confidence", eval.confidence.label)
        comparison?.let { ComparisonStrip(it) }
        if (eval.mean60d != null && eval.swcPct != null) {
            val band = eval.mean60d * (eval.swcPct / 100.0)
            FTRangeIndicator(
                PersonalRange(
                    kind = RangeKind.PersonalBaseline,
                    lower = eval.mean60d - band,
                    upper = eval.mean60d + band,
                    baseline = eval.mean60d,
                    current = eval.baseline7d,
                    label = "60-DAY BASELINE ± SWC ($unit)",
                    sufficientHistory = true,
                ),
            )
        }
        eval.cv7d?.let { StatLine("7-day CV", "%.1f%%".format(it)) }
        val series = points?.let { pts ->
            if (timeframe != null) vitalsTrendSeries(pts, timeframe).takeIf { it.size >= 2 }
            else recentTrendWindow(pts)
        }
        series?.let { DateTrendLine(points = it, color = FT.Emerald, modifier = Modifier.padding(top = 8.dp)) }
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
private fun SleepIndexCard(result: SleepIndexResult) {
    FTCard(title = "SLEEP INDEX") {
        StatLine("Confidence", result.confidence.label)
        if (result.score != null) {
            FTMetricValue(DisplayValue(primary = "%.0f".format(result.score), unit = "/ 100", secondary = result.band))
            result.components.forEach { (component, componentScore) ->
                StatLine(component.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }, "%.0f".format(componentScore.score))
            }
        } else {
            Text(
                "Not enough components yet -- needs at least 2 of duration/regularity/respiratory/stage-composition.",
                style = TextStyle(fontFamily = Inter, fontSize = 13.sp),
                color = FT.TextSecondary,
            )
        }
    }
}

@Composable
private fun CircadianAlignmentCard(result: CircadianAlignmentResult) {
    FTCard(title = "CIRCADIAN ALIGNMENT") {
        Text(
            "A behavioral regularity proxy, not a measured circadian phase.",
            style = TextStyle(fontFamily = Inter, fontSize = 12.sp),
            color = FT.TextMuted,
        )
        StatLine("Confidence", result.confidence.label)
        if (result.score != null) {
            FTMetricValue(DisplayValue(primary = "%.0f".format(result.score), unit = "/ 100", secondary = result.band))
            result.components.forEach { (component, componentScore) ->
                StatLine(component.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }, "%.0f".format(componentScore.score))
            }
        } else {
            Text(
                "Not enough sleep/meal/exercise timing history yet.",
                style = TextStyle(fontFamily = Inter, fontSize = 13.sp),
                color = FT.TextSecondary,
            )
        }
    }
}

@Composable
private fun SriCard(eval: SriEvaluation) {
    FTCard(title = "SLEEP REGULARITY (SRI)") {
        StatLine("Confidence", eval.confidence.label)
        if (eval.value != null) {
            FTMetricValue(DisplayValue(primary = "%.0f".format(eval.value), unit = "/ 100"))
        } else {
            Text(
                "Not enough consecutive nights yet (gaps over 2 nights reset the count).",
                style = TextStyle(fontFamily = Inter, fontSize = 13.sp),
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
        Row(modifier = Modifier.fillMaxWidth().height(10.dp)) {
            Box(Modifier.weight((avgDeep / avgTotal).toFloat().coerceAtLeast(0.001f)).fillMaxHeight().background(FT.Info))
            Box(Modifier.weight((avgRem / avgTotal).toFloat().coerceAtLeast(0.001f)).fillMaxHeight().background(FT.Emerald))
            Box(Modifier.weight((avgLight / avgTotal).toFloat().coerceAtLeast(0.001f)).fillMaxHeight().background(FT.TextMuted))
        }
        PhaseStatLine("Deep", avgDeep, avgTotal, FT.Info)
        PhaseStatLine("REM", avgRem, avgTotal, FT.Emerald)
        PhaseStatLine("Light", avgLight, avgTotal, FT.TextMuted)
        StatLine("Nights averaged", "${recent.size}")
    }
}

@Composable
private fun PhaseStatLine(label: String, minutes: Double, totalMinutes: Double, dotColor: androidx.compose.ui.graphics.Color) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.width(8.dp).height(8.dp).background(dotColor))
            Text(label, style = TextStyle(fontFamily = Inter, fontSize = 14.5.sp), color = FT.TextSecondary)
        }
        Text(
            "${minutes.toInt()} min  ·  %.0f%%".format(minutes / totalMinutes * 100),
            style = TextStyle(fontFamily = RobotoMono, fontSize = 14.5.sp),
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
        StatLine("Confidence", result.confidence.label)
        if (result.score != null) {
            FTMetricValue(DisplayValue(primary = "%.0f".format(result.score), unit = "/ 100"))
            result.components.forEach { (component, componentScore) ->
                StatLine(component.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }, "%.0f".format(componentScore.score))
            }
            if (result.unavailableComponents.isNotEmpty()) {
                Text(
                    "Active calories and zone minutes stay unavailable -- too little of this account's data has them yet.",
                    style = TextStyle(fontFamily = Inter, fontSize = 12.sp),
                    color = FT.TextMuted,
                )
            }
        } else {
            Text(
                "Not enough step or session history yet.",
                style = TextStyle(fontFamily = Inter, fontSize = 13.sp),
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
        DateTrendLine(points = recent, color = FT.DomainHeart)
    }
}

@Composable
private fun RespiratoryCard(eval: RespiratoryAnomalyEvaluation) {
    FTCard(title = "RESPIRATORY RATE") {
        StatLine("Confidence", eval.confidence.label)
        eval.baseline?.let { StatLine("14-night baseline", "%.1f breaths/min".format(it)) }
        Text(
            if (eval.flagged) {
                "Physiological anomaly flagged — 2 consecutive nights outside your baseline ±2 SD. Not a diagnosis."
            } else {
                "No anomaly flagged."
            },
            style = TextStyle(fontFamily = Inter, fontSize = 13.sp),
            color = if (eval.flagged) FT.Critical else FT.TextSecondary,
        )
    }
}

@Composable
private fun SubjectiveCard(title: String, points: List<Pair<LocalDate, Double>>, modifier: Modifier = Modifier) {
    val eval = evaluateSubjective(points)
    FTCard(title = title, modifier = modifier) {
        FTStatePill(eval.state.toMetricState())
        StatLine("Confidence", eval.confidence.label)
        eval.median7d?.let { StatLine("7-day median", "%.1f".format(it)) }
        eval.medianBaseline30d?.let { StatLine("30-day baseline", "%.1f".format(it)) }
        eval.iqr7d?.let { StatLine("7-day IQR", "%.1f".format(it)) }
        eval.trendDirection?.let { StatLine("14-day trend", if (it > 0) "↑ rising (p<0.05)" else "↓ falling (p<0.05)") }
        recentTrendWindow(points)?.let { recent ->
            DateTrendLine(points = recent, color = FT.Info, modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
// DAV-108: label is unweighted (always a short fixed phrase) so it never
// shrinks; value takes the rest of the row via weight(1f) so a long value
// wraps within its own bounded width and stays right-aligned instead of
// colliding with the label.
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
