package com.bioscan.fieldterminal.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.contracts.ExerciseRouteRequestContract
import com.bioscan.fieldterminal.data.ExerciseLibraryRepository
import com.bioscan.fieldterminal.data.MapSettingsStore
import com.bioscan.fieldterminal.data.PlannedMatch
import com.bioscan.fieldterminal.data.SessionDetailRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.TrainingProgramRepository
import com.bioscan.fieldterminal.data.ZeppStrengthData
import com.bioscan.fieldterminal.data.ZeppWorkoutSummary
import com.bioscan.fieldterminal.data.ZoneAnchorSeries
import com.bioscan.fieldterminal.data.ZoneAnchorsRepository
import com.bioscan.fieldterminal.data.catching
import com.bioscan.fieldterminal.data.model.ExerciseLibraryRow
import com.bioscan.fieldterminal.data.model.ExerciseSessionDetailRow
import com.bioscan.fieldterminal.data.softLoad
import com.bioscan.fieldterminal.data.toRoutePoints
import com.bioscan.fieldterminal.domain.DisplayValue
import com.bioscan.fieldterminal.domain.RouteAvailability
import com.bioscan.fieldterminal.domain.RoutePoint
import com.bioscan.fieldterminal.domain.RunDynamicsMarks
import com.bioscan.fieldterminal.domain.SessionDetail
import com.bioscan.fieldterminal.domain.ZoneMethod
import com.bioscan.fieldterminal.domain.computeKmSplits
import com.bioscan.fieldterminal.domain.findRun
import com.bioscan.fieldterminal.domain.mergePreferZepp
import com.bioscan.fieldterminal.domain.movingMinutes
import com.bioscan.fieldterminal.domain.runDynamicsMarks
import com.bioscan.fieldterminal.domain.sessionAvgSpeedKmh
import com.bioscan.fieldterminal.domain.trail.computeTrailSessionState
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTMetricRow
import com.bioscan.fieldterminal.ui.components.FTMetricValue
import com.bioscan.fieldterminal.ui.components.RouteMiniMap
import com.bioscan.fieldterminal.ui.components.SessionZoneInputs
import com.bioscan.fieldterminal.ui.components.SkeletonSections
import com.bioscan.fieldterminal.ui.components.SubTabRow
import com.bioscan.fieldterminal.ui.components.TileHeader
import com.bioscan.fieldterminal.ui.screens.session.RunDynamicsCard
import com.bioscan.fieldterminal.ui.screens.session.RunSplitsCard
import com.bioscan.fieldterminal.ui.screens.session.RunSummaryCard
import com.bioscan.fieldterminal.ui.screens.session.RunTrailCard
import com.bioscan.fieldterminal.ui.screens.session.formatDuration
import com.bioscan.fieldterminal.ui.screens.session.formatSplitPace
import com.bioscan.fieldterminal.ui.screens.training.PlannedSessionCard
import com.bioscan.fieldterminal.ui.screens.user.UserDataCache
import com.bioscan.fieldterminal.ui.theme.FTType
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// The app's first pushed detail route (see ui/nav/
// FieldTerminalNavHost.kt) -- reached from a Log tab Exercise entry's
// DETAIL action. loadHeader() reads real aggregates from Supabase;
// loadTimeSeries() reads fresh from Health Connect on demand, only when
// this screen actually opens, nothing persisted (same principle as Step
// 14's GPX route). Route/elevation load separately from the other
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
fun SessionDetailScreen(sessionId: Long, onBack: () -> Unit, onOpenShoe: (Long) -> Unit = {}) {
    val context = LocalContext.current
    var header by remember { mutableStateOf<ExerciseSessionDetailRow?>(null) }
    var headerError by remember { mutableStateOf<String?>(null) }
    var loadingHeader by remember { mutableStateOf(true) }
    var detail by remember { mutableStateOf<SessionDetail?>(null) }
    var detailError by remember { mutableStateOf<String?>(null) }
    var loadingDetail by remember { mutableStateOf(false) }
    var zeppSummary by remember { mutableStateOf<ZeppWorkoutSummary?>(null) }
    var zeppStrengthData by remember { mutableStateOf<ZeppStrengthData?>(null) }
    var zoneSeries by remember { mutableStateOf<ZoneAnchorSeries?>(null) }
    var zoneDefault by remember { mutableStateOf(ZoneMethod.AUTO) }
    var planned by remember { mutableStateOf<PlannedMatch?>(null) }
    var runMarks by remember { mutableStateOf<RunDynamicsMarks?>(null) }
    val sessionScope = rememberCoroutineScope()

    var exerciseLibrary by remember { mutableStateOf<List<ExerciseLibraryRow>>(emptyList()) }

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
        // Heart-rate zones: the anchor series (maximum, resting, threshold) and the person's default method, loaded once
        // per screen open; the anchors are taken as of this session's date below.
        zoneSeries = softLoad { ZoneAnchorsRepository(SupabaseClientProvider.client).load() }
        zoneDefault = softLoad { TrainingProgramRepository(SupabaseClientProvider.client).loadZoneMethod() } ?: ZoneMethod.AUTO
        // A run's RUN DYNAMICS marks against usual, from the per-run history the Running page reads (cached there).
        if (row?.type == "run") {
            runMarks = softLoad {
                val rows = UserDataCache.running().historyRows()
                findRun(rows, row.id, OffsetDateTime.parse(row.startTime).toLocalDate(), row.distanceKm)
                    ?.let { runDynamicsMarks(it, rows) }
            }
        }
        // What the training block planned for this workout, if anything (DAV-346).
        if (row != null) {
            planned = catching {
                TrainingProgramRepository(SupabaseClientProvider.client)
                    .loadPlannedFor(sessionId, OffsetDateTime.parse(row.startTime).toLocalDate(), row.type == "strength")
            }.getOrNull()
        }

        // Real anatomical data (exercise_library's primary/secondary_muscles,
        // 876 rows) for StrengthCard's regional breakdown -- replaces the
        // earlier keyword-guess classifier now that domain/RegionalLoad.kt/
        // StrengthLoad.kt's already-built, already-verified pipeline is wired
        // in here instead. Only fetched for a strength session with logged
        // exercises -- no point pulling 876 rows otherwise.
        if (row?.type == "strength" && row.details.exercises?.isNotEmpty() == true) {
            exerciseLibrary = softLoad { ExerciseLibraryRepository(SupabaseClientProvider.client).fetchAll() } ?: exerciseLibrary
        }

        val recordId = row?.healthConnectRecordId

        // row.startTime/endTime store this app's usual local wall-clock
        // reading (see HealthConnectExerciseSyncRepository's own comment
        // on the convention), not the real Health Connect instant --
        // reconstruct the real instant via the device's zone rather than
        // trusting the string's own offset, since re-querying Health
        // Connect below needs the genuine moment in time, not the label.
        // Only meaningful when there's a real Health Connect record; a
        // Zepp-only session (DAV-115, no health_connect_record_id) has
        // nothing here to reconstruct and doesn't need to -- loadZeppDetail()
        // below reads its own already-offset-relative series directly.
        val zone = ZoneId.systemDefault()
        val start = recordId?.let { OffsetDateTime.parse(row.startTime).toLocalDateTime().atZone(zone).toInstant() }
        val end = recordId?.let { OffsetDateTime.parse(row.endTime).toLocalDateTime().atZone(zone).toInstant() }
        if (start != null) sessionStart = start

        // Live check: the route card was always the slowest thing on this
        // screen because it was never actually contending for time --
        // checkRouteAvailability() only started once loadTimeSeries()
        // fully finished. The two calls read unrelated Health Connect
        // record types (route vs. HR/speed/power/distance/calories), so
        // there's no reason for one to wait on the other; running them
        // concurrently is the same reasoning HealthConnectExerciseSyncRepository
        // already applies to its own reads.
        loadingDetail = true
        if (row?.type != "strength") loadingRoute = true
        coroutineScope {
            launch {
                try {
                    val hcDetail = if (start != null && end != null) {
                        repo.loadTimeSeries(start, end)
                    } else {
                        SessionDetail(emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
                    }
                    // DAV-115/123: prefer Zepp's real per-second series over
                    // Health Connect's own reconstruction wherever a matched
                    // Zepp workout exists -- the only source at all for a
                    // Zepp-only session (hcDetail is all-empty above).
                    val zeppDetail = repo.loadZeppDetail(sessionId)
                    detail = mergePreferZepp(zeppDetail, hcDetail)
                    zeppSummary = repo.loadZeppSummary(sessionId)
                    if (row?.type == "strength") zeppStrengthData = repo.loadZeppStrengthData(sessionId)
                } catch (e: Exception) {
                    detailError = e.message ?: "Couldn't load time-series detail."
                } finally {
                    loadingDetail = false
                }
            }
            // Strength sessions have no route. For all others: try HC first,
            // fall back to Zepp's decoded routePoints (v12) when HC returns
            // ConsentRequired/NoRoute or the session has no HC record at all.
            if (row?.type != "strength") {
                launch {
                    try {
                        val hcAvailability = if (recordId != null && start != null) {
                            repo.checkRouteAvailability(recordId, start)
                        } else RouteAvailability.NoRoute
                        val availability = if (hcAvailability is RouteAvailability.Available) {
                            hcAvailability
                        } else {
                            val zeppPts = repo.loadZeppRoute(sessionId)
                            if (zeppPts != null) RouteAvailability.Available(zeppPts) else hcAvailability
                        }
                        routeAvailability = availability
                        if (availability is RouteAvailability.Available) routePoints = availability.points
                    } catch (e: Exception) {
                        routeError = e.message ?: "Couldn't check for a recorded route."
                    } finally {
                        loadingRoute = false
                    }
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(FT.Base)) {
        TileHeader(
            onBack = onBack,
            title = header?.type?.replaceFirstChar { it.uppercase() } ?: "SESSION",
            subtitle = header?.let { sessionWhen(it.startTime) },
        )

        when {
            loadingHeader -> SkeletonSections()
            header == null -> Box(Modifier.fillMaxSize().padding(22.dp), contentAlignment = Alignment.Center) {
                Text(headerError ?: "Session not found.", style = FTType.BodySmall, color = FT.TextSecondary)
            }
            else -> {
                val h = header!!
                val d = detail
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
                ) {
                    val isStrength = h.type == "strength"
                    // Strength headline: total calories only (no active-calories
                    // split -- the watch's active figure isn't meaningful for lifting).
                    if (isStrength) {
                        h.caloriesTotal?.let { FTMetricValue(DisplayValue(primary = "${it.toInt()}", unit = "KCAL", secondary = "Total calories")) }
                    }
                    // 28/9 rework: SUMMARY -> RUN DYNAMICS -> TRAIL -> SPLITS -> ROUTE
                    // for a run/trail session (was SUMMARY -> [route consent] ->
                    // TRAIL -> PERFORMANCE+SPLITS -> RUN DYNAMICS -> ROUTE); strength
                    // stays SUMMARY -> STRENGTH. The HR/PACE/POWER/CADENCE chart
                    // (previously its own "PERFORMANCE" card) is now part of SUMMARY.
                    val isRun = h.type == "run"
                    val zones = zoneSeries?.let { z -> SessionZoneInputs(z.anchorsFor(OffsetDateTime.parse(h.startTime).toLocalDate()), h.type, zoneDefault) }
                    // A run: SUMMARY tiles, then RUN DYNAMICS with a collapsible section per signal (owner's structure,
                    // run session page). Every other type keeps SUMMARY with its combined chart.
                    if (isRun) {
                        RunSummaryCard(h, d, zeppSummary, onOpenShoe)
                        RunDynamicsCard(h, d, zeppSummary, zones, loadingDetail, detailError, hasTimeSeries = h.healthConnectRecordId != null || d != null, marks = runMarks)
                    } else {
                        SummaryCard(h, d, loadingDetail, detailError, hasHealthConnectRecord = h.healthConnectRecordId != null, zones = zones)
                    }

                    // Live check: h.details.exercises (name + sets, already
                    // editable via the Log tab's edit sheet) never rendered
                    // anywhere in the app.
                    planned?.let { pm ->
                        PlannedSessionCard(pm, h.details.exercises.orEmpty(), h.durationMin) {
                            sessionScope.launch {
                                catching { TrainingProgramRepository(SupabaseClientProvider.client).linkPlanned(pm.row.id, sessionId) }
                                    .onSuccess { planned = pm.copy(linked = true) }
                            }
                        }
                    }
                    if (isStrength) {
                        h.details.exercises?.takeIf { it.isNotEmpty() }?.let { exercises ->
                            StrengthCard(exercises, exerciseLibrary, zeppStrengthData)
                        }
                        // The exercises against Zepp's version: a choice when they differ, and RE-IMPORT FROM ZEPP.
                        StrengthZeppCard(
                            sessionId = sessionId,
                            sessionDate = OffsetDateTime.parse(h.startTime).toLocalDate(),
                            details = h.details,
                            zepp = zeppStrengthData,
                        ) {
                            val repo = SessionDetailRepository(context, SupabaseClientProvider.client)
                            header = repo.loadHeader(sessionId)
                            zeppStrengthData = repo.loadZeppStrengthData(sessionId)
                        }
                        // HR chart with work/rest overlay -- only when Zepp strength
                        // timing is available and there is actually a heartRate series.
                        val hrSeries = d?.heartRate.orEmpty()
                        val strData = zeppStrengthData
                        if (strData != null && hrSeries.isNotEmpty()) {
                            StrengthHrCard(hrSeries, strData)
                        }
                        zeppStrengthData?.let { MovementEvaluationCard(it) }
                    }

                    if (!isStrength && !isRun) zeppSummary?.let { ZeppDynamicsCard(it) }

                    val recordId = h.healthConnectRecordId

                    // DAV-144. Gated the same way as the ROUTE card below: real
                    // route + real time series both need to be in hand before
                    // the trail engine has anything to compute. Live check
                    // found this went silent (no card, no explanation) for
                    // any trail-tagged run still waiting on route
                    // consent/data -- indistinguishable from broken -- so the
                    // "not ready yet" branches now say why, reusing the same
                    // routeAvailability read the ROUTE card below shares
                    // rather than a second consent flow.
                    if (h.type == "run" && h.details.routeType == "trail") {
                        val pts = routePoints
                        when {
                            pts != null && pts.size >= 2 && d != null -> {
                                val trailState = remember(h.id, pts, d) {
                                    computeTrailSessionState(
                                        sessionId = h.id,
                                        date = try {
                                            OffsetDateTime.parse(h.startTime).toLocalDate()
                                        } catch (e: Exception) {
                                            LocalDate.now()
                                        },
                                        routePoints = pts,
                                        heartRate = d.heartRate,
                                        powerW = d.powerW,
                                        speedKmh = d.speedKmh,
                                    )
                                }
                                RunTrailCard(trailState, pts, d)
                            }
                            loadingRoute || loadingDetail -> Unit // covered by the loading indicators above
                            else -> FTCard(title = "TRAIL") {
                                Text(
                                    when (routeAvailability) {
                                        is RouteAvailability.ConsentRequired ->
                                            "Tagged as trail, but showing its route needs the one-time Health Connect permission above first."
                                        is RouteAvailability.NoRoute ->
                                            "Tagged as trail, but this session has no recorded route to compute trail metrics from."
                                        else ->
                                            "Trail metrics need a recorded route and performance data, not available yet for this session."
                                    },
                                    style = FTType.BodySmall,
                                    color = FT.TextSecondary,
                                )
                            }
                        }
                    }

                    // Splits self-hide (computeKmSplits returns emptyList) while d is
                    // still loading/unavailable -- no separate loading state needed,
                    // same convention RunDynamicsCard/TrailCard's own stats already use.
                    if (isRun) d?.let { RunSplitsCard(computeKmSplits(it.distanceKm, it.heartRate, it.pauses)) }
                    else if (!isStrength) d?.let { SplitsCard(computeKmSplits(it.distanceKm, it.heartRate, it.pauses)) }

                    // Live check: the route map was consistently the slowest
                    // thing to resolve on this screen (map tiles + a real
                    // Health Connect consent round-trip), so it sat at the top
                    // making the rest of the page feel stuck behind it. Moved
                    // to the bottom -- everything above it is ready sooner and
                    // no longer waits behind the map visually.
                    if (recordId != null && !isStrength) {
                        val pts = routePoints
                        when {
                            pts != null && pts.size >= 2 -> FTCard(title = "ROUTE") {
                                if (cartoKey != null) {
                                    RouteMiniMap(points = pts, cartoKey = cartoKey)
                                } else {
                                    Text(
                                        "Add a CARTO API key in Settings to see the route map.",
                                        style = FTType.BodySmall,
                                        color = FT.TextSecondary,
                                    )
                                }
                            }
                            loadingRoute -> Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = FT.Ok)
                            }
                            routeAvailability is RouteAvailability.ConsentRequired -> FTCard(title = "ROUTE") {
                                Text(
                                    "This session has a recorded route. Health Connect requires a one-time, per-session permission to view it.",
                                    style = FTType.BodySmall,
                                    color = FT.TextSecondary,
                                )
                                FTButton(label = "VIEW ROUTE") { routeLauncher.launch(recordId) }
                            }
                            routeError != null -> Text(
                                "Couldn't check for a recorded route (${routeError}).",
                                style = FTType.BodySmall,
                                color = FT.TextSecondary,
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
private fun SummaryCard(
    header: ExerciseSessionDetailRow,
    detail: SessionDetail?,
    loadingDetail: Boolean,
    detailError: String?,
    hasHealthConnectRecord: Boolean,
    zones: SessionZoneInputs?,
) {
    // Live check: the stored session-level aggregates (avg_hr/avg_speed_kmh/
    // calories_active) are sometimes null even though the on-demand Health
    // Connect read (already fetched for PERFORMANCE, just handed in here too)
    // has real samples for the same session -- fall back to computing them
    // from that live series instead of just omitting the line.
    val avgHr = header.avgHr
        ?: detail?.heartRate?.takeIf { it.isNotEmpty() }?.let { s -> s.map { it.value }.average() }
    val avgPaceMinPerKm = (
        sessionAvgSpeedKmh(
            header.type, header.avgSpeedKmh, header.distanceKm, header.durationMin,
            detail?.movingMinutes(),
        )
            ?: detail?.speedKmh?.takeIf { it.isNotEmpty() }?.let { s -> s.map { it.value }.average() }
        )?.takeIf { it > 0 }?.let { 60.0 / it }
    val isStrength = header.type == "strength"
    val activeCalories = header.caloriesActive ?: detail?.caloriesKcal?.lastOrNull()?.value

    // One dominant value, then supporting rows: distance (with duration and
    // pace as its context) for runs/rides, duration alone for strength.
    val heroDistance = header.distanceKm?.takeIf { !isStrength }
    val hero = if (heroDistance != null) {
        DisplayValue(
            primary = "%.2f".format(heroDistance),
            unit = "KM",
            secondary = listOfNotNull(
                header.durationMin?.let { formatDuration(it) },
                avgPaceMinPerKm?.let { formatSplitPace((it * 60).toLong()) },
            ).joinToString(" · ").ifBlank { null },
        )
    } else {
        header.durationMin?.let { DisplayValue(primary = formatDuration(it), secondary = "DURATION") }
    }

    FTCard(title = "SUMMARY") {
        hero?.let { FTMetricValue(it) }
        // With a distance hero, duration and pace ride in its context line.
        if (heroDistance == null) {
            header.distanceKm?.let { FTMetricRow("Distance", "%.2f km".format(it)) }
        }
        header.details.runType?.let { FTMetricRow("Run type", it.replaceFirstChar(Char::uppercase)) }
        header.details.routeType?.let { FTMetricRow("Route", it.replaceFirstChar(Char::uppercase)) }
        avgHr?.let { FTMetricRow("Avg heart rate", "${it.toInt()} bpm") }
        header.maxHr?.let { FTMetricRow("Max heart rate", "${it.toInt()} bpm") }
        header.elevationGainM?.let { FTMetricRow("Elevation gain", "${it.toInt()} m") }
        header.avgPowerW?.let { FTMetricRow("Avg power", "${it.toInt()} W") }
        if (heroDistance == null) {
            avgPaceMinPerKm?.let { FTMetricRow("Avg pace", formatSplitPace((it * 60).toLong())) }
        }
        if (!isStrength) {
            activeCalories?.let { FTMetricRow("Active calories", "${it.toInt()} kcal") }
            header.caloriesTotal?.let { FTMetricRow("Total calories", "${it.toInt()} kcal") }
        }
        header.rpe?.let { FTMetricRow("RPE", "$it/10") }
        header.notes?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = FTType.BodySmall, color = FT.TextSecondary)
        }

        // 28/9: HR/PACE/POWER/CADENCE chart folded in here (was its own
        // "PERFORMANCE" card) -- never shown for strength (no such series).
        if (!isStrength) {
            when {
                !hasHealthConnectRecord -> Text(
                    "No time-series available for sessions logged before Health Connect.",
                    style = FTType.BodySmall,
                    color = FT.TextSecondary,
                )
                loadingDetail -> Box(Modifier.fillMaxWidth().padding(vertical = 30.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = FT.Ok)
                }
                detailError != null -> Text(
                    "Couldn't load time-series detail (${detailError}).",
                    style = FTType.BodySmall,
                    color = FT.TextSecondary,
                )
                detail != null -> PerformanceChartSection(detail, zones)
            }
        }
    }
}

// Start time is this app's local wall-clock label (see the convention noted
// above), so it's formatted as-is, no zone conversion.
private fun sessionWhen(startTime: String): String = try {
    OffsetDateTime.parse(startTime).toLocalDateTime().format(DateTimeFormatter.ofPattern("EEE d MMM · HH:mm"))
} catch (e: Exception) {
    startTime
}
