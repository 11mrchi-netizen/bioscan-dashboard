package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import com.bioscan.fieldterminal.data.BenchmarkArtifactRepository
import com.bioscan.fieldterminal.data.NutritionRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.ZeppStressRepository
import com.bioscan.fieldterminal.data.model.ExpectedBandRow
import com.bioscan.fieldterminal.data.model.LogSexualActivityRow
import com.bioscan.fieldterminal.data.model.MealRow
import com.bioscan.fieldterminal.data.model.SleepAnalysisRow
import com.bioscan.fieldterminal.data.model.TrainingLoadSessionRow
import com.bioscan.fieldterminal.data.model.WearableAnalysisRow
import com.bioscan.fieldterminal.data.model.WellbeingAnalysisRow
import com.bioscan.fieldterminal.domain.CircadianAlignmentResult
import com.bioscan.fieldterminal.domain.DayRange
import com.bioscan.fieldterminal.domain.DisplayValue
import com.bioscan.fieldterminal.domain.DynamicRecoveryResult
import com.bioscan.fieldterminal.domain.EvalState
import com.bioscan.fieldterminal.domain.LoadSpans
import com.bioscan.fieldterminal.domain.MetricState
import com.bioscan.fieldterminal.domain.MovementIndexResult
import com.bioscan.fieldterminal.domain.PersonalRange
import com.bioscan.fieldterminal.domain.RangeKind
import com.bioscan.fieldterminal.domain.RespiratoryAnomalyEvaluation
import com.bioscan.fieldterminal.domain.SLEEP_HOURS_RANGE
import com.bioscan.fieldterminal.domain.ScorecardResult
import com.bioscan.fieldterminal.domain.SeriesBreakStarts
import com.bioscan.fieldterminal.domain.SleepIndexResult
import com.bioscan.fieldterminal.domain.SleepNight
import com.bioscan.fieldterminal.domain.SriEvaluation
import com.bioscan.fieldterminal.domain.StateWord
import com.bioscan.fieldterminal.domain.SubjectiveEvaluation
import com.bioscan.fieldterminal.domain.SwcEvaluation
import com.bioscan.fieldterminal.domain.TrainingLoadEvaluation
import com.bioscan.fieldterminal.domain.VitalsTimeframe
import com.bioscan.fieldterminal.domain.analysis.ComparisonResult
import com.bioscan.fieldterminal.domain.analysis.Directionality
import com.bioscan.fieldterminal.domain.analysis.MIN_CORRELATION_PAIRS
import com.bioscan.fieldterminal.domain.analysis.pearsonCorrelation
import com.bioscan.fieldterminal.domain.baselineStateWord
import com.bioscan.fieldterminal.domain.comparison.BenchmarkArtifact
import com.bioscan.fieldterminal.domain.comparison.METRIC_DIRECTIONALITY
import com.bioscan.fieldterminal.domain.comparison.PopulationContext
import com.bioscan.fieldterminal.domain.comparison.comparePersonal
import com.bioscan.fieldterminal.domain.comparison.comparePopulation
import com.bioscan.fieldterminal.domain.compositeInputs
import com.bioscan.fieldterminal.domain.computeCircadianAlignment
import com.bioscan.fieldterminal.domain.computeMovementIndex
import com.bioscan.fieldterminal.domain.demotes
import com.bioscan.fieldterminal.domain.evaluateHrv
import com.bioscan.fieldterminal.domain.evaluateRespiratoryAnomaly
import com.bioscan.fieldterminal.domain.evaluateRhr
import com.bioscan.fieldterminal.domain.evaluateSleepDuration
import com.bioscan.fieldterminal.domain.evaluateSpo2
import com.bioscan.fieldterminal.domain.evaluateSri
import com.bioscan.fieldterminal.domain.evaluateSubjective
import com.bioscan.fieldterminal.domain.evaluations
import com.bioscan.fieldterminal.domain.expValue
import com.bioscan.fieldterminal.domain.expectedStateWord
import com.bioscan.fieldterminal.domain.interpretSwcEvaluation
import com.bioscan.fieldterminal.domain.leadReadout
import com.bioscan.fieldterminal.domain.minuteHrSummary
import com.bioscan.fieldterminal.domain.morningReadout
import com.bioscan.fieldterminal.domain.recoveryFrom
import com.bioscan.fieldterminal.domain.recoveryStateWord
import com.bioscan.fieldterminal.domain.sleepIndex
import com.bioscan.fieldterminal.domain.sriWord
import com.bioscan.fieldterminal.domain.stress.StressDay
import com.bioscan.fieldterminal.domain.stress.StressRhythmResult
import com.bioscan.fieldterminal.domain.stress.analyzeStressDay
import com.bioscan.fieldterminal.domain.toExpSpace
import com.bioscan.fieldterminal.domain.trendStateWord
import com.bioscan.fieldterminal.domain.vitalsTrendSeries
import com.bioscan.fieldterminal.ui.components.ComparisonStrip
import com.bioscan.fieldterminal.ui.components.DateTrendLine
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTCardEmphasis
import com.bioscan.fieldterminal.ui.components.FTDataChip
import com.bioscan.fieldterminal.ui.components.FTGlanceStrip
import com.bioscan.fieldterminal.ui.components.FTMetricRow
import com.bioscan.fieldterminal.ui.components.FTMetricValue
import com.bioscan.fieldterminal.ui.components.FTRangeIndicator
import com.bioscan.fieldterminal.ui.components.FTScoreRow
import com.bioscan.fieldterminal.ui.components.FTSegment
import com.bioscan.fieldterminal.ui.components.FTSegmentBar
import com.bioscan.fieldterminal.ui.components.FTStatePill
import com.bioscan.fieldterminal.ui.components.GlanceAnchors
import com.bioscan.fieldterminal.ui.components.GlanceChip
import com.bioscan.fieldterminal.ui.components.InfoHelpButton
import com.bioscan.fieldterminal.ui.components.LoadErrorCard
import com.bioscan.fieldterminal.ui.components.MinuteHeartRateChart
import com.bioscan.fieldterminal.ui.components.PinnedPage
import com.bioscan.fieldterminal.ui.components.RangeTrendCard
import com.bioscan.fieldterminal.ui.components.RedesignCard
import com.bioscan.fieldterminal.ui.components.SegmentedToggle
import com.bioscan.fieldterminal.ui.components.SubTabRow
import com.bioscan.fieldterminal.ui.components.TrendBaselineCard
import com.bioscan.fieldterminal.ui.components.TrendCard
import com.bioscan.fieldterminal.ui.components.glanceAnchor
import com.bioscan.fieldterminal.ui.components.parallel
import com.bioscan.fieldterminal.ui.components.personalScale
import com.bioscan.fieldterminal.ui.components.rememberGlanceAnchors
import com.bioscan.fieldterminal.ui.components.rememberHelp
import com.bioscan.fieldterminal.ui.components.rememberLoadGuard
import com.bioscan.fieldterminal.ui.components.stateTreatment
import com.bioscan.fieldterminal.ui.nav.HealthTab
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import com.bioscan.fieldterminal.util.Perf
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

