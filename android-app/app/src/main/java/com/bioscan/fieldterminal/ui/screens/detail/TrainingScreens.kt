package com.bioscan.fieldterminal.ui.screens.detail

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.bioscan.fieldterminal.data.AnalysisRepository
import com.bioscan.fieldterminal.data.RunningHistory
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.buildTrainingSessionLoads
import com.bioscan.fieldterminal.data.model.FitnessFatigueRow
import com.bioscan.fieldterminal.data.model.OstrcAnalysisRow
import com.bioscan.fieldterminal.data.model.TrainingLoadSessionRow
import com.bioscan.fieldterminal.data.model.WearableAnalysisRow
import com.bioscan.fieldterminal.data.model.WellbeingAnalysisRow
import com.bioscan.fieldterminal.data.restingSeries
import com.bioscan.fieldterminal.domain.LoadSpans
import com.bioscan.fieldterminal.domain.evaluateRestCadence
import com.bioscan.fieldterminal.domain.evaluateSubjective
import com.bioscan.fieldterminal.domain.evaluateTrainingLoad
import com.bioscan.fieldterminal.domain.levels.formatPace
import com.bioscan.fieldterminal.domain.trainingLoadSeries
import com.bioscan.fieldterminal.domain.weeklyRestCadenceHistory
import com.bioscan.fieldterminal.ui.components.parallel
import com.bioscan.fieldterminal.ui.components.rememberLoadGuard
import com.bioscan.fieldterminal.data.softLoad
import com.bioscan.fieldterminal.ui.screens.health.ConcernsTab
import com.bioscan.fieldterminal.ui.screens.status.ActiveTravelCard
import com.bioscan.fieldterminal.ui.screens.status.IntensityCard
import com.bioscan.fieldterminal.ui.screens.today.TsbGauge
import com.bioscan.fieldterminal.ui.screens.today.TsbLine
import com.bioscan.fieldterminal.ui.screens.user.UserDataCache
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.LocalDate

private const val TSB_INFO =
    "Form (training stress balance): yesterday's fitness minus yesterday's fatigue. Negative means more recent load than " +
        "you have built up (loaded); around zero balanced; positive rested. The green band (−25 to +5) is the productive zone " +
        "LOAD scores against. The dots on the gauge are the last seven days."

private const val CTL_ATL_INFO =
    "Fitness (CTL) is a slow average of daily training load, fatigue (ATL) a fast one. A session's load is its duration × " +
        "how hard it felt (RPE), or a heart-rate estimate when no RPE was logged. Read your own trend, not the absolute number."

private const val WEEKS_INFO =
    "Training load per week, deload weeks (clearly lighter than the weeks around them) in their own colour. Tap a week to read it."

