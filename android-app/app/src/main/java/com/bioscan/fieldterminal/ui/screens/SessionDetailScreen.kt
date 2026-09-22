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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.bioscan.fieldterminal.domain.SessionSplit
import com.bioscan.fieldterminal.domain.TimePoint
import com.bioscan.fieldterminal.domain.computeKmSplits
import com.bioscan.fieldterminal.ui.components.AmberButton
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.LineChart
import com.bioscan.fieldterminal.ui.components.RouteMiniMap
import com.bioscan.fieldterminal.ui.components.SubTabRow
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import com.bioscan.fieldterminal.ui.theme.Inter
import com.bioscan.fieldterminal.ui.theme.RobotoMono
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
//
// DAV-106: recomposed around decision-useful information -- summary, then
// the route as the spatial view, then one switchable performance signal
// instead of four permanently-stacked charts, then real per-km splits --
// and migrated to the Futuristic Material contract (DAV-105's shared
// FTCard). The chip-row selector reuses SubTabRow verbatim, the same
// "shared compact selector treatment" Fuel/Heart/Labs already use for their
// own sub-tabs, rather than a new selector component.
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

    Column(modifier = Modifier.fillMaxSize().background(FT.Base)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "BACK",
                style = TextStyle(fontFamily = RobotoMono, fontSize = 12.sp, fontWeight = FontWeight.Bold),
                color = FT.TextMuted,
                modifier = Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onBack),
            )
            Text(
                header?.type?.replaceFirstChar { it.uppercase() } ?: "SESSION",
                style = TextStyle(fontFamily = Inter, fontSize = 20.sp, fontWeight = FontWeight.Bold),
                color = FT.TextPrimary,
            )
        }

        when {
            loadingHeader -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = FT.Emerald)
            }
            header == null -> Box(Modifier.fillMaxSize().padding(22.dp), contentAlignment = Alignment.Center) {
                Text(headerError ?: "Session not found.", style = TextStyle(fontFamily = Inter, fontSize = 14.sp), color = FT.TextSecondary)
            }
            else -> {
                val h = header!!
                val d = detail
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    SummaryCard(h)

                    val recordId = h.healthConnectRecordId
                    if (recordId != null) {
                        val pts = routePoints
                        when {
                            pts != null && pts.size >= 2 -> FTCard(title = "ROUTE") {
                                if (cartoKey != null) {
                                    RouteMiniMap(points = pts, cartoKey = cartoKey)
                                } else {
                                    Text(
                                        "Add a CARTO API key in Settings to see the route map.",
                                        style = TextStyle(fontFamily = Inter, fontSize = 13.5.sp),
                                        color = FT.TextSecondary,
                                    )
                                }
                            }
                            loadingRoute -> Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = FT.Emerald)
                            }
                            routeAvailability is RouteAvailability.ConsentRequired -> FTCard(title = "ROUTE") {
                                Text(
                                    "This session has a recorded route. Health Connect requires a one-time, per-session permission to view it.",
                                    style = TextStyle(fontFamily = Inter, fontSize = 13.5.sp),
                                    color = FT.TextSecondary,
                                )
                                AmberButton(label = "VIEW ROUTE") { routeLauncher.launch(recordId) }
                            }
                            routeError != null -> Text(
                                "Couldn't check for a recorded route (${routeError}).",
                                style = TextStyle(fontFamily = Inter, fontSize = 13.5.sp),
                                color = FT.TextSecondary,
                            )
                        }
                    }

                    when {
                        h.healthConnectRecordId == null -> Text(
                            "No time-series available for sessions logged before Health Connect.",
                            style = TextStyle(fontFamily = Inter, fontSize = 13.5.sp),
                            color = FT.TextSecondary,
                        )
                        loadingDetail -> Box(Modifier.fillMaxWidth().padding(vertical = 30.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = FT.Emerald)
                        }
                        detailError != null -> Text(
                            "Couldn't load time-series detail (${detailError}).",
                            style = TextStyle(fontFamily = Inter, fontSize = 13.5.sp),
                            color = FT.TextSecondary,
                        )
                        d != null -> {
                            PerformanceChartCard(d)
                            SplitsCard(computeKmSplits(d.distanceKm, d.heartRate))
                        }
                    }

                    Box(Modifier.fillMaxWidth().padding(vertical = 16.dp))
                }
            }
        }
    }
}

