package com.bioscan.fieldterminal.ui.screens.user

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.AgingProfileOverview
import com.bioscan.fieldterminal.data.RunFormPoint
import com.bioscan.fieldterminal.data.RunningHistory
import com.bioscan.fieldterminal.domain.DataAvailability
import com.bioscan.fieldterminal.domain.DisplayValue
import com.bioscan.fieldterminal.domain.EF_MIN_RUNS_28D
import com.bioscan.fieldterminal.domain.HILLS_MIN_M_PER_KM
import com.bioscan.fieldterminal.domain.PerformanceTimeframe
import com.bioscan.fieldterminal.domain.QUALITY_Z4_SHARE
import com.bioscan.fieldterminal.domain.RecordRun
import com.bioscan.fieldterminal.domain.RunCategory
import com.bioscan.fieldterminal.domain.RunCategoryBasis
import com.bioscan.fieldterminal.domain.WeekBucket
import com.bioscan.fieldterminal.domain.bucketWeeklyStacked
import com.bioscan.fieldterminal.domain.efficiencyRollingMedian28
import com.bioscan.fieldterminal.domain.levels.formatPace
import com.bioscan.fieldterminal.domain.rollingMedian
import com.bioscan.fieldterminal.domain.runRecords
import com.bioscan.fieldterminal.domain.trailingWeeklyAverage
import com.bioscan.fieldterminal.ui.components.DateTrendLine
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTDataState
import com.bioscan.fieldterminal.ui.components.FTMetricRow
import com.bioscan.fieldterminal.ui.components.FTMetricValue
import com.bioscan.fieldterminal.ui.components.FTStackedWeeklyBars
import com.bioscan.fieldterminal.ui.components.SegmentedToggle
import com.bioscan.fieldterminal.ui.components.StackLegendRow
import com.bioscan.fieldterminal.ui.components.StackPart
import com.bioscan.fieldterminal.ui.components.StatTileSpec
import com.bioscan.fieldterminal.ui.components.TileGrid
import com.bioscan.fieldterminal.ui.components.TrendSeries
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val DATE_FMT = DateTimeFormatter.ofPattern("d MMM yyyy")

// Running page (owner's structure, Oct 2026): LEVEL, VOLUME split by kind of run, then one ROAD | TRAIL switch for every
// card below it (the efficiency and form charts, each per run with its 28-day median, and the records). FITNESS lives on
// Training › LOAD. Trail runs are recognised from their tag or Zepp's trail sport, so nothing asks to confirm them.

// The three kinds of run, stacked from the bottom in this order (palette checked with the dataviz validator against the
// dark surface: lightness, chroma, contrast; orange and green, the closest pair under colour blindness, never touch).
private val RUN_PARTS = listOf(
    StackPart("TRAIL", Color(0xFF2E9E5B)),
    StackPart("EASY", Color(0xFF3B86D6)),
    StackPart("QUALITY", Color(0xFFE8692E)),
)

private fun partIndex(category: RunCategory): Int = when (category) {
    RunCategory.TRAIL -> 0
    RunCategory.EASY -> 1
    RunCategory.QUALITY -> 2
}

private enum class RunSurface(val label: String) { ROAD("ROAD"), TRAIL("TRAIL") }

// App restructure: the Running page is Training's RUNNING tab.
@Composable
fun RunningTab() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        LoadedContent(load = { UserDataCache.running() }) { RunningContent(it) }
    }
}

