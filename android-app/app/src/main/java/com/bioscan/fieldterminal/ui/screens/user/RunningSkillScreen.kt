package com.bioscan.fieldterminal.ui.screens.user

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.AgingProfileOverview
import com.bioscan.fieldterminal.data.RunningHistory
import com.bioscan.fieldterminal.domain.EF_MIN_RUNS_28D
import com.bioscan.fieldterminal.domain.PerformanceTimeframe
import com.bioscan.fieldterminal.domain.WeekAggregate
import com.bioscan.fieldterminal.domain.bucketWeekly
import com.bioscan.fieldterminal.domain.efficiencyRollingMedian28
import com.bioscan.fieldterminal.domain.levels.formatPace
import com.bioscan.fieldterminal.domain.trailingWeeklyAverage
import com.bioscan.fieldterminal.domain.vo2MaxRollingAverage
import com.bioscan.fieldterminal.ui.components.DateTrendLine
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTDataState
import com.bioscan.fieldterminal.ui.components.FTMetricRow
import com.bioscan.fieldterminal.ui.components.FTMetricValue
import com.bioscan.fieldterminal.ui.components.FTSmallMultiples
import com.bioscan.fieldterminal.ui.components.FTWeeklyBars
import com.bioscan.fieldterminal.ui.components.SegmentedToggle
import com.bioscan.fieldterminal.ui.components.SmallMultiple
import com.bioscan.fieldterminal.ui.components.TrendSeries
import com.bioscan.fieldterminal.domain.DataAvailability
import com.bioscan.fieldterminal.domain.DisplayValue
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private enum class FitnessMetric(val label: String) { VO2MAX("VO2MAX"), LACTATE_THRESHOLD("LACTATE THRESHOLD") }

private val DATE_FMT = DateTimeFormatter.ofPattern("d MMM yyyy")

@Composable
fun RunningSkillScreen(onBack: () -> Unit) {
    SkillPage("RUNNING", "VOLUME · PACE · FITNESS · EFFICIENCY · FORM", onBack) {
        LoadedContent(load = { UserDataCache.running() }) { RunningContent(it) }
    }
}

@Composable
private fun RunningContent(history: RunningHistory) {
    var timeframe by remember { mutableStateOf(PerformanceTimeframe.ThreeMonths) }
    val aging by produceState<AgingProfileOverview?>(initialValue = null) { value = runCatching { UserDataCache.aging() }.getOrNull() }
    val vo2Percentile by produceState<Double?>(initialValue = null) { value = runCatching { UserDataCache.vo2Percentile() }.getOrNull() }
    val level = remember(history, vo2Percentile) { runningLevelOf(history, vo2Percentile) }
    val today = remember { LocalDate.now() }
    val from = today.minusMonths(timeframe.months)

    SkillHeroCard(
        Skill.RUNNING, level,
        info = "Your VO2max placed against the age- and sex-matched benchmark table (a population percentile) when one fits; otherwise " +
            "your best recent road pace against your own run history, marked PROVISIONAL. Trail runs and implausible paces are excluded.",
    ) {
        aging?.cardioAge?.biologicalAge?.let { FTMetricRow("Cardio age", "%.0f yrs".format(it)) }
        history.roadRuns.maxByOrNull { it.date }?.let { FTMetricRow("Last road run", "${formatPace(it.paceMinPerKm)} · ${"%.1f".format(it.distanceKm)} km") }
    }

    SegmentedToggle(
        options = PerformanceTimeframe.entries, selected = timeframe, labelOf = { it.label }, onSelect = { timeframe = it },
        accentColor = skillColor(Skill.RUNNING),
    )

    VolumeCard(history, from, today)
    PaceCard(history, from)
    FitnessCard(history, timeframe)
    EfficiencyCard(history, from, today)
    FormCard(history, from)
    RecordsCard(history, today)
}

@Composable
private fun VolumeCard(history: RunningHistory, from: LocalDate, today: LocalDate) {
    FTCard(title = "VOLUME", info = "Kilometres per Monday-to-Sunday week, all runs including trail. Quiet weeks are real zeros. The line is the trailing 4-week average.") {
        val inWindow = history.runs.filter { !it.date.isBefore(from) }
        if (inWindow.isEmpty()) {
            FTDataState(DataAvailability.Building, "No runs with distance in this window.")
            return@FTCard
        }
        val weekly = bucketWeekly(history.runs, { it.date }, { it.distanceKm }, from, today)
        FTWeeklyBars(weekly, color = skillColor(Skill.RUNNING), unit = "km", average = trailingWeeklyAverage(weekly), valueFormat = { "%.1f".format(it) })
        val weeks = weekly.size.coerceAtLeast(1)
        FTMetricRow("Total", "${"%.1f".format(inWindow.sumOf { it.distanceKm })} km in ${inWindow.size} runs")
        FTMetricRow("Average per week", "${"%.1f".format(inWindow.sumOf { it.distanceKm } / weeks)} km")

        SectionLabel("LONGEST RUN PER WEEK")
        FTWeeklyBars(
            bucketWeekly(history.runs, { it.date }, { it.distanceKm }, from, today, WeekAggregate.MAX),
            color = skillColor(Skill.STRENGTH), unit = "km", valueFormat = { "%.1f".format(it) }, height = 80.dp,
        )
    }
}

