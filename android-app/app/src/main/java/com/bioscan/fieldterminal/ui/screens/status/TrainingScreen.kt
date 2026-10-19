package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import com.bioscan.fieldterminal.data.model.ExerciseSessionRow
import com.bioscan.fieldterminal.ui.components.ComparisonStrip
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTMetricRow
import com.bioscan.fieldterminal.ui.components.FTMetricValue
import com.bioscan.fieldterminal.ui.components.GlanceAnchors
import com.bioscan.fieldterminal.ui.components.GlanceChip
import com.bioscan.fieldterminal.ui.components.SegmentedToggle
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.components.glanceAnchor
import com.bioscan.fieldterminal.ui.components.launchSafe
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

// Step 7 (Phase C), generalized in Phase G3 from runs-only to any exercise type via `exercise_sessions` (see
// data/TrainingRepository.kt).
// App restructure: the weekly RUNNING and STRENGTH rollup cards gave way to Training's RUNNING and STRENGTH tabs (the
// former User-tab skill pages). What is left here is the SESSIONS tab and the suspected-trail-run review, which RUNNING
// shows on top.
@Composable
private fun TrainingOverviewLoaded(quiet: Boolean = false, content: @Composable (TrainingOverview, (List<Long>) -> Unit) -> Unit) {
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

    // Tags every copy (HC/manual/Zepp) of each run as trail, then reloads so the suspected list updates.
    val onMarkTrail: (List<Long>) -> Unit = { ids ->
        scope.launchSafe {
            val repo = AddEntryRepository(SupabaseClientProvider.client)
            ids.forEach { repo.updateRouteType(it, "trail") }
            overview = TrainingRepository(SupabaseClientProvider.client).loadOverview()
        }
    }

    val o = overview
    when {
        quiet && o == null -> Unit
        isLoading -> Box(Modifier.fillMaxWidth().padding(vertical = 60.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = FT.DomainTraining)
        }
        error != null || o == null -> Box(Modifier.fillMaxWidth().padding(vertical = 60.dp), contentAlignment = Alignment.Center) {
            Text("Failed to load: ${error ?: "no data"}", style = FTType.BodySmall, color = FT.Critical)
        }
        else -> content(o, onMarkTrail)
    }
}

// Training › SESSIONS: every session, newest first, ten at a time (it listed twenty at once on the Load tab).
@Composable
fun TrainingSessionsTab(onOpenSessionDetail: (Long) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        TrainingOverviewLoaded { overview, _ -> SessionListCard(overview.recentSessions, onOpenSessionDetail) }
    }
}

// Training › RUNNING's first card, only when a run looks like trail but is not tagged so; nothing while it loads.
@Composable
fun SuspectedTrailRunsCard() {
    TrainingOverviewLoaded(quiet = true) { overview, onMarkTrail ->
        if (overview.suspectedTrailRuns.isNotEmpty()) {
            FTCard(title = "TRAIL RUNS TO CONFIRM") { SuspectedTrailRuns(overview.suspectedTrailRuns, onMarkTrail) }
        }
    }
}

const val SESSIONS_PAGE = 10

@Composable
private fun SessionListCard(sessions: List<ExerciseSessionRow>, onOpenSessionDetail: (Long) -> Unit, modifier: Modifier = Modifier) {
    if (sessions.isEmpty()) {
        FTCard(title = "ALL SESSIONS", modifier = modifier) {
            Text("No sessions synced or logged yet.", style = FTType.BodySmall, color = FT.TextSecondary)
        }
        return
    }
    var shown by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableIntStateOf(SESSIONS_PAGE) }
    FTCard(title = "ALL SESSIONS", modifier = modifier) {
        sessions.take(shown).forEach { session -> SessionRow(session, onClick = { onOpenSessionDetail(session.id) }) }
        val left = sessions.size - shown
        if (left > 0) {
            com.bioscan.fieldterminal.ui.components.FTButton(
                "LOAD ${minOf(left, SESSIONS_PAGE)} MORE · $left LEFT",
                emphasis = com.bioscan.fieldterminal.ui.components.FTButtonEmphasis.Tertiary,
            ) { shown += SESSIONS_PAGE }
        }
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
            .ftClickable(onClick = onClick)
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

// Untagged runs that look like trail runs (Zepp said trail, or a steep
// climb/km) -- collapsed by default, one tap to confirm each or all.
@Composable
private fun SuspectedTrailRuns(suspected: List<SuspectedTrailRun>, onMark: (List<Long>) -> Unit) {
    if (suspected.isEmpty()) return
    var expanded by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth()
            .ftClickable { expanded = !expanded }
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
            .ftClickable(onClick = onClick, shape = shape)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = FTType.Label, color = FT.DomainTraining)
    }
}
