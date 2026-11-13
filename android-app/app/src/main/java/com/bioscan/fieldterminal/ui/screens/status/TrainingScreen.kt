package com.bioscan.fieldterminal.ui.screens.status

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.TrainingOverview
import com.bioscan.fieldterminal.data.TrainingRepository
import com.bioscan.fieldterminal.data.model.ExerciseSessionRow
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.SkeletonSections
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.components.rememberCacheVersion
import com.bioscan.fieldterminal.ui.theme.FTType
import kotlinx.coroutines.CancellationException
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

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

    val cacheVersion = rememberCacheVersion()
    LaunchedEffect(cacheVersion) {
        try {
            overview = TrainingRepository(SupabaseClientProvider.client).loadOverview()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: "Unknown error"
        }
        isLoading = false
    }

    val o = overview
    when {
        isLoading -> SkeletonSections()
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
    var shown by rememberSaveable { mutableIntStateOf(SESSIONS_PAGE) }
    FTCard(title = "ALL SESSIONS", modifier = modifier) {
        sessions.take(shown).forEach { session -> SessionRow(session, onClick = { onOpenSessionDetail(session.id) }) }
        val left = sessions.size - shown
        if (left > 0) {
            FTButton(
                "LOAD ${minOf(left, SESSIONS_PAGE)} MORE · $left LEFT",
                emphasis = FTButtonEmphasis.Tertiary,
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
