package com.bioscan.fieldterminal.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bioscan.fieldterminal.data.SessionDetailRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.model.ExerciseSessionDetailRow
import com.bioscan.fieldterminal.domain.SessionDetail
import com.bioscan.fieldterminal.domain.TimePoint
import com.bioscan.fieldterminal.ui.components.Card
import com.bioscan.fieldterminal.ui.theme.FieldColors
import com.bioscan.fieldterminal.ui.theme.FieldTextStyles
import com.bioscan.fieldterminal.ui.theme.JetBrainsMono
import com.bioscan.fieldterminal.ui.theme.Saira
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// Phase G4. The app's first pushed detail route (see ui/nav/
// FieldTerminalNavHost.kt) -- reached from a Log tab Exercise entry's
// DETAIL action. loadHeader() reads real aggregates from Supabase;
// loadTimeSeries() reads fresh from Health Connect on demand, only when
// this screen actually opens, nothing persisted (same principle as Step
// 14's GPX route). Deliberately raw/unstyled per this phase's own scope --
// real combined charts are Phase G5, once this on-demand read path is
// proven working.
@Composable
fun SessionDetailScreen(sessionId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    var header by remember { mutableStateOf<ExerciseSessionDetailRow?>(null) }
    var headerError by remember { mutableStateOf<String?>(null) }
    var loadingHeader by remember { mutableStateOf(true) }
    var detail by remember { mutableStateOf<SessionDetail?>(null) }
    var detailError by remember { mutableStateOf<String?>(null) }
    var loadingDetail by remember { mutableStateOf(false) }

    LaunchedEffect(sessionId) {
        val repo = SessionDetailRepository(context, SupabaseClientProvider.client)
        val row = try {
            repo.loadHeader(sessionId)
        } catch (e: Exception) {
            headerError = e.message ?: "Couldn't load this session."
            null
        }
        header = row
        loadingHeader = false

        val recordId = row?.healthConnectRecordId
        if (recordId != null) {
            loadingDetail = true
            try {
                val start = OffsetDateTime.parse(row.startTime).toInstant()
                val end = OffsetDateTime.parse(row.endTime).toInstant()
                detail = repo.loadTimeSeries(start, end)
            } catch (e: Exception) {
                detailError = e.message ?: "Couldn't load time-series detail."
            } finally {
                loadingDetail = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(FieldColors.Ground)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "BACK",
                style = FieldTextStyles.subTabLabel,
                color = FieldColors.InkMuted,
                modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onBack),
            )
            Text(
                header?.type?.replaceFirstChar { it.uppercase() } ?: "SESSION",
                style = FieldTextStyles.headerTitle,
                color = FieldColors.Amber,
            )
        }

        when {
            loadingHeader -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = FieldColors.Amber)
            }
            header == null -> Box(Modifier.fillMaxSize().padding(22.dp), contentAlignment = Alignment.Center) {
                Text(headerError ?: "Session not found.", style = FieldTextStyles.placeholderBody, color = FieldColors.InkMuted)
            }
            else -> {
                val h = header!!
                val d = detail
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    SummaryCard(h)

                    when {
                        h.healthConnectRecordId == null -> Text(
                            "No time-series available for sessions logged before Health Connect.",
                            style = TextStyle(fontFamily = Saira, fontSize = 13.5.sp),
                            color = FieldColors.InkMuted,
                        )
                        loadingDetail -> Box(Modifier.fillMaxWidth().padding(vertical = 30.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = FieldColors.Amber)
                        }
                        detailError != null -> Text(
                            "Couldn't load time-series detail (${detailError}).",
                            style = TextStyle(fontFamily = Saira, fontSize = 13.5.sp),
                            color = FieldColors.InkMuted,
                        )
                        d != null -> {
                            SeriesSummaryCard("HEART RATE", d.heartRate, "bpm")
                            SeriesSummaryCard("SPEED", d.speedKmh, "km/h")
                            SeriesSummaryCard("POWER", d.powerW, "W")
                        }
                    }

                    Box(Modifier.fillMaxWidth().padding(vertical = 16.dp))
                }
            }
        }
    }
}

@Composable
private fun SummaryCard(header: ExerciseSessionDetailRow) {
    val start = try {
        OffsetDateTime.parse(header.startTime).toLocalDateTime()
            .format(DateTimeFormatter.ofPattern("EEE d MMM · HH:mm"))
    } catch (e: Exception) {
        header.startTime
    }

    Card(title = "SUMMARY") {
        StatLine("When", start)
        header.durationMin?.let { StatLine("Duration", formatDuration(it)) }
        header.distanceKm?.let { StatLine("Distance", "%.2f km".format(it)) }
        header.avgHr?.let { StatLine("Avg heart rate", "${it.toInt()} bpm") }
        header.maxHr?.let { StatLine("Max heart rate", "${it.toInt()} bpm") }
        header.elevationGainM?.let { StatLine("Elevation gain", "${it.toInt()} m") }
        header.avgPowerW?.let { StatLine("Avg power", "${it.toInt()} W") }
        header.avgSpeedKmh?.let { StatLine("Avg speed", "%.1f km/h".format(it)) }
        header.caloriesActive?.let { StatLine("Active calories", "${it.toInt()} kcal") }
        header.caloriesTotal?.let { StatLine("Total calories", "${it.toInt()} kcal") }
        header.rpe?.let { StatLine("RPE", "$it/10") }
        header.notes?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = TextStyle(fontFamily = Saira, fontSize = 13.5.sp), color = FieldColors.InkMuted)
        }
    }
}

// Raw/unstyled on purpose -- proving the on-demand read works with real
// numbers is this phase's job; real line charts are Phase G5.
@Composable
private fun SeriesSummaryCard(title: String, points: List<TimePoint>, unit: String) {
    Card(title = title) {
        if (points.isEmpty()) {
            Text("No samples in this session's time window.", style = TextStyle(fontFamily = Saira, fontSize = 13.5.sp), color = FieldColors.InkMuted)
        } else {
            val values = points.map { it.value }
            StatLine("Samples", "${points.size}")
            StatLine("Average", "%.1f %s".format(values.average(), unit))
            StatLine("Min / Max", "%.1f / %.1f %s".format(values.min(), values.max(), unit))
        }
    }
}

@Composable
private fun StatLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = TextStyle(fontFamily = Saira, fontSize = 14.5.sp), color = FieldColors.InkMuted)
        Text(value, style = TextStyle(fontFamily = JetBrainsMono, fontSize = 14.5.sp), color = FieldColors.Ink)
    }
}

private fun formatDuration(totalMinutes: Double): String {
    val h = (totalMinutes / 60).toInt()
    val m = (totalMinutes % 60).toInt()
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
