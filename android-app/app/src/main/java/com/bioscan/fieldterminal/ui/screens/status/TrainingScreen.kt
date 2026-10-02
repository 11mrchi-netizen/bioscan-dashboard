package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.rememberCoroutineScope
import com.bioscan.fieldterminal.data.AddEntryRepository
import com.bioscan.fieldterminal.data.SuspectedTrailRun
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.TrainingOverview
import kotlinx.coroutines.launch
import com.bioscan.fieldterminal.data.TrainingRepository
import com.bioscan.fieldterminal.domain.DisplayValue
import com.bioscan.fieldterminal.domain.PerformanceTimeframe
import com.bioscan.fieldterminal.domain.RunningPeriod
import com.bioscan.fieldterminal.domain.averagePaceMinPerKmSince
import com.bioscan.fieldterminal.domain.hasPlausiblePace
import com.bioscan.fieldterminal.domain.comparison.comparableSet
import com.bioscan.fieldterminal.domain.comparison.comparePersonal
import com.bioscan.fieldterminal.domain.comparison.isComparableSessionDistance
import com.bioscan.fieldterminal.domain.preparePerformanceTrendData
import com.bioscan.fieldterminal.domain.sumDistanceKmSince
import com.bioscan.fieldterminal.ui.components.ComparisonStrip
import com.bioscan.fieldterminal.ui.components.DateTrendLine
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTMetricValue
import com.bioscan.fieldterminal.ui.components.InfoHelpButton
import com.bioscan.fieldterminal.domain.EF_MIN_RUNS_28D
import com.bioscan.fieldterminal.domain.efficiencyRollingMedian28
import com.bioscan.fieldterminal.ui.components.RangeBar
import com.bioscan.fieldterminal.ui.components.SegmentedToggle
import com.bioscan.fieldterminal.ui.components.TrendSeries
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import com.bioscan.fieldterminal.ui.theme.Inter
import com.bioscan.fieldterminal.ui.theme.RobotoMono
import com.bioscan.fieldterminal.data.model.ExerciseSessionRow
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

// Step 7 (Phase C), generalized in Phase G3 from runs-only to any exercise
// type via `exercise_sessions` (see data/TrainingRepository.kt). STRENGTH
// finally has a real data source -- Step 7's own finding was that none
// existed anywhere in this project; Health Connect is the first. Kept
// basic here per the user's own framing: counts/minutes this week, not a
// full per-lift breakdown -- that's Phase G5's "combined session detail"
// view, once one exists to link to.
@Composable
fun TrainingScreen(timeframe: PerformanceTimeframe, onOpenSessionDetail: (Long) -> Unit = {}) {
    var overview by remember { mutableStateOf<TrainingOverview?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        try {
            overview = TrainingRepository(SupabaseClientProvider.client).loadOverview()
        } catch (e: Exception) {
            error = e.message ?: "Unknown error"
        }
        isLoading = false
    }

    // Tags every copy (HC/manual/Zepp) of each run as trail, then reloads so the
    // RUNNING card's trail count/elevation and the suspected list update.
    val onMarkTrail: (List<Long>) -> Unit = { ids ->
        scope.launch {
            val repo = AddEntryRepository(SupabaseClientProvider.client)
            ids.forEach { repo.updateRouteType(it, "trail") }
            overview = TrainingRepository(SupabaseClientProvider.client).loadOverview()
        }
    }

    when {
        isLoading -> Box(Modifier.fillMaxWidth().padding(vertical = 60.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = FT.DomainTraining)
        }
        error != null -> Box(Modifier.fillMaxWidth().padding(vertical = 60.dp), contentAlignment = Alignment.Center) {
            Text("Failed to load: $error", style = TextStyle(fontFamily = Inter, fontSize = 14.sp), color = FT.Critical)
        }
        else -> TrainingContent(overview!!, timeframe, onOpenSessionDetail, onMarkTrail)
    }
}