// Redesign (spec §3): the Health page's Cardio, Sleep, Wellbeing and Sexual Health tabs on the shared card layouts
// (ui/components/RedesignCards.kt). Every graph follows the global range (GlobalTimeRange). The glance strip of metric
// pills at the top of each tab is gone; each card carries its own latest reading and direction pill, and its explanatory
// notes sit behind the "?" in its header.

private fun List<WearableAnalysisRow>.series(pick: (WearableAnalysisRow) -> Double?): List<Pair<LocalDate, Double>> =
    mapNotNull { r -> pick(r)?.let { LocalDate.parse(r.date) to it } }

@JvmName("sleepSeries")
private fun List<SleepAnalysisRow>.series(pick: (SleepAnalysisRow) -> Double?): List<Pair<LocalDate, Double>> =
    mapNotNull { r -> pick(r)?.let { LocalDate.parse(r.date) to it } }

private const val HEART_RATE_INFO =
    "Each column runs from the lowest to the highest heart rate your band recorded that day. At three months and " +
        "beyond a column spans the average of its days' lows to the average of its days' highs. The minute-by-minute " +
        "line for the latest day is on Today."

private const val STEPS_INFO =
    "Daily steps. The pill names the population band (Tudor-Locke and Bassett's steps-per-day categories) that the 15-day average falls in."