@Composable
private fun RunningContent(history: RunningHistory) {
    var timeframe by remember { mutableStateOf(PerformanceTimeframe.ThreeMonths) }
    var surfaceName by rememberSaveable { mutableStateOf(RunSurface.ROAD.name) }
    val surface = RunSurface.valueOf(surfaceName)
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

    SegmentedToggle(
        options = RunSurface.entries, selected = surface, labelOf = { it.label }, onSelect = { surfaceName = it.name },
        accentColor = skillColor(Skill.RUNNING),
    )
    val trail = surface == RunSurface.TRAIL
    val form = remember(history, trail) { history.form.filter { it.trail == trail } }
    EfficiencyFactorCard(form, from, today, trail)
    RawAndMedianCard(
        title = "HR DECOUPLING",
        info = "How much the efficiency factor drops from the first half of a run to the second, in %. Under about 5 % suggests a solid " +
            "aerobic base; a bigger drift means heart rate crept up for the same output (heat, dehydration, fatigue). Only runs " +
            "with an efficiency factor have one.",
        all = form.series { it.hrDecouplingPct },
        from = from, today = today,
        valueFormat = { "%+.1f".format(it) }, unit = "% drift · lower = steadier",
        emptyText = "No ${surface.label.lowercase()} runs with a decoupling value in this window.",
    )
    PaceCard(history, trail, from, today)
    CadenceStrideCard(form, from, today, trail)
    // The watch reports ground contact and vertical ratio on road runs only: on TRAIL these two are left out.
    if (!trail) {
        RawAndMedianCard(
            title = "GROUND CONTACT",
            info = "How long each foot stays on the ground per step, in milliseconds (Zepp). Lower usually means a springier stride; it rises as you slow down or tire.",
            all = form.series { it.groundContactMs },
            from = from, today = today,
            valueFormat = { "%.0f".format(it) }, unit = "ms",
            emptyText = "No road runs with ground contact in this window.",
        )
        RawAndMedianCard(
            title = "VERTICAL RATIO",
            info = "How much you bounce relative to your stride length, in % (Zepp). Lower means more of the effort goes forward.",
            all = form.series { it.verticalRatioPct },
            from = from, today = today,
            valueFormat = { "%.1f".format(it) }, unit = "% of stride",
            emptyText = "No road runs with a vertical ratio in this window.",
        )
    }
    RecordsCard(history, trail, today)
}

private fun List<RunFormPoint>.series(pick: (RunFormPoint) -> Double?): List<Pair<LocalDate, Double>> =
    mapNotNull { f -> pick(f)?.let { f.date to it } }

// ---- VOLUME -----------------------------------------------------------------------------------------------------------

@Composable
private fun VolumeCard(history: RunningHistory, from: LocalDate, today: LocalDate) {
    val inWindow = history.runs.filter { !it.date.isBefore(from) }
    val placedByHeartRate = inWindow.count { it.categoryBasis == RunCategoryBasis.HEART_RATE_TIME || it.categoryBasis == RunCategoryBasis.AVERAGE_HEART_RATE }
    val placedByClimb = inWindow.count { it.categoryBasis == RunCategoryBasis.CLIMB }
    FTCard(
        title = "VOLUME",
        info = "Kilometres per Monday-to-Sunday week, split by kind of run. TRAIL: tagged trail or recorded as a trail run by Zepp. " +
            "QUALITY: tempo, threshold, intervals, hills and races. EASY: easy, recovery and long runs. A run without a run type is " +
            "placed from what it recorded: a climb of ${HILLS_MIN_M_PER_KM.toInt()} m/km or more is a hill session; otherwise it is QUALITY " +
            "when ${(QUALITY_Z4_SHARE * 100).toInt()} % or more of its heart-rate time was in zone 4 or above (its average heart rate when " +
            "there is no breakdown), in your own zones as of that date. Tagging a run's type always overrides this. The line is the " +
            "trailing 4-week average.",
    ) {
        if (inWindow.isEmpty()) {
            FTDataState(DataAvailability.Building, "No runs with distance in this window.")
            return@FTCard
        }
        val weeks = bucketWeeklyStacked(history.runs, { it.date }, { it.distanceKm }, { partIndex(it.category) }, RUN_PARTS.size, from, today)
        val total = inWindow.sumOf { it.distanceKm }
        TileGrid(
            listOf(
                StatTileSpec("TOTAL VOLUME", "%.1f".format(total), "km", secondary = "${inWindow.size} runs"),
                StatTileSpec("AVG PER WEEK", "%.1f".format(total / weeks.size.coerceAtLeast(1)), "km", secondary = "over ${weeks.size} weeks"),
            ),
        )
        FTStackedWeeklyBars(
            weeks, RUN_PARTS, unit = "km",
            average = trailingWeeklyAverage(weeks.map { WeekBucket(it.weekStart, it.total, 0) }),
            valueFormat = { "%.1f".format(it) },
        )
        RUN_PARTS.indices.reversed().forEach { i ->
            val km = inWindow.filter { partIndex(it.category) == i }.sumOf { it.distanceKm }
            StackLegendRow(RUN_PARTS[i], "%.0f km · %.0f %%".format(km, if (total > 0) km / total * 100 else 0.0))
        }
        if (placedByHeartRate + placedByClimb > 0) {
            Text(
                "No run type on ${placedByHeartRate + placedByClimb} of these runs: $placedByHeartRate placed by heart rate, $placedByClimb by climb.",
                style = FTType.Caption, color = FT.TextMuted,
            )
        }
    }
}