@Composable
private fun TrainingContent(overview: TrainingOverview, timeframe: PerformanceTimeframe, onOpenSessionDetail: (Long) -> Unit, onMarkTrail: (List<Long>) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        // 28/9: PERFORMANCE and RUN EFFICIENCY folded into one RUNNING card
        // (previously three separate top-level cards) per direct user
        // request -- both are running-related, and neither is per-session so
        // they read fine as sub-sections rather than their own cards.
        RunningCard(overview, timeframe, onMarkTrail)

        FTCard(title = "STRENGTH") {
            if (overview.hasAnyStrength) {
                FTMetricValue(
                    DisplayValue(
                        primary = "${overview.strengthSessionsThisWeek}",
                        unit = if (overview.strengthSessionsThisWeek == 1) "SESSION" else "SESSIONS",
                    ),
                )
                StatLine("This week", "${overview.strengthMinutesThisWeek} min")
                Text(
                    "Synced from Health Connect. Per-lift/set detail isn't shown here yet.",
                    style = TextStyle(fontFamily = Inter, fontSize = 12.5.sp),
                    color = FT.TextSecondary,
                )
            } else {
                Text(
                    text = "No strength sessions synced yet.",
                    style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
                    color = FT.TextSecondary,
                )
                Text(
                    text = "Health Connect is this app's real data source for strength training now — " +
                        "log a workout with any Health-Connect-aware app on your phone and it'll show up here after the next sync.",
                    style = TextStyle(fontFamily = Inter, fontSize = 12.5.sp),
                    color = FT.TextSecondary,
                )
            }
        }

        SessionListCard(overview.recentSessions, onOpenSessionDetail)
    }
}

// Live check: nothing on this tab showed a plain "everything you did"
// list -- RUNNING/STRENGTH above are weekly rollups only. Reuses the same
// recentSessions fetch those cards already get (no new query) and the same
// session_detail/{id} route the Log tab's EntryRow already opens.
@Composable
private fun SessionListCard(sessions: List<ExerciseSessionRow>, onOpenSessionDetail: (Long) -> Unit) {
    if (sessions.isEmpty()) return
    FTCard(title = "ALL SESSIONS") {
        sessions.take(20).forEach { session -> SessionRow(session, onClick = { onOpenSessionDetail(session.id) }) }
    }
}

@Composable
private fun SessionRow(session: ExerciseSessionRow, onClick: () -> Unit) {
    val when_ = try {
        OffsetDateTime.parse(session.startTime).toLocalDate().format(DateTimeFormatter.ofPattern("EEE d MMM"))
    } catch (e: Exception) {
        session.startTime
    }
    val detail = session.distanceKm?.let { "%.1f km".format(it) }
        ?: session.durationMin?.let { "${it.toInt()} min" }
        ?: "—"

    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(session.type.replaceFirstChar { it.uppercase() }, style = TextStyle(fontFamily = Inter, fontSize = 14.5.sp), color = FT.TextPrimary)
            Text(when_, style = TextStyle(fontFamily = Inter, fontSize = 12.sp), color = FT.TextSecondary)
        }
        Text(detail, style = TextStyle(fontFamily = RobotoMono, fontSize = 14.5.sp), color = FT.TextSecondary)
    }
}

