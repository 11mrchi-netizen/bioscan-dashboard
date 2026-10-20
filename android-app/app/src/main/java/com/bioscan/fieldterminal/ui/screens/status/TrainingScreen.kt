package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

// Step 7 (Phase C), generalized in Phase G3 from runs-only to any exercise type via `exercise_sessions` (see
// data/TrainingRepository.kt).
// App restructure: the weekly RUNNING and STRENGTH rollup cards gave way to Training's RUNNING and STRENGTH tabs (the
// former User-tab skill pages). What is left here is the SESSIONS tab. (The suspected-trail-run review is gone: trail runs
// are recognised from their tag or Zepp's trail sport, which the sync now writes as the tag.)
@Composable
private fun TrainingOverviewLoaded(content: @Composable (TrainingOverview) -> Unit) {
    var overview by remember { mutableStateOf<TrainingOverview?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

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

    val o = overview
    when {
        isLoading -> Box(Modifier.fillMaxWidth().padding(vertical = 60.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = FT.DomainTraining)
        }
        error != null || o == null -> Box(Modifier.fillMaxWidth().padding(vertical = 60.dp), contentAlignment = Alignment.Center) {
            Text("Failed to load: ${error ?: "no data"}", style = FTType.BodySmall, color = FT.Critical)
        }
        else -> content(o)
    }
}

// Training › SESSIONS: every session, newest first, ten at a time (it listed twenty at once on the Load tab).
@Composable
fun TrainingSessionsTab(onOpenSessionDetail: (Long) -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        TrainingOverviewLoaded { overview -> SessionListCard(overview.recentSessions, onOpenSessionDetail) }
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
