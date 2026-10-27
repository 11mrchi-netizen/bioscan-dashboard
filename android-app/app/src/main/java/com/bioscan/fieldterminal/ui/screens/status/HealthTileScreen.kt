package com.bioscan.fieldterminal.ui.screens.status

import com.bioscan.fieldterminal.domain.LoadSpans
import com.bioscan.fieldterminal.domain.DayRange
import com.bioscan.fieldterminal.domain.compositeInputs
import com.bioscan.fieldterminal.domain.evaluations
import com.bioscan.fieldterminal.domain.recoveryFrom
import com.bioscan.fieldterminal.domain.sleepIndex
import com.bioscan.fieldterminal.domain.demotes
import com.bioscan.fieldterminal.domain.ScorecardResult
import com.bioscan.fieldterminal.domain.SLEEP_HOURS_RANGE
import com.bioscan.fieldterminal.domain.baselineStateWord
import com.bioscan.fieldterminal.domain.recoveryStateWord
import com.bioscan.fieldterminal.domain.toExpSpace
import com.bioscan.fieldterminal.domain.trendStateWord
import com.bioscan.fieldterminal.util.Perf
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import com.bioscan.fieldterminal.data.model.LogSexualActivityRow
import com.bioscan.fieldterminal.data.model.SleepAnalysisRow
import com.bioscan.fieldterminal.data.model.TrainingLoadSessionRow
import com.bioscan.fieldterminal.data.model.MealRow
import com.bioscan.fieldterminal.data.NutritionRepository
import com.bioscan.fieldterminal.data.model.WearableAnalysisRow
import com.bioscan.fieldterminal.data.model.ExpectedBandRow
import com.bioscan.fieldterminal.domain.expectedStateWord
import com.bioscan.fieldterminal.domain.leadReadout
import com.bioscan.fieldterminal.domain.morningReadout
import com.bioscan.fieldterminal.data.model.WellbeingAnalysisRow
import com.bioscan.fieldterminal.domain.DisplayValue
import com.bioscan.fieldterminal.domain.EvalState
import com.bioscan.fieldterminal.domain.MetricState
import com.bioscan.fieldterminal.domain.PersonalRange
import com.bioscan.fieldterminal.domain.RangeKind
import com.bioscan.fieldterminal.domain.RespiratoryAnomalyEvaluation
import com.bioscan.fieldterminal.data.BenchmarkArtifactRepository
import com.bioscan.fieldterminal.domain.analysis.ComparisonResult
import com.bioscan.fieldterminal.domain.analysis.Directionality
import com.bioscan.fieldterminal.domain.analysis.MIN_CORRELATION_PAIRS
import com.bioscan.fieldterminal.domain.analysis.pearsonCorrelation
import com.bioscan.fieldterminal.domain.comparison.BenchmarkArtifact
import com.bioscan.fieldterminal.domain.comparison.METRIC_DIRECTIONALITY
import com.bioscan.fieldterminal.domain.comparison.PopulationContext
import com.bioscan.fieldterminal.domain.comparison.comparePersonal
import com.bioscan.fieldterminal.domain.comparison.comparePopulation
import com.bioscan.fieldterminal.domain.SeriesBreakStarts
import com.bioscan.fieldterminal.domain.SleepNight
import com.bioscan.fieldterminal.domain.sriWord
import com.bioscan.fieldterminal.domain.SriEvaluation
import com.bioscan.fieldterminal.domain.SleepIndexResult
import com.bioscan.fieldterminal.domain.MovementIndexResult
import com.bioscan.fieldterminal.domain.computeMovementIndex
import com.bioscan.fieldterminal.domain.CircadianAlignmentResult
import com.bioscan.fieldterminal.domain.computeCircadianAlignment
import com.bioscan.fieldterminal.data.ZeppStressRepository
import com.bioscan.fieldterminal.domain.stress.StressDay
import com.bioscan.fieldterminal.domain.stress.StressRhythmResult
import com.bioscan.fieldterminal.domain.stress.analyzeStressDay
import com.bioscan.fieldterminal.domain.TrainingLoadEvaluation
import com.bioscan.fieldterminal.domain.DynamicRecoveryResult
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
import com.bioscan.fieldterminal.ui.nav.HealthTab
import com.bioscan.fieldterminal.domain.minuteHrSummary
import com.bioscan.fieldterminal.ui.components.ComparisonStrip
import com.bioscan.fieldterminal.ui.components.DateTrendLine
import com.bioscan.fieldterminal.ui.components.personalScale
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTCardEmphasis
import com.bioscan.fieldterminal.ui.components.FTDataChip
import com.bioscan.fieldterminal.ui.components.FTGlanceStrip
import com.bioscan.fieldterminal.ui.components.GlanceAnchors
import com.bioscan.fieldterminal.ui.components.GlanceChip
import com.bioscan.fieldterminal.ui.components.FTMetricRow
import com.bioscan.fieldterminal.ui.components.FTMetricValue
import com.bioscan.fieldterminal.ui.components.FTRangeIndicator
import com.bioscan.fieldterminal.ui.components.FTScoreRow
import com.bioscan.fieldterminal.ui.components.FTSegment
import com.bioscan.fieldterminal.ui.components.FTSegmentBar
import com.bioscan.fieldterminal.ui.components.FTStatePill
import com.bioscan.fieldterminal.ui.components.MinuteHeartRateChart
import com.bioscan.fieldterminal.ui.components.LoadErrorCard
import com.bioscan.fieldterminal.ui.components.PinnedPage
import com.bioscan.fieldterminal.ui.components.SegmentedToggle
import com.bioscan.fieldterminal.ui.components.SubTabRow
import com.bioscan.fieldterminal.ui.components.glanceAnchor
import com.bioscan.fieldterminal.ui.components.parallel
import com.bioscan.fieldterminal.ui.components.rememberGlanceAnchors
import com.bioscan.fieldterminal.ui.components.rememberLoadGuard
import com.bioscan.fieldterminal.ui.components.stateTreatment
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
// DAV-216 (24/9 fixes): Injury moved out to TrainingTab.
// App restructure: the Health tile is CARDIO · SLEEP · BODY · WELLBEING · SEXUAL HEALTH · BLOODWORK. THIS MORNING and
// TODAY'S RECOVERY live on Today only; BODY came from Fuel and BLOODWORK from Labs (each loads its own data), and the
// sexual-health correlations moved to Lab › N=1.
@Composable
fun HealthTileScreen(onBack: () -> Unit, requestedTab: HealthTab? = null, onRequestedTabConsumed: () -> Unit = {}, onOpenConcern: (String) -> Unit = {}) {
    var tab by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(requestedTab ?: HealthTab.Cardio) }
    LaunchedEffect(requestedTab) {
        if (requestedTab != null) {
            tab = requestedTab
            onRequestedTabConsumed()
        }
    }

    var wearable by remember { mutableStateOf<List<WearableAnalysisRow>?>(null) }
    // Each day's lowest and highest heart rate, for the HEART RATE range card (the minute curve lives on Today).
    var hrRanges by remember { mutableStateOf<List<Pair<LocalDate, DayRange>>>(emptyList()) }
    var sleep by remember { mutableStateOf<List<SleepAnalysisRow>?>(null) }
    var wellbeing by remember { mutableStateOf<List<WellbeingAnalysisRow>?>(null) }
    var sexualActivity by remember { mutableStateOf<List<LogSexualActivityRow>?>(null) }
    var stepsBenchmark by remember { mutableStateOf<List<BenchmarkArtifact>>(emptyList()) }
    var exerciseSessions by remember { mutableStateOf<List<TrainingLoadSessionRow>>(emptyList()) }
    var recentMealsForTiming by remember { mutableStateOf<List<MealRow>>(emptyList()) }
    var stressDays by remember { mutableStateOf<List<StressDay>>(emptyList()) }
    // Analysis Layer 3 / R1: start of each metric's current regime (last confirmed series break); NONE until loaded.
    var breakStarts by remember { mutableStateOf(SeriesBreakStarts.NONE) }
    // Analysis review phase G: the spans training load is drawn with (standard unless the personal pair was adopted).
    var loadSpans by remember { mutableStateOf(LoadSpans.STANDARD) }

    val guard = rememberLoadGuard()
    LaunchedEffect(guard.attempt) {
        // Perf pass: the nine loads are independent, so they run together (was one after another).
        Perf.timed("HeartTile.load") {
            guard.run {
                val repo = AnalysisRepository(SupabaseClientProvider.client)
                parallel(
                    { wearable = repo.loadWearableDaily() },
                    { hrRanges = repo.loadHeartRateRanges() },
                    { sleep = repo.loadSleepDaily() },
                    { wellbeing = repo.loadWellbeingDaily() },
                    // Arousal folded into wellbeing_daily -- see `wellbeing` above.
                    { sexualActivity = repo.loadSexualActivityDaily() },
                    // DAV-196: first real population artifact (Tudor-Locke & Bassett's steps/day categories).
                    { stepsBenchmark = BenchmarkArtifactRepository(SupabaseClientProvider.client).loadArtifacts("steps") },
                    // 09.3 Movement Index: the same session query the Load tab's CTL/ATL/TSB uses.
                    { exerciseSessions = repo.loadExerciseSessionsForTrainingLoad() },
                    // 09.6 Circadian Alignment: a 200-meal lookback, not the 10-row default.
                    { recentMealsForTiming = NutritionRepository(SupabaseClientProvider.client).loadRecentMeals(limit = 200) },
                    // 05.1 Stress Rhythm: decoded client-side from zepp_raw_extracts (see ZeppStressRepository).
                    { stressDays = ZeppStressRepository(SupabaseClientProvider.client).loadStressDays() },
                    { breakStarts = repo.loadSeriesBreakStarts() },
                    { loadSpans = repo.loadLoadSpans() },
                )
            }
        }
    }

    // Contract A4: title, context line and sub-tab rail stay pinned; only the tab's content scrolls, from the top on a tab change.
    val scroll = rememberScrollState()
    LaunchedEffect(tab) { scroll.scrollTo(0) }
    PinnedPage(
        onBack = onBack,
        title = "HEALTH",
        accent = FT.DomainHeart,
        rail = { SubTabRow(items = HealthTab.entries, selected = tab, label = { it.label }, onSelect = { tab = it }) },
        headerAction = { com.bioscan.fieldterminal.ui.components.TimeRangeDropdown() },
        scrollState = scroll,
    ) {
        // These two load their own data, so they show without waiting for the shared load below.
        when (tab) {
            HealthTab.Body -> { BodyTabContent(); return@PinnedPage }
            HealthTab.Concerns -> { com.bioscan.fieldterminal.ui.screens.health.ConcernsTab(onOpenConcern = onOpenConcern); return@PinnedPage }
            HealthTab.Bloodwork -> { LabsScreen(); return@PinnedPage }
            else -> Unit
        }

        val w = wearable
        val s = sleep
        val wb = wellbeing
        val sa = sexualActivity
        if (w == null || s == null || wb == null || sa == null) {
            if (guard.error != null) {
                LoadErrorCard(guard)
            } else {
                Box(Modifier.fillMaxWidth().padding(vertical = 60.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = FT.DomainHeart)
                }
            }
            return@PinnedPage
        }

        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            when (tab) {
                HealthTab.Cardio -> CardioPage(w, s, hrRanges, stepsBenchmark, breakStarts)
                HealthTab.Sleep -> SleepPage(s, w, wb, exerciseSessions, recentMealsForTiming, breakStarts, loadSpans)
                HealthTab.Wellbeing -> WellbeingPage(wb, stressDays)
                HealthTab.SexualHealth -> SexualHealthPage(wb, sa)
                HealthTab.Body, HealthTab.Bloodwork, HealthTab.Concerns -> Unit
            }
        }
    }
}