@Composable
private fun PaceCard(history: RunningHistory, from: LocalDate) {
    FTCard(title = "PACE", info = "Average pace of each road run of 1 km or more (trail excluded), with a 28-day rolling average. Lower is faster. Pace depends on terrain, weather and effort, so read the trend, not single runs.") {
        val road = history.roadRuns.filter { it.distanceKm >= 1.0 }
        val points = road.map { it.date to it.paceMinPerKm }
        val windowed = points.filter { !it.first.isBefore(from) }
        if (windowed.size < 2) {
            FTDataState(DataAvailability.Building, "Needs at least 2 road runs in this window.")
            return@FTCard
        }
        val avg = vo2MaxRollingAverage(points, 28).filter { !it.first.isBefore(from) }
        DateTrendLine(
            series = listOf(TrendSeries(windowed, FT.TextMuted, "RUN"), TrendSeries(avg, skillColor(Skill.RUNNING), "28D AVG")),
            valueFormat = ::formatPace,
            unit = "min/km · lower = faster",
        )
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LegendDot("PER RUN", FT.TextMuted)
            LegendDot("28D AVG", skillColor(Skill.RUNNING))
        }
        windowed.minByOrNull { it.second }?.let { FTMetricRow("Fastest run in window", "${formatPace(it.second)} · ${DATE_FMT.format(it.first)}") }
    }
}

@Composable
private fun FitnessCard(history: RunningHistory, timeframe: PerformanceTimeframe) {
    val available = listOfNotNull(
        FitnessMetric.VO2MAX.takeIf { history.vo2MaxSeries.isNotEmpty() },
        FitnessMetric.LACTATE_THRESHOLD.takeIf { history.ltPaceSeries.isNotEmpty() },
    )
    FTCard(title = "FITNESS", info = "Slow-moving wearable estimates, not lab tests: VO2max from your watch via Health Connect, lactate threshold from Zepp (recomputed on qualifying runs).") {
        if (available.isEmpty()) {
            FTDataState(DataAvailability.Building, "No VO2max or lactate-threshold readings yet.")
            return@FTCard
        }
        var selected by remember(available) { mutableStateOf(available.first()) }
        if (available.size > 1) SegmentedToggle(available, selected, { it.label }, { selected = it }, accentColor = skillColor(Skill.RUNNING))
        when (selected) {
            FitnessMetric.VO2MAX -> {
                FTMetricValue(DisplayValue(primary = "%.1f".format(history.latestVo2Max ?: history.vo2MaxSeries.last().second), unit = "ML/KG/MIN"))
                PerformanceTrend(history.vo2MaxSeries, timeframe, { "%.1f".format(it) }, "ml/kg/min")
            }
            FitnessMetric.LACTATE_THRESHOLD -> {
                FTMetricValue(
                    DisplayValue(
                        primary = formatPace(history.ltPaceSeries.last().second),
                        secondary = history.ltHrSeries.lastOrNull()?.second?.let { "%.0f bpm".format(it) } ?: "HR unavailable",
                    ),
                )
                PerformanceTrend(history.ltPaceSeries, timeframe, ::formatPace, "pace · min:sec per km")
            }
        }
    }
}