@Composable
internal fun CardioPage(
    w: List<WearableAnalysisRow>,
    s: List<SleepAnalysisRow>,
    hrRanges: List<Pair<LocalDate, DayRange>>,
    stepsBenchmark: List<BenchmarkArtifact>,
    breakStarts: SeriesBreakStarts,
) {
    val hrvPoints = w.series { it.hrv }
    val hrvEval = evaluateHrv(hrvPoints, windowStart = breakStarts.start("hrv")).toExpSpace()
    TrendBaselineCard("HRV", hrvPoints, "ms", pill = baselineStateWord(hrvEval.state, METRIC_DIRECTIONALITY.getValue("hrv")), methodology = "hrv", scale = personalScale(hrvPoints.takeLast(365).map { it.second }))

    RangeTrendCard("HEART RATE", hrRanges, "bpm", info = HEART_RATE_INFO)

    val rhrPoints = w.series { it.rhr }
    val rhrEval = evaluateRhr(rhrPoints, windowStart = breakStarts.start("rhr"))
    TrendBaselineCard("RESTING HEART RATE", rhrPoints, "bpm", pill = baselineStateWord(rhrEval.state, METRIC_DIRECTIONALITY.getValue("resting_heart_rate")), methodology = "rhr", scale = personalScale(rhrPoints.takeLast(365).map { it.second }))

    // Blood pressure (a range trend with two baseline bars) stays hidden until a manual blood-pressure log exists.

    val spo2Points = w.series { it.spo2 }
    val spo2Eval = evaluateSpo2(spo2Points)
    TrendBaselineCard("SPO2", spo2Points, "%", pill = baselineStateWord(spo2Eval.state, Directionality.NON_DIRECTIONAL))

    val stepsPoints = w.series { it.steps }
    val recent = stepsPoints.filter { ChronoUnit.DAYS.between(it.first, LocalDate.now()) <= 15 }
    val populationWord = recent.takeIf { it.size >= 2 }?.let { r ->
        comparePopulation(metric = "steps", current = r.map { it.second }.average(), context = PopulationContext(ageYears = null, sex = null, geography = null), artifacts = stepsBenchmark).bandLabel
    }?.let { StateWord(MetricState.Neutral, it.replace('_', ' ').uppercase()) }
    TrendBaselineCard("STEPS", stepsPoints, "steps / day", pill = populationWord, info = STEPS_INFO, format = { "%.0f".format(it) })

    val rrPoints = s.series { it.respiratoryRate }
    val rrEval = evaluateRespiratoryAnomaly(rrPoints)
    TrendBaselineCard(
        "RESPIRATORY RATE", rrPoints, "breaths / min",
        pill = if (rrEval.flagged) StateWord(MetricState.Warning, "ANOMALY FLAGGED") else null,
        methodology = "respiratory_rate_sleep",
        info = if (rrEval.flagged) "Two consecutive nights outside your baseline by more than 2 SD. Not a diagnosis." else null,
    )
}

@Composable
internal fun SleepPage(
    s: List<SleepAnalysisRow>,
    w: List<WearableAnalysisRow>,
    wb: List<WellbeingAnalysisRow>,
    exerciseSessions: List<TrainingLoadSessionRow>,
    meals: List<MealRow>,
    breakStarts: SeriesBreakStarts,
    loadSpans: LoadSpans,
) {
    val composite = remember(s, w, wb, exerciseSessions, breakStarts, loadSpans) { compositeInputs(w, s, wb, exerciseSessions, breakStarts, loadSpans) }
    val sleepIndexResult = remember(composite) { composite.sleepIndex() }
    val scorecards by rememberScorecards(w, s, wb, exerciseSessions, breakStarts, loadSpans)
    SleepIndexCard(sleepIndexResult, scorecards?.sleepIndex)

    val hoursPoints = s.series { it.hours }
    val durationEval = evaluateSleepDuration(hoursPoints, windowStart = breakStarts.start("sleep_hours"))
    TrendBaselineCard(
        "SLEEP DURATION", hoursPoints, "h",
        pill = baselineStateWord(durationEval.state, METRIC_DIRECTIONALITY.getValue("sleep_duration"), SLEEP_HOURS_RANGE, durationEval.baseline7d),
        methodology = "sleep_duration",
    )

    // One Trend and Baseline card per phase.
    TrendBaselineCard("REM", s.series { it.remMin }, "min", methodology = "sleep_stages", color = FT.Category.Sleep.c500, format = { "%.0f".format(it) })
    TrendBaselineCard("LIGHT SLEEP", s.series { it.lightMin }, "min", color = FT.Category.Sleep.c300, format = { "%.0f".format(it) })
    TrendBaselineCard("DEEP SLEEP", s.series { it.deepMin }, "min", color = FT.Category.Sleep.c700, format = { "%.0f".format(it) })

    // Circadian alignment waits for the routine-analysis refinement; it stays as it was until then.
    val sriEval = remember(s) {
        evaluateSri(s.mapNotNull { row ->
            val bedtime = row.bedtime?.let { runCatching { Instant.parse(it) }.getOrNull() }
            val wake = row.wakeTime?.let { runCatching { Instant.parse(it) }.getOrNull() }
            if (bedtime != null && wake != null) SleepNight(LocalDate.parse(row.date), bedtime, wake) else null
        })
    }
    val mealTimestamps = meals.mapNotNull { runCatching { OffsetDateTime.parse(it.loggedAt) }.getOrNull() }
    val exerciseTimestamps = exerciseSessions.mapNotNull { runCatching { OffsetDateTime.parse(it.startTime) }.getOrNull() }
    CircadianAlignmentCard(computeCircadianAlignment(sriEval, mealTimestamps, exerciseTimestamps))
}