// Distance run over a selectable window -- computed client-side from the
// same `exercise_sessions` rows the RUNNING card above already fetched,
// using the existing sumDistanceKmSince(). No new query per period switch.
// DAV-115/123: RUNNING — THIS WEEK and DISTANCE TOTALS combined into one
// card per direct user request -- both were showing the same underlying
// runningSessions rows, just at a fixed week vs. a switchable period.
// "4-week avg km/wk" is dropped as its own fixed stat since selecting "1M"
// on the same toggle now covers that; longest run stays all-time (its own
// label already says so, not something a timeframe toggle should touch).
// The weekly-volume RangeBar only makes sense at the Week grain -- a fixed
// 32km ceiling means nothing once the period can be a year, so it's shown
// only there rather than picking an arbitrary scaled ceiling for the rest.
@Composable
private fun RunningCard(overview: TrainingOverview, timeframe: PerformanceTimeframe, onMarkTrail: (List<Long>) -> Unit) {
    var period by remember { mutableStateOf(RunningPeriod.Week) }
    val today = LocalDate.now()

    FTCard(title = "RUNNING") {
        if (!overview.hasAnyRunning) {
            Text("No running sessions logged yet.", style = TextStyle(fontFamily = Inter, fontSize = 15.5.sp), color = FT.TextSecondary)
        } else {
            val totalKm = sumDistanceKmSince(overview.runningSessions, today, period.days)
            val avgPace = averagePaceMinPerKmSince(overview.runningSessions, today, period.days)
            val trailRuns = overview.runningSessions.filter {
                it.details.routeType == "trail" &&
                    ChronoUnit.DAYS.between(OffsetDateTime.parse(it.startTime).toLocalDate(), today) < period.days
            }

            SegmentedToggle(options = RunningPeriod.entries, selected = period, labelOf = { it.label }, onSelect = { period = it })
            FTMetricValue(DisplayValue(primary = "%.1f".format(totalKm), unit = "KM"))
            if (period == RunningPeriod.Week) {
                RangeBar(value = totalKm, max = 32.0, watchBelow = null, color = FT.Emerald)
            }

            avgPace?.let { StatLine("Avg pace", formatPace(it)) }

            // DAV-198: pace is genuinely session-shaped (unlike HRV/RHR's
            // daily wearable readings, which have no context to filter on --
            // see ComparableSet.kt's own header comment) -- a 5K and a
            // marathon aren't comparable regardless of fitness, so history is
            // restricted to similar-distance runs before comparePersonal ever
            // sees it.
            val comparableRuns = overview.runningSessions.filter {
                it.details.routeType != "trail" && hasPlausiblePace(it.distanceKm, it.durationMin)
            }
            val latestRun = comparableRuns.maxByOrNull { OffsetDateTime.parse(it.startTime) }
            val latestDistance = latestRun?.distanceKm
            val latestDuration = latestRun?.durationMin
            if (latestRun != null && latestDistance != null && latestDistance > 0 && latestDuration != null) {
                val paceHistory = comparableSet(comparableRuns.filter { it.id != latestRun.id }) { s ->
                    s.distanceKm?.let { isComparableSessionDistance(latestDistance, it) } == true
                }.comparable.mapNotNull { s ->
                    val d = s.distanceKm ?: return@mapNotNull null
                    val dur = s.durationMin ?: return@mapNotNull null
                    OffsetDateTime.parse(s.startTime).toLocalDate() to (dur / d)
                }
                val paceComparison = comparePersonal(
                    metric = "pace",
                    current = latestDuration / latestDistance,
                    history = paceHistory,
                    presentSources = setOf("exercise_sessions"),
                    idealSources = setOf("exercise_sessions"),
                    origin = "exercise_sessions",
                )
                ComparisonStrip(personal = paceComparison)
            }

            overview.longestRunKm?.let { longest ->
                StatLine("Longest run (all-time)", "%.2f km".format(longest))
            }

            // DAV-144. No per-session list exists on this tab (see
            // docs/trail-intelligence/05-trail-metrics-ui-presentation.md) --
            // this is the same rollup shape every other figure here uses, only
            // shown when a real trail run happened in the selected period.
            if (trailRuns.isNotEmpty()) {
                StatLine("Trail runs", "${trailRuns.size}")
                trailRuns.mapNotNull { it.elevationGainM }.takeIf { it.isNotEmpty() }?.sum()?.let { gain ->
                    StatLine("Elevation gained", "${gain.toInt()} m")
                }
            }

            SuspectedTrailRuns(overview.suspectedTrailRuns, onMarkTrail)
        }

        // 28/9: folded in from the old standalone PERFORMANCE/RUN EFFICIENCY
        // cards -- a wearable/Zepp-derived trend independent of whether any
        // running session has been logged, so each self-hides on no data
        // rather than being gated behind hasAnyRunning above (a real display
        // bug this reorder originally fixed: VO2max previously never showed
        // at all for a no-running-history account, despite coming from
        // wearable_daily, not exercise_sessions).
        PerformanceSection(overview, timeframe)
        RunEfficiencySection(overview, timeframe)
    }
}