@Composable
private fun EfficiencyCard(history: RunningHistory, from: LocalDate, today: LocalDate) {
    FTCard(
        title = "EFFICIENCY",
        info = "Efficiency factor = grade-adjusted speed (m/min) ÷ average heart rate, for easy/aerobic runs of 20+ minutes from Zepp. " +
            "Rising over weeks usually means better aerobic fitness. Decoupling is how much that ratio drops from the first to the second half of a run; under ~5% suggests a solid aerobic base.",
    ) {
        val ef = history.form.mapNotNull { f -> f.efficiencyFactor?.let { f.date to it } }
        if (ef.isEmpty()) {
            FTDataState(DataAvailability.Building, "No aerobic runs with an efficiency factor yet.")
            return@FTCard
        }
        val rolling = efficiencyRollingMedian28(ef)
        val median = rolling.lastOrNull()?.second
        if (median != null) {
            FTMetricValue(DisplayValue(primary = "%.2f".format(median), unit = "EF", secondary = "28-day median"))
        } else {
            val n = ef.count { it.first.isAfter(today.minusDays(28)) }
            Text("Building — needs $EF_MIN_RUNS_28D aerobic runs of 20+ min in 28 days ($n so far).", style = FTType.Caption, color = FT.TextSecondary)
        }
        val windowed = ef.filter { !it.first.isBefore(from) }
        if (windowed.size >= 2) {
            DateTrendLine(
                series = listOf(TrendSeries(windowed, FT.TextMuted, "RUN"), TrendSeries(rolling.filter { !it.first.isBefore(from) }, skillColor(Skill.RUNNING), "28D MEDIAN")),
                valueFormat = { "%.2f".format(it) },
                unit = "EF (m/min per bpm)",
            )
        }
        FTSmallMultiples(
            listOf(
                SmallMultiple(
                    "Grade-adj. pace", "min/km",
                    history.form.mapNotNull { f -> f.gapMinPerKm?.let { f.date to it } }.filter { !it.first.isBefore(from) },
                    history.form.lastOrNull { it.gapMinPerKm != null }?.gapMinPerKm?.let { formatPace(it).removeSuffix(" /km") },
                    skillColor(Skill.RUNNING), note = "Building — Zepp runs only",
                ),
                SmallMultiple(
                    "HR decoupling", "%",
                    history.form.mapNotNull { f -> f.hrDecouplingPct?.let { f.date to it } }.filter { !it.first.isBefore(from) },
                    history.form.lastOrNull { it.hrDecouplingPct != null }?.hrDecouplingPct?.let { "%+.1f".format(it) },
                    FT.Info, note = "Building — needs an efficiency-eligible run",
                ),
            ),
        )
    }
}

@Composable
private fun FormCard(history: RunningHistory, from: LocalDate) {
    FTCard(title = "FORM", info = "Running-dynamics averages per run from your Zepp watch. Each is on its own scale; this shows how your own form moves over time, not whether a number is good.") {
        fun series(pick: (com.bioscan.fieldterminal.data.RunFormPoint) -> Double?) =
            history.form.mapNotNull { f -> pick(f)?.let { f.date to it } }.filter { !it.first.isBefore(from) }
        fun latest(pick: (com.bioscan.fieldterminal.data.RunFormPoint) -> Double?, fmt: String) = history.form.lastOrNull { pick(it) != null }?.let { fmt.format(pick(it)) }
        if (listOf<(com.bioscan.fieldterminal.data.RunFormPoint) -> Double?>({ it.cadenceSpm }, { it.strideCm }, { it.groundContactMs }, { it.verticalRatioPct }).all { series(it).isEmpty() }) {
            FTDataState(DataAvailability.Building, "No running-dynamics data in this window (needs Zepp-synced runs).")
            return@FTCard
        }
        FTSmallMultiples(
            listOf(
                SmallMultiple("Cadence", "spm", series { it.cadenceSpm }, latest({ it.cadenceSpm }, "%.0f"), FT.Category.Activity.c500),
                SmallMultiple("Stride length", "cm", series { it.strideCm }, latest({ it.strideCm }, "%.0f"), FT.Category.Activity.c300),
                SmallMultiple("Ground contact", "ms", series { it.groundContactMs }, latest({ it.groundContactMs }, "%.0f"), FT.Category.Activity.c700),
                SmallMultiple("Vertical ratio", "%", series { it.verticalRatioPct }, latest({ it.verticalRatioPct }, "%.1f"), FT.Info),
            ),
        )
    }
}

@Composable
private fun RecordsCard(history: RunningHistory, today: LocalDate) {
    FTCard(title = "RECORDS", info = "All-time bests from your deduplicated run history. Fastest pace counts road runs of 5 km or more.") {
        val longest = history.runs.maxByOrNull { it.distanceKm }
        val fastest = history.roadRuns.filter { it.distanceKm >= 5.0 }.minByOrNull { it.paceMinPerKm }
        val bestWeek = history.runs.firstOrNull()?.let { first ->
            bucketWeekly(history.runs, { it.date }, { it.distanceKm }, first.date, today).maxByOrNull { it.value }
        }
        if (longest == null) {
            FTDataState(DataAvailability.Building, "No runs recorded yet.")
            return@FTCard
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FTMetricRow("Longest run", "${"%.1f".format(longest.distanceKm)} km · ${DATE_FMT.format(longest.date)}")
            fastest?.let { FTMetricRow("Fastest run ≥ 5 km", "${formatPace(it.paceMinPerKm)} · ${DATE_FMT.format(it.date)}") }
            bestWeek?.takeIf { it.value > 0 }?.let { FTMetricRow("Biggest week", "${"%.1f".format(it.value)} km · w/c ${DATE_FMT.format(it.weekStart)}") }
        }
    }
}