/** Spec §3.2: the score sits on the right, opposite the title; nothing below the last component bar (regularity is one of them). */
@Composable
private fun SleepIndexCard(result: SleepIndexResult, scorecard: ScorecardResult?) {
    val (helpBody, helpDetails) = rememberHelp("sleep_index", null)
    RedesignCard {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("SLEEP INDEX", style = FTType.CardTitle, color = FT.TextPrimary)
            helpBody?.let { InfoHelpButton(title = "Sleep index", body = it, details = helpDetails) }
            Spacer(Modifier.weight(1f))
            if (!scorecard.demotes()) result.score?.let {
                Text("%.0f".format(it), style = FTType.MetricMedium, color = FT.TextPrimary)
                Text(" / 100", style = FTType.MonoCaption, color = FT.TextMuted)
            }
        }
        if (scorecard.demotes()) FTStatePill(INPUTS_ONLY)
        if (result.score != null) {
            result.components.forEach { (component, componentScore) ->
                FTScoreRow(component.name.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }, componentScore.score, FT.Category.Sleep.c500)
            }
        } else {
            Text("Not enough components yet: needs at least 2 of duration, regularity, respiratory and stage composition.", style = FTType.BodySmall, color = FT.TextSecondary)
        }
    }
}

@Composable
internal fun WellbeingPage(wb: List<WellbeingAnalysisRow>, stressDays: List<StressDay>) {
    listOf(
        "ENERGY" to wb.mapNotNull { r -> r.energy?.let { LocalDate.parse(r.date) to it.toDouble() } },
        "MOOD" to wb.mapNotNull { r -> r.mood?.let { LocalDate.parse(r.date) to it.toDouble() } },
        "STRESS" to wb.mapNotNull { r -> r.stress?.let { LocalDate.parse(r.date) to it.toDouble() } },
        "SORENESS" to wb.mapNotNull { r -> r.soreness?.let { LocalDate.parse(r.date) to it.toDouble() } },
    ).forEach { (title, points) -> SubjectiveTrendCard(title, points) }

    // Labelled apart from the self-rated STRESS above, so the two are never read as one. Its curve through the day is on Today.
    val stressPoints = stressDays.mapNotNull { d -> d.summary.avg?.let { d.date to it.toDouble() } }
    TrendBaselineCard("PHYSIOLOGICAL STRESS", stressPoints, "/ 100", color = FT.Category.Wellbeing.c500, info = "The band's stress score, daily average. The curve through the day is on Today.", format = { "%.0f".format(it) })
}

@Composable
internal fun SubjectiveTrendCard(title: String, points: List<Pair<LocalDate, Double>>) =
    TrendCard(title, points, "/ 10", pill = trendStateWord(evaluateSubjective(points).state), color = FT.Category.Wellbeing.c500, scale = personalScale(points.takeLast(365).map { it.second }))

@Composable
internal fun SexualHealthPage(wb: List<WellbeingAnalysisRow>, sa: List<LogSexualActivityRow>) {
    SexualHealthOccurrencesCard(sa)
    SexualHealthFrequencyCard(sa)
    SexualHealthHeatmapCard(sa)
    SubjectiveTrendCard("MORNING ERECTION QUALITY", wb.mapNotNull { r -> r.morningErectionQuality?.let { LocalDate.parse(r.date) to it.toDouble() } })
    SubjectiveTrendCard("AROUSAL", wb.mapNotNull { r -> r.arousalLevel?.let { LocalDate.parse(r.date) to it.toDouble() } })
    SexualHealthIntensityCard(sa)
}