@Composable
fun LoadScreen(onBack: () -> Unit, onOpenSessions: () -> Unit) {
    var sessions by remember { mutableStateOf<List<TrainingLoadSessionRow>?>(null) }
    var wearable by remember { mutableStateOf<List<WearableAnalysisRow>?>(null) }
    var fitnessFatigue by remember { mutableStateOf<FitnessFatigueRow?>(null) }
    var spans by remember { mutableStateOf(LoadSpans.STANDARD) }
    val guard = rememberLoadGuard()
    LaunchedEffect(guard.attempt) {
        guard.run {
            val repo = AnalysisRepository(SupabaseClientProvider.client)
            parallel(
                { sessions = repo.loadExerciseSessionsForTrainingLoad() },
                { wearable = repo.loadWearableDaily() },
                { fitnessFatigue = repo.loadFitnessFatigue(); spans = repo.loadLoadSpans() },
            )
        }
    }
    val running by produceState<RunningHistory?>(null, guard.attempt) { value = softLoad { UserDataCache.running() } ?: value }
    DetailScreen("TRAINING LOAD", onBack, eyebrow = "LOAD", domain = FT.Domain.Load) {
        val s = sessions
        val w = wearable
        if (s == null || w == null) {
            Loading(guard)
            return@DetailScreen
        }
        val today = LocalDate.now()
        val days = LocalRangeDays.current
        val loads = remember(s, w) { buildTrainingSessionLoads(s, restingSeries(w)) }
        val eval = remember(loads, spans) { evaluateTrainingLoad(loads, spans = spans) }
        val points = remember(loads, spans) { trainingLoadSeries(loads, spans = spans) }
        val tsb = remember(points) { points.associate { it.date to it.tsb } }

        Section("FORM (TSB)", info = TSB_INFO, trailing = eval.tsb?.let { "%+.0f".format(it) }) {
            if (eval.tsb == null) EmptyLine("Builds after six weeks of logged training.")
            else {
                eval.tsbBand?.let { Text(it.uppercase(), style = FTType.LabelCaps, color = FT.TextSecondary) }
                TsbGauge(tsb, today)
                TsbLine(tsb, today, days.coerceAtMost(120))
            }
        }

        Section("FITNESS AND FATIGUE", info = CTL_ATL_INFO + (fitnessFatigue?.payload?.summary?.takeIf { it.isNotBlank() }?.let { "\n\n$it" } ?: "")) {
            StatTileGrid(
                listOf(
                    Stat("FITNESS · CTL", eval.ctl?.let { "%.0f".format(it) } ?: "—", "${spans.ctlDays.toInt()}-day average",
                        HistorySpec("Fitness (CTL)", points.associate { it.date to it.ctl }, "", Fmt.int, FT.Metric.Ctl, info = CTL_ATL_INFO)),
                    Stat("FATIGUE · ATL", eval.atl?.let { "%.0f".format(it) } ?: "—", "${spans.atlDays.toInt()}-day average",
                        HistorySpec("Fatigue (ATL)", points.associate { it.date to it.atl }, "", Fmt.int, FT.Metric.Atl, info = CTL_ATL_INFO)),
                ),
                today,
            )
            MultiLineChart(
                listOf(
                    ChartLine("Fitness", points.associate { it.date to it.ctl }, FT.Metric.Ctl),
                    ChartLine("Fatigue", points.associate { it.date to it.atl }, FT.Metric.Atl, dashed = true),
                ),
                today, days, Fmt.int,
            )
            if (spans.personal) Text("Spans fitted to you: they forecast your HRV better than the standard 42 and 7 days.", style = FTType.Caption, color = FT.TextMuted)
        }

        Section("WEEKS", info = WEEKS_INFO) {
            val weeks = remember(loads, days) { weeklyRestCadenceHistory(loads, weeks = (days / 7).coerceIn(8, 52)) }
            StackedBars(
                weeks.sortedByDescending { it.weekIndex }.map { wk -> shortDate(wk.weekEndDate.minusDays(6)) to if (wk.isDeload) listOf(0.0, wk.load) else listOf(wk.load, 0.0) },
                listOf(FT.Category.Activity.c500, FT.Emerald), listOf("Training", "Deload"), Fmt.int, "AU",
            )
        }

        IntensityCard()

        val r = running
        if (r != null && (r.vo2MaxSeries.isNotEmpty() || r.ltPaceSeries.isNotEmpty())) {
            Section("RUNNING FITNESS", info = "Slow-moving wearable estimates, not lab tests: VO2max from your watch via Health Connect, lactate threshold from Zepp (recomputed on qualifying runs).") {
                if (r.vo2MaxSeries.isNotEmpty()) MetricRow("VO2max", r.vo2MaxSeries.toMap(), "ml/kg/min", Fmt.one, minSd = 0.5, betterHigher = true, color = FT.Category.Activity.c500,
                    info = "Your watch's estimate of maximal oxygen uptake. It moves slowly; read months, not days.")
                if (r.ltPaceSeries.isNotEmpty()) MetricRow("Threshold pace", r.ltPaceSeries.toMap(), "/km", ::formatPace, minSd = 0.05, betterHigher = false, color = FT.Category.Activity.c500,
                    info = "Lactate-threshold pace from Zepp: about the fastest pace you can hold for an hour. Lower is faster.")
            }
        }

        ActiveTravelCard()

        LinkRow("All sessions", "Every recorded workout, newest first", onOpenSessions)
    }
}

private const val OSTRC_INFO =
    "OSTRC-H2 weekly check-in for one body area, 0-100 (higher = the problem affected training more). No injury-risk score " +
        "is made from it: single-factor screening does not predict injury. You do the synthesis."