@Composable
private fun SectionDivider() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(FT.GlassBorder))
}

private val sectionLabelStyle = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp, letterSpacing = 0.14f.em)

// Untagged runs that look like trail runs (Zepp said trail, or a steep
// climb/km) -- collapsed by default, one tap to confirm each or all.
@Composable
private fun SuspectedTrailRuns(suspected: List<SuspectedTrailRun>, onMark: (List<Long>) -> Unit) {
    if (suspected.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { expanded = !expanded }
            .padding(top = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            "SUSPECTED TRAIL RUNS (${suspected.size})",
            style = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp, letterSpacing = 0.14f.em),
            color = FT.Warning,
        )
        Text(if (expanded) "HIDE" else "REVIEW", style = TextStyle(fontFamily = RobotoMono, fontSize = 11.5.sp), color = FT.TextSecondary)
    }
    if (!expanded) return
    Text(
        "Logged as plain runs, but Zepp called them trail or they climb like one. Nothing changes until you confirm.",
        style = TextStyle(fontFamily = Inter, fontSize = 12.sp),
        color = FT.TextSecondary,
    )
    suspected.forEach { s ->
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                val date = OffsetDateTime.parse(s.row.startTime).toLocalDate().format(DateTimeFormatter.ofPattern("EEE d MMM"))
                Text("$date · ${"%.1f".format(s.row.distanceKm ?: 0.0)} km", style = TextStyle(fontFamily = Inter, fontSize = 14.sp), color = FT.TextPrimary)
                Text(s.reason, style = TextStyle(fontFamily = RobotoMono, fontSize = 10.5.sp), color = FT.TextSecondary)
            }
            TrailMarkButton("MARK") { onMark(s.sessionIds) }
        }
    }
    if (suspected.size > 1) {
        TrailMarkButton("MARK ALL AS TRAIL", modifier = Modifier.fillMaxWidth()) { onMark(suspected.flatMap { it.sessionIds }.distinct()) }
    }
}

@Composable
private fun TrailMarkButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(FT.RadiusSmall)
    Box(
        modifier = modifier
            .border(FT.BorderWidth, FT.DomainTraining, shape)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp), color = FT.DomainTraining)
    }
}