// Sexual Health tab -- self-quantification only, never a proxy for anything
// clinical (explicit user directive). All five cards read sa/wb, already
// loaded for Wellbeing above; no new repository call.

@Composable
internal fun SexualHealthOccurrencesCard(sa: List<LogSexualActivityRow>) {
    val intercourseDays = sa.count { it.activityType == "intercourse" }
    val masturbationDays = sa.count { it.activityType == "masturbation" }
    val totalOrgasms = sa.sumOf { row -> row.instances.count { it.orgasm } }
    val activeDays = sa.map { it.date }.distinct().size
    val totalDays = sa.mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }
        .let { dates -> if (dates.isEmpty()) 0 else ChronoUnit.DAYS.between(dates.min(), dates.max()).toInt() + 1 }
    FTCard(title = "OCCURRENCES", info = "Raw counts over the logged window. Self-quantification only -- no clinical significance implied.") {
        FTMetricRow("Intercourse days", "$intercourseDays")
        FTMetricRow("Masturbation days", "$masturbationDays")
        FTMetricRow("Total orgasms", "$totalOrgasms")
        if (totalDays > 0) FTMetricRow("Active days", "$activeDays / $totalDays")
    }
}

@Composable
internal fun SexualHealthFrequencyCard(sa: List<LogSexualActivityRow>) {
    val dates = sa.mapNotNull { row -> runCatching { LocalDate.parse(row.date) }.getOrNull()?.let { it to row.activityType } }
    if (dates.isEmpty()) {
        FTCard(title = "FREQUENCY") { Text("No entries logged yet.", style = FTType.BodySmall, color = FT.TextSecondary) }
        return
    }
    val today = LocalDate.now()
    val weeks = (0 until 8).map { w -> today.minusDays((w * 7 + 6).toLong()) to today.minusDays((w * 7).toLong()) }.reversed()
    val weeklyCounts = weeks.map { (start, end) ->
        val inWeek = dates.filter { (d, _) -> !d.isBefore(start) && !d.isAfter(end) }
        inWeek.count { it.second == "intercourse" } to inWeek.count { it.second == "masturbation" }
    }
    val maxCount = weeklyCounts.maxOfOrNull { (i, m) -> i + m }?.coerceAtLeast(1) ?: 1
    val sortedDates = dates.map { it.first }.distinct().sorted()
    val gaps = sortedDates.zipWithNext { a, b -> ChronoUnit.DAYS.between(a, b) }
    val avgGap = gaps.takeIf { it.isNotEmpty() }?.average()

    FTCard(title = "FREQUENCY", info = "Weekly counts, last 8 weeks. Self-quantification only.") {
        Row(modifier = Modifier.fillMaxWidth().height(80.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            weeklyCounts.forEach { (i, m) ->
                Column(modifier = Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.Bottom) {
                    val iFrac = i.toFloat() / maxCount
                    val mFrac = m.toFloat() / maxCount
                    if (i > 0) Box(Modifier.fillMaxWidth().fillMaxHeight(iFrac).background(FT.Category.Arousal.c500))
                    if (m > 0) Box(Modifier.fillMaxWidth().fillMaxHeight(mFrac).background(FT.Category.Wellbeing.c500))
                    if (i == 0 && m == 0) Box(Modifier.fillMaxWidth().height(2.dp).background(FT.GlassFill))
                }
            }
        }
        avgGap?.let { FTMetricRow("Average gap between sessions", "%.1f days".format(it)) }
    }
}

@Composable
internal fun SexualHealthHeatmapCard(sa: List<LogSexualActivityRow>) {
    val byDate = sa.mapNotNull { row -> runCatching { LocalDate.parse(row.date) }.getOrNull()?.let { it to row.activityType } }
        .groupBy({ it.first }, { it.second })
        .mapValues { (_, types) ->
            com.bioscan.fieldterminal.ui.components.DayActivity(
                intercourseCount = types.count { it == "intercourse" },
                masturbationCount = types.count { it == "masturbation" },
            )
        }
    FTCard(title = "ACTIVITY HEATMAP", info = "Last 12 weeks. Red = intercourse, blue = masturbation, darker = more that day.") {
        com.bioscan.fieldterminal.ui.components.ActivityHeatmap(byDate)
    }
}

@Composable
internal fun SexualHealthIntensityCard(sa: List<LogSexualActivityRow>) {
    val intensities = sa.flatMap { it.instances }.mapNotNull { it.orgasmIntensity }
    val loadSizes = sa.flatMap { it.instances }.mapNotNull { it.loadSize }
    if (intensities.isNotEmpty() || loadSizes.isNotEmpty()) {
        FTCard(title = "INTENSITY & LOAD DISTRIBUTION", info = "Self-reported only -- no validated biomarker link to either field.") {
            if (intensities.isNotEmpty()) {
                Text("ORGASM INTENSITY", style = FTType.Label, color = FT.TextMuted)
                val buckets = listOf("0-2" to (0..2), "3-4" to (3..4), "5-6" to (5..6), "7-8" to (7..8), "9-10" to (9..10))
                FTSegmentBar(
                    segments = buckets.mapIndexed { i, (label, range) ->
                        FTSegment(label, intensities.count { it in range }.toDouble(), shadeForIndex(FT.Category.Arousal, i, buckets.size))
                    },
                    modifier = Modifier.padding(top = 6.dp, bottom = 12.dp),
                )
            }
            if (loadSizes.isNotEmpty()) {
                Text("LOAD SIZE", style = FTType.Label, color = FT.TextMuted)
                FTSegmentBar(
                    segments = (1..5).mapIndexed { i, size ->
                        FTSegment("$size", loadSizes.count { it == size }.toDouble(), shadeForIndex(FT.Category.Wellbeing, i, 5))
                    },
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

private fun shadeForIndex(category: com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens.CategoryColor, index: Int, total: Int): androidx.compose.ui.graphics.Color {
    val step = (index.toFloat() / (total - 1).coerceAtLeast(1))
    return when {
        step < 0.25f -> category.c300
        step < 0.5f -> category.c500
        step < 0.75f -> category.c700
        else -> category.c900
    }
}

// App restructure: shown in Lab › N=1 with the other "what goes with what" questions.
@Composable
internal fun SexualHealthCorrelationsCard(wb: List<WellbeingAnalysisRow>, sa: List<LogSexualActivityRow>, w: List<WearableAnalysisRow>, s: List<SleepAnalysisRow>) {
    val wbByDate = wb.mapNotNull { row -> runCatching { LocalDate.parse(row.date) }.getOrNull()?.let { it to row } }.toMap()
    val sByDate = s.mapNotNull { row -> runCatching { LocalDate.parse(row.date) }.getOrNull()?.let { it to row } }.toMap()
    val wByDate = w.mapNotNull { row -> runCatching { LocalDate.parse(row.date) }.getOrNull()?.let { it to row } }.toMap()

    fun pairs(x: (LocalDate) -> Double?, y: (LocalDate) -> Double?): List<Pair<Double, Double>> {
        val dates = wbByDate.keys + sByDate.keys + wByDate.keys
        return dates.mapNotNull { d -> val xv = x(d); val yv = y(d); if (xv != null && yv != null) xv to yv else null }
    }

    val erectionVsSleepHours = pearsonCorrelation(pairs({ wbByDate[it]?.morningErectionQuality?.toDouble() }, { sByDate[it]?.hours }))
    val erectionVsDeepSleep = pearsonCorrelation(pairs({ wbByDate[it]?.morningErectionQuality?.toDouble() }, { sByDate[it]?.deepMin }))
    val arousalVsHrv = pearsonCorrelation(pairs({ wbByDate[it]?.arousalLevel?.toDouble() }, { wByDate[it]?.hrv }))
    val arousalVsRhr = pearsonCorrelation(pairs({ wbByDate[it]?.arousalLevel?.toDouble() }, { wByDate[it]?.rhr }))

    val gapByDate: Map<LocalDate, Double> = run {
        val sorted = sa.mapNotNull { row -> runCatching { LocalDate.parse(row.date) }.getOrNull() }.distinct().sorted()
        sorted.zipWithNext { a, b -> b to ChronoUnit.DAYS.between(a, b).toDouble() }.toMap()
    }
    val intensityByDate: Map<LocalDate, Double> = sa.mapNotNull { row ->
        runCatching { LocalDate.parse(row.date) }.getOrNull()?.let { date ->
            row.instances.mapNotNull { it.orgasmIntensity }.maxOrNull()?.let { date to it.toDouble() }
        }
    }.toMap()
    val intensityVsGap = pearsonCorrelation(gapByDate.keys.mapNotNull { d -> intensityByDate[d]?.let { it to gapByDate.getValue(d) } })

    val rows = listOfNotNull(
        erectionVsSleepHours?.let { Triple("Morning erection quality × sleep duration", it, "research-supported pairing") },
        erectionVsDeepSleep?.let { Triple("Morning erection quality × deep sleep", it, "research-supported pairing") },
        arousalVsHrv?.let { Triple("Arousal × HRV", it, "observed in your data only") },
        arousalVsRhr?.let { Triple("Arousal × resting heart rate", it, "observed in your data only") },
        intensityVsGap?.let { Triple("Orgasm intensity × days since last activity", it, "observed in your data only") },
    )

    FTCard(title = "CORRELATIONS", info = "Pearson r with its 95% interval, minimum $MIN_CORRELATION_PAIRS paired days. A strength word is shown only when the interval excludes zero. Only morning erection quality × sleep has real supporting research -- everything else is observed in your own data, not a validated link, and a correlation never shows cause.") {
        if (rows.isEmpty()) {
            Text("Not enough paired history yet (need $MIN_CORRELATION_PAIRS+ overlapping days per pair).", style = FTType.BodySmall, color = FT.TextSecondary)
        } else {
            rows.forEach { (label, result, caption) ->
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(label, style = FTType.BodySmall, color = FT.TextPrimary, modifier = Modifier.weight(1f))
                        Text("r = %.2f".format(result.r), style = FTType.Value, color = FT.TextSecondary)
                    }
                    Text(
                        "${result.strength} · 95% interval ${"%.2f".format(result.lower)} to ${"%.2f".format(result.upper)} · n=${result.n} · $caption",
                        style = FTType.Caption,
                        color = FT.TextMuted,
                    )
                }
            }
        }
    }
}

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
    modifier: Modifier = Modifier,
    points: List<Pair<LocalDate, Double>>? = null,
    comparison: ComparisonResult? = null,
    // DAV-285: HRV/RHR pass a real VitalsTimeframe selection (7D shows raw
    // daily points, longer windows a weekly-mean rollup -- vitalsTrendSeries).
    // Every other EvalCard caller (Sleep Duration, etc.) leaves this null and
    // keeps the original fixed 30-day window unchanged.
    timeframe: VitalsTimeframe? = null,
    methodology: String? = null,
    emphasis: FTCardEmphasis = FTCardEmphasis.Supporting,
    // A known healthy range for an OPTIMAL_RANGE metric (sleep duration), so a shift out of it reads as attention.
    range: ClosedFloatingPointRange<Double>? = null,
) {
    val word = baselineStateWord(eval.state, directionality, range, eval.baseline7d)
    FTCard(dataQuality = eval.confidence, title = title, modifier = modifier, methodology = methodology, emphasis = emphasis) {
        FTStatePill(word)
        comparison?.let { ComparisonStrip(it) }
        // Analysis review A2: the band drawn is the band that decided the pill (SwcEvaluation.bandLow/bandHigh, already in
        // display units for HRV via toExpSpace), not mean x swcPct, which drew HRV's log-space band about 4x too narrow.
        val bandLow = eval.bandLow
        val bandHigh = eval.bandHigh
        if (eval.mean60d != null && bandLow != null && bandHigh != null) {
            FTRangeIndicator(
                PersonalRange(
                    kind = RangeKind.PersonalBaseline,
                    lower = bandLow,
                    upper = bandHigh,
                    baseline = eval.mean60d,
                    current = eval.baseline7d,
                    label = "60-DAY BASELINE, SHIFT BAND ($unit)",
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
            // Chart rules (analysis review phase C): the axis is fixed to this person's usual range for the metric, and
            // missing days break the line (a missing week, once the points are weekly means).
            val weekly = timeframe != null && timeframe != VitalsTimeframe.Week
            DateTrendLine(
                points = it,
                color = FT.DomainHeart,
                refLow = bandLow,
                refHigh = bandHigh,
                highlightColor = stateTreatment(word.state).color,
                modifier = Modifier.padding(top = 8.dp),
                unit = unit,
                scale = points?.let { all -> personalScale(all.takeLast(365).map { p -> p.second }) },
                maxGapDays = if (weekly) 7 else 1,
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
private const val DAILY_HR_INFO =
    "Your band's heart rate minute by minute for the newest day it has synced, from midnight to midnight. Minutes " +
        "without a reading (band off the wrist, or no measurement) are left out: the line breaks there and a dashed " +
        "stretch on the baseline marks them. Tap or drag the chart to read a minute."

private const val TREND_WINDOW_DAYS = 30L

private fun recentTrendWindow(points: List<Pair<LocalDate, Double>>): List<Pair<LocalDate, Double>>? {
    if (points.size < 2) return null
    val today = LocalDate.now()
    val recent = points.filter { ChronoUnit.DAYS.between(it.first, today) <= TREND_WINDOW_DAYS }
    return recent.takeIf { it.size >= 2 }
}

@Composable
internal fun DynamicRecoveryCard(
    result: DynamicRecoveryResult,
    modifier: Modifier = Modifier,
    emphasis: FTCardEmphasis = FTCardEmphasis.Hero,
    scorecard: ScorecardResult? = null,
) {
    // Contract A3: the lead card of Health › Recovery; on Today it follows THIS MORNING when the expected band exists.
    // Analysis review phase G: when its scorecard says it forecasts no better than one of its inputs, it drops its own
    // word (INPUTS ONLY) and shows the inputs.
    // Fix 4: the state and each contributor are tinted by direction (faint green up, faint red down, contract A9), and the
    // "?" says how the votes fell.
    FTCard(
        dataQuality = result.confidence, title = "TODAY'S RECOVERY", methodology = "dynamic_recovery", emphasis = emphasis, modifier = modifier,
        helpExtra = result.balance?.let { recoveryBalanceLine(it) },
    ) {
        if (scorecard.demotes()) FTStatePill(INPUTS_ONLY) else RecoveryStatePill(result.state)
        if (result.contributors.isEmpty()) {
            Text(
                "Not enough contributors yet -- needs at least 2 of sleep/HRV/RHR/training load/subjective state.",
                style = FTType.BodySmall,
                color = FT.TextSecondary,
            )
        } else {
            result.contributors.forEach { contributor ->
                val effect = when (contributor.direction) {
                    1 -> "SUPPORTS"
                    -1 -> "HOLDS BACK"
                    0 -> "NEUTRAL"
                    else -> "NO SIGNAL YET"
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(contributor.dimension.replace('_', ' ').replaceFirstChar { it.uppercase() }, style = FTType.Body, color = FT.TextSecondary, modifier = Modifier.weight(1f))
                    com.bioscan.fieldterminal.ui.components.FTMovementChip(contributor.direction, effect)
                }
            }
        }
        ScorecardLine(scorecard)
    }
}

// The recovery word as a movement: ABOVE in faint green, BELOW in faint red, AS EXPECTED and MIXED untinted.
@Composable
internal fun RecoveryStatePill(state: EvalState) {
    when (state) {
        EvalState.ShiftUp -> com.bioscan.fieldterminal.ui.components.FTMovementChip(1, "ABOVE EXPECTED")
        EvalState.ShiftDown -> com.bioscan.fieldterminal.ui.components.FTMovementChip(-1, "BELOW EXPECTED")
        EvalState.Stable -> com.bioscan.fieldterminal.ui.components.FTMovementChip(0, "AS EXPECTED")
        else -> FTStatePill(recoveryStateWord(state))
    }
}

private fun recoveryBalanceLine(b: com.bioscan.fieldterminal.domain.RecoveryBalance): String =
    "This morning: %.0f votes, %.0f%% of them up and %.0f%% down (net %+.2f; ABOVE or BELOW from ±0.25).".format(b.votes, b.up * 100, b.down * 100, b.net)

@Composable
internal fun CircadianAlignmentCard(result: CircadianAlignmentResult, modifier: Modifier = Modifier) {
    FTCard(dataQuality = result.confidence, title = "CIRCADIAN ALIGNMENT", info = "A behavioral regularity proxy, not a measured circadian phase.", modifier = modifier) {
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
internal fun PhysiologicalStressCard(result: StressRhythmResult, today: StressDay) {
    // "PHYSIOLOGICAL STRESS" not "STRESS" -- never visually conflated with
    // the subjective SubjectiveCard("STRESS") above it (DAV-246's own rule).
    FTCard(dataQuality = result.confidence, title = "PHYSIOLOGICAL STRESS") {
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

// Live check: HealthConnectDailySyncRepository.syncSleep() already computes
// real per-night deep/rem/light minutes from SleepSessionRecord.stages and
// syncs them to sleep_daily -- nothing in the app ever displayed them. Shows
// the last 14 real nights' average as a proportion bar rather than one more
// day-by-day trend line (this is a composition question -- "where does sleep
// time go" -- not a day-to-day trend one).
// Live check: wearable_daily.steps has 1,000+ real synced days, never read
// anywhere. No evaluate*() function exists for steps (no baseline/SWC
// concept defined for it, unlike HRV/RHR) -- a plain daily count and trend,
// not a full EvalCard, is the honest treatment.