private const val CONTEXT_INFO =
    "Load beside your injuries, never combined into one score: form, days in a row without rest, your 7-day soreness median, " +
        "and the last eight weeks of load with deload weeks marked."

@Composable
fun InjuryScreen(onBack: () -> Unit, onOpenConcern: (String) -> Unit) {
    var sessions by remember { mutableStateOf<List<TrainingLoadSessionRow>?>(null) }
    var wearable by remember { mutableStateOf<List<WearableAnalysisRow>?>(null) }
    var wellbeing by remember { mutableStateOf<List<WellbeingAnalysisRow>?>(null) }
    var ostrc by remember { mutableStateOf<List<OstrcAnalysisRow>?>(null) }
    var spans by remember { mutableStateOf(LoadSpans.STANDARD) }
    val guard = rememberLoadGuard()
    LaunchedEffect(guard.attempt) {
        guard.run {
            val repo = AnalysisRepository(SupabaseClientProvider.client)
            parallel(
                { sessions = repo.loadExerciseSessionsForTrainingLoad() },
                { wearable = repo.loadWearableDaily() },
                { wellbeing = repo.loadWellbeingDaily() },
                { ostrc = repo.loadOstrcCheckins() },
                { spans = repo.loadLoadSpans() },
            )
        }
    }
    DetailScreen("INJURY", onBack, eyebrow = "LOAD", domain = FT.Domain.Load) {
        Section("INJURIES AND PAIN", info = "Body concerns you are tracking (injuries and recurring pain). Tap one for its own page.") {
            ConcernsTab(onOpenConcern = onOpenConcern, anatomicalOnly = true)
        }
        val s = sessions
        val w = wearable
        val wb = wellbeing
        val os = ostrc
        if (s == null || w == null || wb == null || os == null) {
            Loading(guard)
            return@DetailScreen
        }
        val today = LocalDate.now()
        val loads = remember(s, w) { buildTrainingSessionLoads(s, restingSeries(w)) }
        val eval = remember(loads, spans) { evaluateTrainingLoad(loads, spans = spans) }
        val rest = remember(loads, eval) { evaluateRestCadence(loads, eval.tsb, eval.confidence.met) }
        val soreness = remember(wb) { evaluateSubjective(wb.mapNotNull { r -> r.soreness?.let { LocalDate.parse(r.date) to it.toDouble() } }) }

        val byArea = os.mapNotNull { r -> r.severityScore?.let { r.bodyArea to (LocalDate.parse(r.checkDate) to it.toDouble()) } }.groupBy({ it.first }, { it.second })
        if (byArea.isEmpty()) {
            Section("OSTRC CHECK-INS", info = OSTRC_INFO) { EmptyLine("No weekly injury check-ins yet. Log one from + LOG › INJURY CHECK-IN.") }
        }
        for ((area, entries) in byArea) {
            val latest = entries.maxBy { it.first }
            Section("OSTRC · ${area.uppercase()}", info = OSTRC_INFO, trailing = shortDate(latest.first)) {
                Text("${latest.second.toInt()} / 100", style = FTType.MetricMedium, color = if (latest.second >= 25) FT.Warning else FT.TextPrimary)
                TrendChart(entries.toMap(), today, LocalRangeDays.current, null, Fmt.int, FT.Metric.Soreness)
            }
        }

        Section("LOAD BESIDE IT", info = CONTEXT_INFO) {
            StatTiles(
                listOf(
                    Triple("FORM (TSB)", eval.tsb?.let { "%+.0f".format(it) } ?: "—", eval.tsbBand),
                    Triple("DAYS WITHOUT REST", "${rest.consecutiveDaysWithoutRest}", null),
                    Triple("SORENESS · 7-DAY MEDIAN", soreness.median7d?.let { "%.1f".format(it) } ?: "—", "out of 10"),
                ),
            )
            val weeks = remember(loads) { weeklyRestCadenceHistory(loads, weeks = 8) }
            StackedBars(
                weeks.sortedByDescending { it.weekIndex }.map { wk -> shortDate(wk.weekEndDate.minusDays(6)) to if (wk.isDeload) listOf(0.0, wk.load) else listOf(wk.load, 0.0) },
                listOf(FT.Category.Activity.c500, FT.Emerald), listOf("Training", "Deload"), Fmt.int, "AU", height = 70,
            )
        }
    }
}