// DAV-272: Efficiency Factor (grade-adjusted speed per heartbeat) from Zepp-synced
// aerobic runs -- computed server-side, so this only reads scalars. Shown as a
// 28-day rolling median once enough runs exist; a single run's EF swings too
// much with heat, sleep and terrain to read on its own.
@Composable
private fun RunEfficiencySection(overview: TrainingOverview, timeframe: PerformanceTimeframe) {
    if (overview.efficiencyPoints.isEmpty() && overview.latestGapMinPerKm == null) return
    val today = LocalDate.now()
    val rolling = efficiencyRollingMedian28(overview.efficiencyPoints)
    val cutoff = today.minusMonths(timeframe.months)

    SectionDivider()
    Text("RUN EFFICIENCY", style = sectionLabelStyle, color = FT.TextMuted)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Efficiency factor", style = TextStyle(fontFamily = Inter, fontSize = 14.5.sp), color = FT.TextSecondary)
        InfoHelpButton("Efficiency factor", EF_HELP)
    }
    val median = rolling.lastOrNull()?.second
    if (median != null) {
        FTMetricValue(DisplayValue(primary = "%.2f".format(median), unit = "EF", secondary = "28-day median"))
    } else {
        val n = overview.efficiencyPoints.count { it.first.isAfter(today.minusDays(28)) }
        Text(
            "Building — needs $EF_MIN_RUNS_28D aerobic runs of 20+ min in 28 days ($n so far).",
            style = TextStyle(fontFamily = Inter, fontSize = 12.5.sp),
            color = FT.TextSecondary,
        )
    }
    overview.latestGapMinPerKm?.let { StatLine("Latest grade-adjusted pace", formatPace(it)) }
    overview.latestHrDecouplingPct?.let { StatLine("Latest HR decoupling", "%+.1f%%".format(it)) }

    val raw = overview.efficiencyPoints.filter { it.first >= cutoff }
    if (raw.size >= 2) {
        DateTrendLine(
            series = listOf(
                TrendSeries(raw, FT.TextMuted, "RUN"),
                TrendSeries(rolling.filter { it.first >= cutoff }, FT.Emerald, "28D MEDIAN"),
            ),
            valueFormat = { "%.2f".format(it) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            LegendItem("PER RUN", FT.TextMuted)
            LegendItem("28D MEDIAN", FT.Emerald)
        }
    }
}

private const val EF_HELP =
    "Efficiency factor is how much flat-ground running speed you produce per heartbeat: " +
        "grade-adjusted speed (m/min) ÷ average heart rate.\n\n" +
        "Rising over weeks usually means better aerobic fitness. Only easy/aerobic runs of 20+ minutes count " +
        "(below your watch's lactate-threshold heart rate), because hard sessions aren't comparable.\n\n" +
        "HR decoupling is how much that ratio drops from the first to the second half of a run — under ~5% suggests a solid aerobic base."

private enum class PerformanceMetric(val label: String) {
    VO2MAX("VO2MAX"),
    LACTATE_THRESHOLD("LACTATE THRESHOLD"),
}

// DAV-115/123: VO2max and lactate threshold on one switchable card -- both
// are slow-moving fitness estimates on the same 1M/3M/6M/1Y timeframe, not
// per-session figures, so they share the toggle/chart rather than each
// getting their own copy of it. Card is hidden entirely only when neither
// metric has ever had a real reading.
@Composable
private fun PerformanceSection(overview: TrainingOverview, timeframe: PerformanceTimeframe) {
    val available = listOfNotNull(
        PerformanceMetric.VO2MAX.takeIf { overview.latestVo2Max != null },
        PerformanceMetric.LACTATE_THRESHOLD.takeIf { overview.latestLactateThresholdPaceMinPerKm != null },
    )
    if (available.isEmpty()) return

    var selected by remember(available) { mutableStateOf(available.first()) }
    val vo2maxPersonal = overview.latestVo2Max?.let { latest ->
        comparePersonal(
            metric = "vo2max",
            current = latest,
            history = overview.vo2MaxSeries,
            presentSources = setOf("wearable_daily"),
            idealSources = setOf("wearable_daily"),
            origin = "wearable_daily",
        )
    }

    SectionDivider()
    Text("PERFORMANCE", style = sectionLabelStyle, color = FT.TextMuted)
    if (available.size > 1) {
        SegmentedToggle(options = available, selected = selected, labelOf = { it.label }, onSelect = { selected = it })
    }
    when (selected) {
        PerformanceMetric.VO2MAX -> {
            FTMetricValue(DisplayValue(primary = "%.1f".format(overview.latestVo2Max)))
            Text(
                "Wearable-estimated, not lab-confirmed.",
                style = TextStyle(fontFamily = Inter, fontSize = 12.5.sp),
                color = FT.TextSecondary,
            )
            vo2maxPersonal?.let { ComparisonStrip(personal = it) }
        }
        PerformanceMetric.LACTATE_THRESHOLD -> {
            FTMetricValue(
                DisplayValue(
                    primary = formatPace(overview.latestLactateThresholdPaceMinPerKm!!),
                    // DAV-284: HR is a second measurement of the same threshold, not a
                    // separate metric -- shown paired, never fabricated when Zepp's
                    // estimate for a given run didn't include it.
                    secondary = overview.latestLactateThresholdHrBpm?.let { "%.0f bpm".format(it) } ?: "HR unavailable",
                ),
            )
            Text(
                "Zepp's own estimate, recomputed per qualifying run.",
                style = TextStyle(fontFamily = Inter, fontSize = 12.5.sp),
                color = FT.TextSecondary,
            )
        }
    }
    PerformanceTrendChart(
        series = when (selected) {
            PerformanceMetric.VO2MAX -> overview.vo2MaxSeries
            PerformanceMetric.LACTATE_THRESHOLD -> overview.lactateThresholdPaceSeries
        },
        timeframe = timeframe,
        valueFormat = when (selected) {
            PerformanceMetric.VO2MAX -> { v -> "%.1f".format(v) }
            PerformanceMetric.LACTATE_THRESHOLD -> ::formatPace
        },
    )
}