private enum class PerfSignal(val label: String, val unit: String, val color: Color) {
    HR("HR", "bpm", FT.Critical),
    PACE("PACE", "min/km", FT.Emerald),
    POWER("POWER", "W", FT.Warning),
    CAL("CAL", "kcal", FT.Info),
}

// One chart, switchable rather than four stacked ones -- only signals this
// session actually recorded appear as options. PACE is derived from the
// same speed samples SPEED used to plot directly (min/km = 60/kmh), matching
// this app's existing averagePaceMinPerKmSince convention (domain/
// Training.kt) rather than showing raw km/h, since the ticket calls for PACE.
@Composable
private fun PerformanceChartCard(d: SessionDetail) {
    val pace = remember(d.speedKmh) { d.speedKmh.mapNotNull { p -> if (p.value > 0) TimePoint(p.offsetSeconds, 60.0 / p.value) else null } }
    val seriesBySignal = mapOf(
        PerfSignal.HR to d.heartRate,
        PerfSignal.PACE to pace,
        PerfSignal.POWER to d.powerW,
        PerfSignal.CAL to d.caloriesKcal,
    )
    val available = PerfSignal.entries.filter { seriesBySignal[it]?.isNotEmpty() == true }
    if (available.isEmpty()) return
    var selected by remember(d) { mutableStateOf(available.first()) }
    if (selected !in available) selected = available.first()

    FTCard(title = "PERFORMANCE") {
        SubTabRow(items = available, selected = selected, label = { it.label }, onSelect = { selected = it })
        val points = seriesBySignal.getValue(selected)
        val values = points.map { it.value }
        StatLine("Samples", "${points.size}")
        StatLine("Average", "%.1f %s".format(values.average(), selected.unit))
        StatLine("Min / Max", "%.1f / %.1f %s".format(values.min(), values.max(), selected.unit))
        LineChart(points = points, color = selected.color, filled = selected == PerfSignal.CAL, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun SplitsCard(splits: List<SessionSplit>) {
    if (splits.isEmpty()) return
    FTCard(title = "SPLITS") {
        splits.forEach { split ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("KM ${split.km}", style = TextStyle(fontFamily = Inter, fontSize = 14.sp), color = FT.TextSecondary)
                Text(
                    formatSplitPace(split.durationSec) + (split.avgHr?.let { "  ·  %.0f bpm".format(it) } ?: ""),
                    style = TextStyle(fontFamily = RobotoMono, fontSize = 14.sp),
                    color = FT.TextPrimary,
                )
            }
        }
    }
}

private fun formatSplitPace(durationSec: Long): String {
    val mins = durationSec / 60
    val secs = durationSec % 60
    return "%d:%02d /km".format(mins, secs)
}

@Composable
private fun SummaryCard(header: ExerciseSessionDetailRow) {
    val start = try {
        OffsetDateTime.parse(header.startTime).toLocalDateTime()
            .format(DateTimeFormatter.ofPattern("EEE d MMM · HH:mm"))
    } catch (e: Exception) {
        header.startTime
    }

    FTCard(title = "SUMMARY") {
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
            Text(it, style = TextStyle(fontFamily = Inter, fontSize = 13.5.sp), color = FT.TextSecondary)
        }
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
            style = TextStyle(fontFamily = RobotoMono, fontSize = 14.5.sp),
            color = FT.TextPrimary,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
    }
}

private fun formatDuration(totalMinutes: Double): String {
    val h = (totalMinutes / 60).toInt()
    val m = (totalMinutes % 60).toInt()
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
