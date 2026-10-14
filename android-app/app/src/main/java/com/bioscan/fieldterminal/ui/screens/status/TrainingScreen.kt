package com.bioscan.fieldterminal.ui.screens.status

import com.bioscan.fieldterminal.ui.components.launchSafe
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
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
import com.bioscan.fieldterminal.domain.sumDistanceKmSince
import com.bioscan.fieldterminal.ui.components.ComparisonStrip
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTMetricValue
import com.bioscan.fieldterminal.ui.components.SegmentedToggle
import com.bioscan.fieldterminal.ui.components.FTMetricRow
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
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
fun TrainingScreen(
    timeframe: PerformanceTimeframe,
    onOpenSessionDetail: (Long) -> Unit = {},
    onOpenRunning: () -> Unit = {},
    onOpenStrength: () -> Unit = {},
) {
    var overview by remember { mutableStateOf<TrainingOverview?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        try {
            overview = TrainingRepository(SupabaseClientProvider.client).loadOverview()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Unknown error"
        }
        isLoading = false
    }

    // Tags every copy (HC/manual/Zepp) of each run as trail, then reloads so the
    // RUNNING card's trail count/elevation and the suspected list update.
    val onMarkTrail: (List<Long>) -> Unit = { ids ->
        scope.launchSafe {
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
            Text("Failed to load: $error", style = FTType.BodySmall, color = FT.Critical)
        }
        else -> TrainingContent(overview!!, timeframe, onOpenSessionDetail, onMarkTrail, onOpenRunning, onOpenStrength)
    }
}

@Composable
private fun TrainingContent(
    overview: TrainingOverview,
    timeframe: PerformanceTimeframe,
    onOpenSessionDetail: (Long) -> Unit,
    onMarkTrail: (List<Long>) -> Unit,
    onOpenRunning: () -> Unit,
    onOpenStrength: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        // 28/9: PERFORMANCE and RUN EFFICIENCY folded into one RUNNING card
        // (previously three separate top-level cards) per direct user
        // request -- both are running-related, and neither is per-session so
        // they read fine as sub-sections rather than their own cards.
        RunningCard(overview, timeframe, onMarkTrail, onOpenRunning)

        FTCard(title = "STRENGTH") {
            if (overview.hasAnyStrength) {
                FTMetricValue(
                    DisplayValue(
                        primary = "${overview.strengthSessionsThisWeek}",
                        unit = if (overview.strengthSessionsThisWeek == 1) "SESSION" else "SESSIONS",
                    ),
                )
                FTMetricRow("This week", "${overview.strengthMinutesThisWeek} min")
                SkillLink(summary = "STANDARDS · E1RM · VOLUME · PRs", label = "OPEN STRENGTH", onClick = onOpenStrength)
            } else {
                Text(
                    text = "No strength sessions synced yet.",
                    style = FTType.RowTitle,
                    color = FT.TextSecondary,
                )
                Text(
                    text = "Health Connect is this app's real data source for strength training now — " +
                        "log a workout with any Health-Connect-aware app on your phone and it'll show up here after the next sync.",
                    style = FTType.Caption,
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
            Text(session.type.replaceFirstChar { it.uppercase() }, style = FTType.Body, color = FT.TextPrimary)
            Text(when_, style = FTType.Caption, color = FT.TextSecondary)
        }
        Text(detail, style = FTType.Value, color = FT.TextSecondary)
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
// No volume bar: a fixed km ceiling was arbitrary at any period, so the hero
// value stands alone until a real personal baseline backs a comparison.
@Composable
private fun RunningCard(overview: TrainingOverview, timeframe: PerformanceTimeframe, onMarkTrail: (List<Long>) -> Unit, onOpenRunning: () -> Unit) {
    var period by remember { mutableStateOf(RunningPeriod.Week) }
    val today = LocalDate.now()

    FTCard(title = "RUNNING") {
        if (!overview.hasAnyRunning) {
            Text("No running sessions logged yet.", style = FTType.Body, color = FT.TextSecondary)
        } else {
            val totalKm = sumDistanceKmSince(overview.runningSessions, today, period.days)
            val avgPace = averagePaceMinPerKmSince(overview.runningSessions, today, period.days)
            val trailRuns = overview.runningSessions.filter {
                it.details.routeType == "trail" &&
                    ChronoUnit.DAYS.between(OffsetDateTime.parse(it.startTime).toLocalDate(), today) < period.days
            }

            SegmentedToggle(options = RunningPeriod.entries, selected = period, labelOf = { it.label }, onSelect = { period = it })
            FTMetricValue(DisplayValue(primary = "%.1f".format(totalKm), unit = "KM"))

            avgPace?.let { FTMetricRow("Avg pace", formatPace(it)) }

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
                FTMetricRow("Longest run (all-time)", "%.2f km".format(longest))
            }

            // DAV-144. No per-session list exists on this tab (see
            // docs/trail-intelligence/05-trail-metrics-ui-presentation.md) --
            // this is the same rollup shape every other figure here uses, only
            // shown when a real trail run happened in the selected period.
            if (trailRuns.isNotEmpty()) {
                FTMetricRow("Trail runs", "${trailRuns.size}")
                trailRuns.mapNotNull { it.elevationGainM }.takeIf { it.isNotEmpty() }?.sum()?.let { gain ->
                    FTMetricRow("Elevation gained", "${gain.toInt()} m")
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
        // The deep charts (VO2max / lactate threshold, efficiency, pace, form, weekly volume) moved to the User tab's
        // Running page; this card keeps the weekly rollup and links there.
        SkillLink(
            summary = listOfNotNull(
                overview.latestVo2Max?.let { "VO2MAX ${"%.0f".format(it)}" },
                overview.efficiencyPoints.lastOrNull()?.second?.let { "EF ${"%.2f".format(it)}" },
            ).joinToString("  ·  ").ifBlank { "VO2MAX · PACE · EFFICIENCY · FORM" },
            label = "OPEN RUNNING",
            onClick = onOpenRunning,
        )
    }
}

// One-line pointer from a rollup card to its deep page on the User tab.
@Composable
private fun SkillLink(summary: String, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(top = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(summary, style = FTType.MonoCaption, color = FT.TextSecondary, modifier = Modifier.weight(1f))
        Text("$label ›", style = FTType.Label, color = FT.DomainTraining)
    }
}

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
            style = FTType.LabelCaps,
            color = FT.Warning,
        )
        Text(if (expanded) "HIDE" else "REVIEW", style = FTType.MonoCaption, color = FT.TextSecondary)
    }
    if (!expanded) return
    Text(
        "Logged as plain runs, but Zepp called them trail or they climb like one. Nothing changes until you confirm.",
        style = FTType.Caption,
        color = FT.TextSecondary,
    )
    suspected.forEach { s ->
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                val date = OffsetDateTime.parse(s.row.startTime).toLocalDate().format(DateTimeFormatter.ofPattern("EEE d MMM"))
                Text("$date · ${"%.1f".format(s.row.distanceKm ?: 0.0)} km", style = FTType.BodySmall, color = FT.TextPrimary)
                Text(s.reason, style = FTType.Micro, color = FT.TextSecondary)
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
        Text(label, style = FTType.Label, color = FT.DomainTraining)
    }
}

private fun formatPace(minPerKm: Double): String {
    val min = minPerKm.toInt()
    val sec = ((minPerKm - min) * 60).toInt()
    return "%d:%02d /km".format(min, sec)
}