// DAV-80 / DAV-152 / DAV-115. Raw readings are the noisiest series so they
// get the dimmest line; the 28-day average gets the emerald primary-signal
// accent (DAV-99). Generalized to any (date, value) series -- VO2max and
// lactate threshold pace both plot through this now, no metric-specific copy.
@Composable
private fun PerformanceTrendChart(
    series: List<Pair<LocalDate, Double>>,
    timeframe: PerformanceTimeframe,
    valueFormat: (Double) -> String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (series.size < 2) {
            Text(
                "Not enough readings yet to plot a trend.",
                style = TextStyle(fontFamily = Inter, fontSize = 12.5.sp),
                color = FT.TextSecondary,
                modifier = Modifier.padding(vertical = 10.dp),
            )
            return@Column
        }
        val trendData = preparePerformanceTrendData(series, timeframe)
        if (trendData.raw.size >= 2) {
            DateTrendLine(
                series = listOf(
                    TrendSeries(trendData.raw, FT.TextMuted, "RAW"),
                    TrendSeries(trendData.avg7d, FT.Info, "7D"),
                    TrendSeries(trendData.avg28d, FT.Emerald, "28D"),
                ),
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                valueFormat = valueFormat,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                LegendItem("RAW", FT.TextMuted)
                LegendItem("7D AVG", FT.Info)
                LegendItem("28D AVG", FT.Emerald)
            }
        } else {
            Text(
                "Not enough readings in the last ${timeframe.label.lowercase()} to plot a trend.",
                style = TextStyle(fontFamily = Inter, fontSize = 12.5.sp),
                color = FT.TextSecondary,
                modifier = Modifier.padding(vertical = 10.dp),
            )
        }
    }
}

@Composable
private fun LegendItem(label: String, color: androidx.compose.ui.graphics.Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(
            modifier = Modifier
                .padding(top = 1.dp)
                .height(3.dp)
                .width(12.dp)
                .background(color),
        )
        Text(label, style = TextStyle(fontFamily = RobotoMono, fontSize = 10.5.sp), color = FT.TextSecondary)
    }
}

// DAV-108: label is unweighted (always a short fixed phrase) so it never
// shrinks; value takes the rest of the row via weight(1f) so a long value
// wraps within its own bounded width and stays right-aligned instead of
// colliding with the label.
@Composable
private fun StatLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = TextStyle(fontFamily = Inter, fontSize = 14.5.sp), color = FT.TextSecondary)
        Text(
            value,
            style = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.Medium, fontSize = 14.5.sp),
            color = FT.TextPrimary,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
    }
}

private fun formatPace(minPerKm: Double): String {
    val min = minPerKm.toInt()
    val sec = ((minPerKm - min) * 60).toInt()
    return "%d:%02d /km".format(min, sec)
}