// ---- EFFICIENCY AND FORM ----------------------------------------------------------------------------------------------

// One metric per run as dots, with its 28-day median as the line (the median computed on the whole history, so the line
// starts right at the window's edge). The headline is the latest median.
@Composable
private fun RawAndMedianCard(
    title: String,
    info: String,
    all: List<Pair<LocalDate, Double>>,
    from: LocalDate,
    today: LocalDate,
    valueFormat: (Double) -> String,
    unit: String,
    emptyText: String,
    median: (List<Pair<LocalDate, Double>>) -> List<Pair<LocalDate, Double>> = { rollingMedian(it) },
    minCount: Int = 3,
    extra: (List<Pair<LocalDate, Double>>) -> List<TrendSeries> = { emptyList() },
) {
    FTCard(title = title, info = info) {
        val raw = all.filter { !it.first.isBefore(from) }
        if (raw.isEmpty()) {
            FTDataState(DataAvailability.Building, emptyText)
            return@FTCard
        }
        val line = median(all).filter { !it.first.isBefore(from) }
        val latest = line.lastOrNull()?.second
        if (latest != null) {
            FTMetricValue(DisplayValue(primary = valueFormat(latest), secondary = "28-day median"))
        } else {
            val n = all.count { it.first.isAfter(today.minusDays(28)) }
            Text("Building — the 28-day median needs $minCount runs in 28 days ($n so far).", style = FTType.Caption, color = FT.TextSecondary)
        }
        DateTrendLine(
            series = listOf(
                TrendSeries(raw, FT.TextMuted, "PER RUN", dotsOnly = true),
                TrendSeries(line, skillColor(Skill.RUNNING), "28D MEDIAN"),
            ) + extra(all),
            valueFormat = valueFormat,
            unit = unit,
            legend = true,
        )
    }
}

@Composable
private fun EfficiencyFactorCard(form: List<RunFormPoint>, from: LocalDate, today: LocalDate, trail: Boolean) {
    RawAndMedianCard(
        title = "EFFICIENCY FACTOR",
        info = "Grade-adjusted speed (m/min) ÷ average heart rate, for aerobic runs of 20+ minutes from Zepp. Rising over weeks usually " +
            "means better aerobic fitness. Grade-adjusted speed takes the hills out, which is what lets trail runs have one too; " +
            "compare a run with its own kind. The median needs $EF_MIN_RUNS_28D runs in 28 days.",
        all = form.series { it.efficiencyFactor },
        from = from, today = today,
        valueFormat = { "%.2f".format(it) }, unit = "EF (m/min per bpm)",
        emptyText = "No ${if (trail) "trail" else "road"} runs with an efficiency factor in this window.",
        median = ::efficiencyRollingMedian28, minCount = EF_MIN_RUNS_28D,
    )
}

@Composable
private fun PaceCard(history: RunningHistory, trail: Boolean, from: LocalDate, today: LocalDate) {
    val runs = remember(history, trail) { history.runs.filter { it.trail == trail && it.distanceKm >= 1.0 } }
    RawAndMedianCard(
        title = "PACE",
        info = "Average pace of each run of 1 km or more, with its 28-day median, and grade-adjusted pace (GAP, Zepp runs) as a 28-day " +
            "median: the pace the same effort would give on the flat. On trail, GAP is the line to follow; raw pace mostly says " +
            "how steep the ground was. Lower is faster.",
        all = runs.map { it.date to it.paceMinPerKm },
        from = from, today = today,
        valueFormat = ::formatPace, unit = "min/km · lower = faster",
        emptyText = "No ${if (trail) "trail" else "road"} runs in this window.",
        extra = {
            val gap = rollingMedian(runs.mapNotNull { r -> r.gapMinPerKm?.let { r.date to it } }).filter { !it.first.isBefore(from) }
            listOf(TrendSeries(gap, FT.Info, "GAP 28D MEDIAN", dashed = true))
        },
    )
}

