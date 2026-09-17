package com.bioscan.fieldterminal.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.health.connect.client.contracts.ExerciseRouteRequestContract
import com.bioscan.fieldterminal.data.MapSettingsStore
import com.bioscan.fieldterminal.data.SessionDetailRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.model.ExerciseSessionDetailRow
import com.bioscan.fieldterminal.data.toRoutePoints
import com.bioscan.fieldterminal.domain.RouteAvailability
import com.bioscan.fieldterminal.domain.RoutePoint
import com.bioscan.fieldterminal.domain.SessionDetail
import com.bioscan.fieldterminal.domain.TimePoint
import com.bioscan.fieldterminal.ui.components.AmberButton
import com.bioscan.fieldterminal.ui.components.Card
import com.bioscan.fieldterminal.ui.components.LineChart
import com.bioscan.fieldterminal.ui.components.RouteMiniMap
import com.bioscan.fieldterminal.ui.theme.FieldColors
import com.bioscan.fieldterminal.ui.theme.FieldTextStyles
import com.bioscan.fieldterminal.ui.theme.JetBrainsMono
import com.bioscan.fieldterminal.ui.theme.Saira
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// Phase G4/G5. The app's first pushed detail route (see ui/nav/
// FieldTerminalNavHost.kt) -- reached from a Log tab Exercise entry's
// DETAIL action. loadHeader() reads real aggregates from Supabase;
// loadTimeSeries() reads fresh from Health Connect on demand, only when
// this screen actually opens, nothing persisted (same principle as Step
// 14's GPX route). Route/elevation (Phase G5) load separately from the other
// series since a route needs its own per-session Health Connect consent --
// see routeLauncher below and domain/SessionRoute.kt.
@Composable
fun SessionDetailScreen(sessionId: Long, onBack: () -> Unit) {
    val context = LocalContext.current
    var header by remember { mutableStateOf<ExerciseSessionDetailRow?>(null) }
    var headerError by remember { mutableStateOf<String?>(null) }
    var loadingHeader by remember { mutableStateOf(true) }
    var detail by remember { mutableStateOf<SessionDetail?>(null) }
    var detailError by remember { mutableStateOf<String?>(null) }
    var loadingDetail by remember { mutableStateOf(false) }

    var sessionStart by remember { mutableStateOf<Instant?>(null) }
    var loadingRoute by remember { mutableStateOf(false) }
    var routeAvailability by remember { mutableStateOf<RouteAvailability?>(null) }
    var routePoints by remember { mutableStateOf<List<RoutePoint>?>(null) }
    var routeError by remember { mutableStateOf<String?>(null) }
    val cartoKey = remember { MapSettingsStore.getCartoKey(context) }

    // Health Connect's own consent screen for this one session's route --
    // see domain/SessionRoute.kt's RouteAvailability.ConsentRequired. Only
    // launched from the VIEW ROUTE button below; a null result means the
    // user backed out or declined, which is a real, valid outcome, not an
    // error.
    val routeLauncher = rememberLauncherForActivityResult(ExerciseRouteRequestContract()) { route ->
        val start = sessionStart
        if (route != null && start != null) {
            routePoints = toRoutePoints(route, start)
        }
    }

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
            // row.startTime/endTime store this app's usual local wall-clock
            // reading (see HealthConnectExerciseSyncRepository's own comment
            // on the convention), not the real Health Connect instant --
            // reconstruct the real instant via the device's zone rather than
            // trusting the string's own offset, since re-querying Health
            // Connect below needs the genuine moment in time, not the label.
            val zone = ZoneId.systemDefault()
            val start = OffsetDateTime.parse(row.startTime).toLocalDateTime().atZone(zone).toInstant()
            val end = OffsetDateTime.parse(row.endTime).toLocalDateTime().atZone(zone).toInstant()
            sessionStart = start

            loadingDetail = true
            try {
                detail = repo.loadTimeSeries(start, end)
            } catch (e: Exception) {
                detailError = e.message ?: "Couldn't load time-series detail."
            } finally {
                loadingDetail = false
            }

            loadingRoute = true
            try {
                val availability = repo.checkRouteAvailability(recordId, start)
                routeAvailability = availability
                if (availability is RouteAvailability.Available) routePoints = availability.points
            } catch (e: Exception) {
                routeError = e.message ?: "Couldn't check for a recorded route."
            } finally {
                loadingRoute = false
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
                            SeriesSummaryCard("HEART RATE", d.heartRate, "bpm", FieldColors.Red)
                            SeriesSummaryCard("SPEED", d.speedKmh, "km/h", FieldColors.Azure)
                            SeriesSummaryCard("POWER", d.powerW, "W", FieldColors.Amber)
                            if (d.caloriesKcal.isNotEmpty()) {
                                SeriesSummaryCard("CALORIES (CUMULATIVE)", d.caloriesKcal, "kcal", FieldColors.Green, filled = true)
                            }
                        }
                    }

                    val recordId = h.healthConnectRecordId
                    if (recordId != null) {
                        val pts = routePoints
                        when {
                            pts != null && pts.size >= 2 -> {
                                val elevation = pts.mapNotNull { p -> p.elevationM?.let { TimePoint(p.offsetSeconds, it) } }
                                if (elevation.size >= 2) {
                                    SeriesSummaryCard("ELEVATION", elevation, "m", FieldColors.Sand, filled = true)
                                }
                                Card(title = "ROUTE MAP") {
                                    if (cartoKey != null) {
                                        RouteMiniMap(points = pts, cartoKey = cartoKey)
                                    } else {
                                        Text(
                                            "Add a CARTO API key in Settings to see the route map.",
                                            style = TextStyle(fontFamily = Saira, fontSize = 13.5.sp),
                                            color = FieldColors.InkMuted,
                                        )
                                    }
                                }
                            }
                            loadingRoute -> Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = FieldColors.Amber)
                            }
                            routeAvailability is RouteAvailability.ConsentRequired -> Card(title = "ROUTE") {
                                Text(
                                    "This session has a recorded route. Health Connect requires a one-time, per-session permission to view it.",
                                    style = TextStyle(fontFamily = Saira, fontSize = 13.5.sp),
                                    color = FieldColors.InkMuted,
                                )
                                AmberButton(label = "VIEW ROUTE") { routeLauncher.launch(recordId) }
                            }
                            routeError != null -> Text(
                                "Couldn't check for a recorded route (${routeError}).",
                                style = TextStyle(fontFamily = Saira, fontSize = 13.5.sp),
                                color = FieldColors.InkMuted,
                            )
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

// Phase G5: the LineChart beneath the stats is real now -- G4 deliberately
// left this raw/unstyled to prove the on-demand read worked with real
// numbers first.
@Composable
private fun SeriesSummaryCard(title: String, points: List<TimePoint>, unit: String, color: Color, filled: Boolean = false) {
    Card(title = title) {
        if (points.isEmpty()) {
            Text("No samples in this session's time window.", style = TextStyle(fontFamily = Saira, fontSize = 13.5.sp), color = FieldColors.InkMuted)
        } else {
            val values = points.map { it.value }
            StatLine("Samples", "${points.size}")
            StatLine("Average", "%.1f %s".format(values.average(), unit))
            StatLine("Min / Max", "%.1f / %.1f %s".format(values.min(), values.max(), unit))
            LineChart(points = points, color = color, filled = filled, modifier = Modifier.padding(top = 4.dp))
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