// Cadence (per run and its 28-day median) over stride length (28-day median), two panels on one date span and one
// label gutter so a date sits at the same place in both; two units never share an axis.
@Composable
private fun CadenceStrideCard(form: List<RunFormPoint>, from: LocalDate, today: LocalDate, trail: Boolean) {
    FTCard(
        title = "CADENCE · STRIDE LENGTH",
        info = "Steps per minute per run with its 28-day median, and below it stride length as a 28-day median (Zepp). For the same " +
            "pace, cadence and stride trade off: a rising cadence with a shorter stride is the usual sign of a quicker, lighter step.",
    ) {
        val cadenceAll = form.series { it.cadenceSpm }
        val cadence = cadenceAll.filter { !it.first.isBefore(from) }
        val cadenceMedian = rollingMedian(cadenceAll).filter { !it.first.isBefore(from) }
        val stride = rollingMedian(form.series { it.strideCm }).filter { !it.first.isBefore(from) }
        if (cadence.isEmpty() && stride.isEmpty()) {
            FTDataState(DataAvailability.Building, "No ${if (trail) "trail" else "road"} runs with cadence in this window.")
            return@FTCard
        }
        val dates = (cadence + stride).map { it.first }
        val range = dates.min()..dates.max()
        cadenceMedian.lastOrNull()?.let { FTMetricValue(DisplayValue(primary = "%.0f".format(it.second), unit = "SPM", secondary = "28-day median")) }
        DateTrendLine(
            series = listOf(
                TrendSeries(cadence, FT.TextMuted, "PER RUN", dotsOnly = true),
                TrendSeries(cadenceMedian, skillColor(Skill.RUNNING), "28D MEDIAN"),
            ),
            valueFormat = { "%.0f".format(it) }, unit = "cadence · spm", legend = true,
            dateRange = range, minGutter = 36.dp,
        )
        if (stride.size >= 2) {
            DateTrendLine(
                series = listOf(TrendSeries(stride, FT.Info, "STRIDE 28D MEDIAN")),
                valueFormat = { "%.0f".format(it) }, unit = "stride length · cm", legend = true,
                dateRange = range, minGutter = 36.dp,
            )
        } else {
            Text("Stride length: its 28-day median needs 3 runs with a stride in 28 days.", style = FTType.Caption, color = FT.TextSecondary)
        }
    }
}

// ---- RECORDS ----------------------------------------------------------------------------------------------------------

@Composable
private fun RecordsCard(history: RunningHistory, trail: Boolean, today: LocalDate) {
    FTCard(
        title = "RECORDS",
        info = if (trail) {
            "All-time trail bests: the longest trail run, the biggest climb, the fastest grade-adjusted pace over 10 km or more (raw pace " +
                "on a trail mostly reflects the ground) and the biggest trail week."
        } else {
            "All-time road bests from your deduplicated run history: the longest run, the fastest run of 5 km or more and the biggest week of road running."
        },
    ) {
        val runs = history.runs.filter { it.trail == trail }.map { RecordRun(it.date, it.distanceKm, it.paceMinPerKm, it.elevationGainM, it.gapMinPerKm) }
        val records = remember(runs, trail) { runRecords(runs, trail, today) }
        val longest = records.longest
        if (longest == null) {
            FTDataState(DataAvailability.Building, "No ${if (trail) "trail" else "road"} runs recorded yet.")
            return@FTCard
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FTMetricRow(if (trail) "Longest trail run" else "Longest run", "${"%.1f".format(longest.value)} km · ${DATE_FMT.format(longest.date)}")
            records.fastest?.let {
                FTMetricRow(if (trail) "Fastest GAP ≥ 10 km" else "Fastest run ≥ 5 km", "${formatPace(it.value)} · ${DATE_FMT.format(it.date)}")
            }
            records.biggestClimb?.let { FTMetricRow("Biggest climb", "${"%.0f".format(it.value)} m · ${DATE_FMT.format(it.date)}") }
            records.biggestWeek?.let {
                FTMetricRow(if (trail) "Biggest trail week" else "Biggest week", "${"%.1f".format(it.value)} km · w/c ${DATE_FMT.format(it.date)}")
            }
        }
    }
}

// ---- FITNESS (shown on Training › LOAD) ---------------------------------------------------------------------------------

private enum class FitnessMetric(val label: String) { VO2MAX("VO2MAX"), LACTATE_THRESHOLD("LACTATE THRESHOLD") }

/** VO2max and lactate threshold: slow-moving fitness estimates, on the Load tab with its timeframe. */
@Composable
fun RunningFitnessCard(timeframe: PerformanceTimeframe) {
    LoadedContent(load = { UserDataCache.running() }) { history -> FitnessCard(history, timeframe) }
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
