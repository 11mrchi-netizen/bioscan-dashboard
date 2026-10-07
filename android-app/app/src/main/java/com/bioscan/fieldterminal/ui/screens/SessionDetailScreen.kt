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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
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
import com.bioscan.fieldterminal.data.ZeppWorkoutSummary
import com.bioscan.fieldterminal.data.ExerciseLibraryRepository
import com.bioscan.fieldterminal.data.model.ExerciseLibraryRow
import com.bioscan.fieldterminal.data.model.ExerciseSessionDetailRow
import com.bioscan.fieldterminal.data.model.StrengthExerciseDto
import com.bioscan.fieldterminal.data.toRoutePoints
import com.bioscan.fieldterminal.domain.DisplayValue
import com.bioscan.fieldterminal.domain.EvalState
import com.bioscan.fieldterminal.domain.MetricState
import com.bioscan.fieldterminal.domain.toMetricState
import com.bioscan.fieldterminal.domain.RouteAvailability
import com.bioscan.fieldterminal.domain.RoutePoint
import com.bioscan.fieldterminal.domain.SessionDetail
import com.bioscan.fieldterminal.domain.SessionSplit
import com.bioscan.fieldterminal.domain.TimePoint
import com.bioscan.fieldterminal.domain.bestEstimatedOneRepMax
import com.bioscan.fieldterminal.domain.classifyMovementPattern
import com.bioscan.fieldterminal.domain.MovementPattern
import com.bioscan.fieldterminal.domain.resolveExercise
import com.bioscan.fieldterminal.domain.sessionAverageRir
import com.bioscan.fieldterminal.domain.sessionAverageRpe
import com.bioscan.fieldterminal.domain.sessionRegionalLoad
import com.bioscan.fieldterminal.domain.sessionVolumeLoad
import com.bioscan.fieldterminal.domain.analysis.SessionStateObject
import com.bioscan.fieldterminal.domain.analysis.StateDimension
import com.bioscan.fieldterminal.domain.computeKmSplits
import com.bioscan.fieldterminal.domain.mergePreferZepp
import com.bioscan.fieldterminal.domain.trail.computeTrailSessionState
import com.bioscan.fieldterminal.domain.trail.elevationProfileWithOverlays
import com.bioscan.fieldterminal.domain.trail.itraCategory
import com.bioscan.fieldterminal.ui.components.AmberButton
import com.bioscan.fieldterminal.ui.components.BodyHeatMap
import com.bioscan.fieldterminal.domain.zoneLoads
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTMetricValue
import com.bioscan.fieldterminal.ui.components.FTStatePill
import com.bioscan.fieldterminal.ui.components.InfoHelpButton
import com.bioscan.fieldterminal.ui.components.LineChart
import com.bioscan.fieldterminal.ui.components.MinMaxAverageBar
import com.bioscan.fieldterminal.ui.components.RangeBar
import com.bioscan.fieldterminal.ui.components.RouteMiniMap
import com.bioscan.fieldterminal.ui.components.SubTabRow
import com.bioscan.fieldterminal.ui.components.TrailElevationChart
import com.bioscan.fieldterminal.ui.components.FTMetricRow
import com.bioscan.fieldterminal.ui.components.TileHeader
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import com.bioscan.fieldterminal.ui.theme.RobotoMono
import java.time.Instant
import java.time.LocalDate
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
    var zeppSummary by remember { mutableStateOf<ZeppWorkoutSummary?>(null) }

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

        // Real anatomical data (exercise_library's primary/secondary_muscles,
        // 876 rows) for StrengthCard's regional breakdown -- replaces the
        // earlier keyword-guess classifier now that domain/RegionalLoad.kt/
        // StrengthLoad.kt's already-built, already-verified pipeline is wired
        // in here instead. Only fetched for a strength session with logged
        // exercises -- no point pulling 876 rows otherwise.
        if (row?.type == "strength" && row.details.exercises?.isNotEmpty() == true) {
            exerciseLibrary = ExerciseLibraryRepository(SupabaseClientProvider.client).fetchAll()
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
        if (recordId != null) loadingRoute = true
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
                } catch (e: Exception) {
                    detailError = e.message ?: "Couldn't load time-series detail."
                } finally {
                    loadingDetail = false
                }
            }
            // Strength sessions have no route -- skip the Health Connect
            // consent round-trip entirely for them.
            if (recordId != null && start != null && row.type != "strength") {
                launch {
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
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(FT.Base)) {
        TileHeader(
            onBack = onBack,
            title = header?.type?.replaceFirstChar { it.uppercase() } ?: "SESSION",
            subtitle = header?.let { sessionWhen(it.startTime) },
        )

        when {
            loadingHeader -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = FT.Emerald)
            }
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
                    SummaryCard(h, d, loadingDetail, detailError, hasHealthConnectRecord = h.healthConnectRecordId != null)

                    // Live check: h.details.exercises (name + sets, already
                    // editable via the Log tab's edit sheet) never rendered
                    // anywhere in the app.
                    if (isStrength) {
                        h.details.exercises?.takeIf { it.isNotEmpty() }?.let { exercises -> StrengthCard(exercises, exerciseLibrary) }
                    }

                    if (!isStrength) zeppSummary?.let { RunDynamicsCard(it) }

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
                                TrailCard(trailState, pts, d)
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
                    if (!isStrength) d?.let { SplitsCard(computeKmSplits(it.distanceKm, it.heartRate)) }

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
                                CircularProgressIndicator(color = FT.Emerald)
                            }
                            routeAvailability is RouteAvailability.ConsentRequired -> FTCard(title = "ROUTE") {
                                Text(
                                    "This session has a recorded route. Health Connect requires a one-time, per-session permission to view it.",
                                    style = FTType.BodySmall,
                                    color = FT.TextSecondary,
                                )
                                AmberButton(label = "VIEW ROUTE") { routeLauncher.launch(recordId) }
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

private enum class PerfSignal(val label: String, val unit: String, val color: Color) {
    HR("HR", "bpm", FT.DomainHeart),
    PACE("PACE", "min/km", FT.Category.Activity.c500),
    POWER("POWER", "W", FT.Category.Activity.c300),
    CADENCE("CADENCE", "spm", FT.TextSecondary),
}

// Pace reads as m:ss /km (not a decimal like 4.8), everything else as value + unit.
private fun formatSignalValue(signal: PerfSignal, v: Double): String =
    if (signal == PerfSignal.PACE) {
        val s = Math.round(v * 60).toInt()
        "%d:%02d /km".format(s / 60, s % 60)
    } else {
        "%.1f %s".format(v, signal.unit)
    }

// One chart, switchable rather than four stacked ones -- only signals this
// session actually recorded appear as options. PACE is derived from the
// same speed samples SPEED used to plot directly (min/km = 60/kmh), matching
// this app's existing averagePaceMinPerKmSince convention (domain/
// Training.kt) rather than showing raw km/h, since the ticket calls for PACE.
// 28/9: no longer its own "PERFORMANCE" FTCard -- folded into SUMMARY as a
// plain content section per direct request (HR/pace/cadence "in the summary").
@Composable
private fun PerformanceChartSection(d: SessionDetail) {
    val pace = remember(d.speedKmh) { d.speedKmh.mapNotNull { p -> if (p.value > 0) TimePoint(p.offsetSeconds, 60.0 / p.value) else null } }
    val seriesBySignal = mapOf(
        PerfSignal.HR to d.heartRate,
        PerfSignal.PACE to pace,
        PerfSignal.POWER to d.powerW,
        PerfSignal.CADENCE to d.cadenceSpm,
    )
    val available = PerfSignal.entries.filter { seriesBySignal[it]?.isNotEmpty() == true }
    if (available.isEmpty()) return
    var selected by remember(d) { mutableStateOf(available.first()) }
    if (selected !in available) selected = available.first()

    // Live check: switching tabs made a sensor that only reported for part
    // of the run look exactly as "full" as one spanning the whole session --
    // each chart stretched to its own first/last sample. The widest real
    // coverage across every available signal becomes the shared axis every
    // tab draws against, so a partial one shows up short instead.
    val sessionStart = available.minOf { seriesBySignal.getValue(it).first().offsetSeconds }
    val sessionEnd = available.maxOf { seriesBySignal.getValue(it).last().offsetSeconds }

    SubTabRow(items = available, selected = selected, label = { it.label }, onSelect = { selected = it })
    val points = seriesBySignal.getValue(selected)
    val values = points.map { it.value }
    MinMaxAverageBar(
        min = values.min(),
        average = values.average(),
        max = values.max(),
        color = selected.color,
        format = { v -> formatSignalValue(selected, v) },
    )
    LineChart(
        points = points,
        color = selected.color,
        xRange = sessionStart to sessionEnd,
        modifier = Modifier.padding(top = 4.dp),
        valueFormat = { v -> formatSignalValue(selected, v) },
        invertY = selected == PerfSignal.PACE,
        unit = if (selected == PerfSignal.PACE) "pace · min:sec per km" else selected.unit,
    )
    if (points.first().offsetSeconds > sessionStart || points.last().offsetSeconds < sessionEnd) {
        Text(
            "${selected.label} only reported for part of this session.",
            style = FTType.Caption,
            color = FT.TextSecondary,
        )
    }
}

@Composable
private fun SplitsCard(splits: List<SessionSplit>) {
    if (splits.isEmpty()) return
    val fastestKm = splits.minByOrNull { it.durationSec }?.km
    FTCard(title = "SPLITS") {
        splits.forEach { split ->
            val isFastest = split.km == fastestKm
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("KM ${split.km}", style = FTType.BodySmall, color = FT.TextSecondary)
                Text(
                    formatSplitPace(split.durationSec) + (split.avgHr?.let { "  ·  %.0f bpm".format(it) } ?: "") + (if (isFastest) "  ★" else ""),
                    style = if (isFastest) FTType.Telemetry else FTType.Value,
                    color = if (isFastest) FT.Emerald else FT.TextPrimary,
                )
            }
        }
    }
}

// DAV-115/123: Zepp's own run-dynamics + lactate-threshold summary averages
// (docs/zepp-integration/03-workout-detail-field-decode.md) -- workout-level
// scalars, not a TimePoint series, so they get their own small card rather
// than forcing them into PerformanceChartCard's per-second signals. Each row
// renders only when that field is non-null -- a device/sport that doesn't
// report ground contact time (say) just omits that row, same optional-stat
// convention SummaryCard/TrailCard already use in this file. Lactate
// threshold is Zepp's own rolling estimate as of this workout, not a
// dedicated one-off test -- labeled that way rather than implying otherwise.
@Composable
private fun RunDynamicsCard(summary: ZeppWorkoutSummary) {
    val rows = buildList {
        summary.gapMinPerKm?.let { add(Triple("Grade-adjusted pace", formatSplitPace((it * 60).toLong()), "Grade-adjusted pace" to GAP_HELP)) }
        summary.efficiencyFactor?.let { add(Triple("Efficiency factor", "%.2f".format(it), "Efficiency factor" to EF_SESSION_HELP)) }
        summary.hrDecouplingPct?.let { add(Triple("HR decoupling", "%+.1f%%".format(it), "HR decoupling" to HR_DECOUPLING_HELP)) }
        summary.avgCadenceSpm?.let { add(Triple("Avg cadence", "%.0f spm".format(it), null)) }
        summary.maxCadenceSpm?.let { add(Triple("Max cadence", "%.0f spm".format(it), null)) }
        summary.avgGroundContactMs?.let { add(Triple("Ground contact", "%.0f ms".format(it), "Ground contact time" to GROUND_CONTACT_HELP)) }
        summary.avgStrideLengthCm?.let { add(Triple("Stride length", "%.0f cm".format(it), "Stride length" to STRIDE_LENGTH_HELP)) }
        summary.avgVerticalStrideRatioPct?.let { add(Triple("Vertical ratio", "%.1f%%".format(it), "Vertical oscillation ratio" to VERTICAL_RATIO_HELP)) }
        if (summary.lactateThresholdHrBpm != null || summary.lactateThresholdPaceSecPerKm != null) {
            val hr = summary.lactateThresholdHrBpm?.let { "%.0f bpm".format(it) }
            val pace = summary.lactateThresholdPaceSecPerKm?.let { formatSplitPace(it.toLong()) }
            add(Triple("Lactate threshold (as of this run)", listOfNotNull(hr, pace).joinToString("  ·  "), "Lactate threshold" to LACTATE_THRESHOLD_HELP))
        }
    }
    if (rows.isEmpty()) return

    FTCard(title = "RUN DYNAMICS") {
        rows.forEach { (label, value, help) ->
            if (help != null) StatLineHelp(label, value, help.first, help.second) else FTMetricRow(label, value)
        }
    }
}

// DAV-144, per docs/trail-intelligence/05-trail-metrics-ui-presentation.md.
// Mountain Index and ITRA category lead as a two-block header (28/9); every
// other dimension renders only when it has a real value -- a NoData
// dimension (too few climbs to compare, no HR data for efficiency) is
// silently omitted rather than shown as a forced N/A, matching this file's
// own SummaryCard convention for optional stats.
@Composable
private fun TrailCard(state: SessionStateObject, routePoints: List<RoutePoint>, detail: SessionDetail) {
    val mountainIndex = state.dimensions.getValue(StateDimension.MOUNTAIN_INDEX)
    val kmEffort = state.dimensions.getValue(StateDimension.KM_EFFORT).value
    val itra = kmEffort?.let { itraCategory(it) }
    val profile = remember(routePoints, detail) {
        elevationProfileWithOverlays(routePoints, detail.heartRate, detail.speedKmh, detail.cadenceSpm)
    }

    FTCard(title = "TRAIL") {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(modifier = Modifier.weight(1f)) {
                TrailHeaderLabel("MOUNTAIN INDEX", "Mountain Index", MOUNTAIN_INDEX_HELP)
                if (mountainIndex.value != null) {
                    FTMetricValue(DisplayValue(primary = "%.0f".format(mountainIndex.value), unit = "M/KM"))
                } else {
                    FTStatePill(mountainIndex.evalState.toMetricState())
                }
            }
            Column(modifier = Modifier.weight(1f)) {
                TrailHeaderLabel("ITRA CATEGORY", "ITRA category", ITRA_CATEGORY_HELP)
                if (itra != null) {
                    FTMetricValue(DisplayValue(primary = itra))
                } else {
                    Text("—", style = FTType.MetricMedium, color = FT.TextMuted)
                }
            }
        }

        if (profile.size >= 2) {
            TrailElevationChart(profile, modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
        }

        kmEffort?.let { StatLineHelp("KM-effort", "%.1f".format(it), "KM-effort", KM_EFFORT_HELP) }
        state.dimensions.getValue(StateDimension.ELEVATION_GAIN_M).value?.let { FTMetricRow("Elevation gain", "${it.toInt()} m") }
        state.dimensions.getValue(StateDimension.ELEVATION_LOSS_M).value?.let { FTMetricRow("Elevation loss", "${it.toInt()} m") }
        state.dimensions.getValue(StateDimension.AVERAGE_VAM).value?.let { StatLineHelp("Avg VAM", "${it.toInt()} m/h", "VAM", VAM_HELP) }
        state.dimensions.getValue(StateDimension.UPHILL_RUN_PERCENT).value?.let { FTMetricRow("Uphill run", "%.0f%%".format(it)) }
        state.dimensions.getValue(StateDimension.UPHILL_EFFICIENCY).value?.let { StatLineHelp("Uphill efficiency", "%.2f m/h per bpm".format(it), "Uphill efficiency", GRADE_EFFICIENCY_HELP) }
        state.dimensions.getValue(StateDimension.DOWNHILL_EFFICIENCY).value?.let { StatLineHelp("Downhill efficiency", "%.2f m/h per bpm".format(it), "Downhill efficiency", GRADE_EFFICIENCY_HELP) }
        state.dimensions.getValue(StateDimension.CLIMB_CONSISTENCY).value?.let { StatLineHelp("Climb consistency (CV)", "%.2f".format(it), "Climb consistency", CONSISTENCY_HELP) }
        state.dimensions.getValue(StateDimension.DESCENT_CONSISTENCY).value?.let { StatLineHelp("Descent consistency (CV)", "%.2f".format(it), "Descent consistency", CONSISTENCY_HELP) }
        state.dimensions.getValue(StateDimension.PACE_DEGRADATION).value?.let { StatLineHelp("Pace degradation", "%+.1f%%".format(it), "Pace degradation", PACE_DEGRADATION_HELP) }
        state.dimensions.getValue(StateDimension.VAM_DEGRADATION).value?.let { StatLineHelp("VAM degradation", "%+.1f%%".format(it), "VAM degradation", VAM_DEGRADATION_HELP) }
        state.dimensions.getValue(StateDimension.HR_DECOUPLING).value?.let { StatLineHelp("HR decoupling", "%+.1f%%".format(it), "HR decoupling", HR_DECOUPLING_HELP) }
    }
}

@Composable
private fun TrailHeaderLabel(label: String, helpTitle: String, helpBody: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = FTType.Label, color = FT.TextMuted)
        InfoHelpButton(helpTitle, helpBody)
    }
}

// Same StatLine row shape, with a "?" between label and value for the
// specialized/jargon-heavy metrics on this screen (28/9) -- plain stats
// (Duration, Distance, Avg HR, ...) don't get one, same as CTL/ATL/TSB got
// explainers on the Training tab but "This week"/"Confidence" didn't.
@Composable
private fun StatLineHelp(label: String, value: String, helpTitle: String, helpBody: String) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = FTType.Body, color = FT.TextSecondary)
        InfoHelpButton(helpTitle, helpBody, modifier = Modifier.padding(start = 6.dp))
        Text(
            value,
            style = FTType.Value,
            color = FT.TextPrimary,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f).padding(start = 8.dp),
        )
    }
}

private const val MOUNTAIN_INDEX_HELP =
    "How steep this route was on average: elevation gain (m) ÷ distance (km). The 40s-60s m/km range is a hilly trail run; over 100 is seriously mountainous, UTMB-style climbing."
private const val ITRA_CATEGORY_HELP =
    "ITRA's own race-size classification, from the same km-effort formula ITRA uses to grade real races (distance + climb/100). Bands run XXS (under 25) up to XXL (210+) -- this shows which size of real race this run's own effort would sit in."
private const val KM_EFFORT_HELP =
    "One \"effort kilometre\" per km of distance plus one per 100 m climbed -- the official FFA/ITRA formula for a route's real size, since a flat 20K and a mountainous 20K are not the same effort."
private const val VAM_HELP =
    "Vertical Ascent in Meters per hour -- how fast you climbed, borrowed from cycling. A brisk hike is roughly 400-600 m/h; strong trail runners sustain 800-1000+ on steep, runnable climbs."
private const val GRADE_EFFICIENCY_HELP =
    "Climbing (or descending) speed per heartbeat: vertical metres per hour ÷ average heart rate on that segment -- the trail equivalent of Efficiency Factor. Higher means faster for the same effort."
private const val CONSISTENCY_HELP =
    "How even your pace was across this run's climbs (or descents), as a coefficient of variation. Lower means more consistent climb to climb; higher means some hit much harder than others."
private const val PACE_DEGRADATION_HELP =
    "How much your grade-adjusted pace slowed from the first half of comparable segments to the second. Positive means you faded; near zero means you held pace well."
private const val VAM_DEGRADATION_HELP =
    "Same idea as pace degradation, for climbing speed: how much slower you climbed late in the run versus early, on matched climbs."
private const val HR_DECOUPLING_HELP =
    "How much your effort-to-heart-rate ratio drifted from the first half of this run to the second. Under about 5% suggests a solid aerobic effort; a bigger drift means heart rate crept up for the same output -- heat, dehydration or fatigue."
private const val GAP_HELP =
    "Grade-Adjusted Pace: your pace converted to its flat-ground equivalent using the Minetti et al. running-cost formula, so a hilly split doesn't look artificially slow next to a flat one."
private const val EF_SESSION_HELP =
    "Efficiency Factor for this one run: grade-adjusted speed ÷ average heart rate. Compare it against the Training tab's 28-day trend rather than any single other run -- one run swings too much with heat, sleep and terrain to read alone."
private const val GROUND_CONTACT_HELP =
    "Ground contact time -- how long each foot spends on the ground per stride, in milliseconds. Lower generally means a springier stride; it naturally rises as you slow down or tire."
private const val VERTICAL_RATIO_HELP =
    "Vertical oscillation ratio -- how much you bounce up and down relative to your stride length. Lower means more of your effort goes into forward motion rather than bouncing."
private const val STRIDE_LENGTH_HELP =
    "Average distance covered per stride. Naturally shorter uphill and on technical terrain, longer on flat, fast sections."
private const val LACTATE_THRESHOLD_HELP =
    "Your watch's own rolling estimate of the heart rate and pace you could sustain for about an hour before lactate builds up faster than you can clear it -- recomputed after each qualifying run, not from one dedicated test."

// Live check: this used to run a best-effort keyword guess over free-text
// exercise names. Redone against domain/RegionalLoad.kt's already-built,
// already-verified pipeline instead -- exercise_library's real 876-row
// primary/secondary_muscles data, resolved by exact name match
// (StrengthLoad.kt's resolveExercise()), weighted primary/secondary and
// merged across the session (sessionRegionalLoad()). An exercise whose
// logged name doesn't match the library contributes nothing to the
// breakdown rather than a guessed region -- a real data-availability gap,
// not hidden behind a heuristic.
@Composable
private fun StrengthCard(exercises: List<StrengthExerciseDto>, library: List<ExerciseLibraryRow>) {
    val totalVolume = remember(exercises) { sessionVolumeLoad(exercises) }
    val regional = remember(exercises, library) { sessionRegionalLoad(exercises, library) }
    val avgRpe = remember(exercises) { sessionAverageRpe(exercises) }
    val avgRir = remember(exercises) { sessionAverageRir(exercises) }
    FTCard(title = "STRENGTH") {
        FTMetricRow("Total volume", "%.0f kg".format(totalVolume))
        avgRpe?.let { FTMetricRow("Avg RPE", "%.1f".format(it)) }
        avgRir?.let { FTMetricRow("Avg RIR", "%.1f".format(it)) }

        // Body heat map replaces the old per-region bars (25/9 rework).
        val zones = remember(regional) { zoneLoads(regional) }
        if (zones.values.sum() > 0) {
            BodyHeatMap(zones, modifier = Modifier.padding(vertical = 6.dp))
        } else if (library.isNotEmpty()) {
            Text(
                "None of this session's logged exercise names matched the exercise library -- no anatomical breakdown available.",
                style = FTType.Caption,
                color = FT.TextSecondary,
            )
        }

        // One set per line: "Set 1 -- 8 x 60 kg  82% 1RM  RPE 8  RIR 2".
        // 28/9: subtitle line from the exercise library match already used for
        // the heat map above (category/equipment/movement pattern) -- real
        // fields, silently omitted per-part when null/unclassified rather than
        // guessed, same convention the heat map's own fallback text uses.
        exercises.forEach { exercise ->
            val e1rm = bestEstimatedOneRepMax(exercise)
            val libraryRow = remember(exercise.name, library) { resolveExercise(exercise.name, library) }
            val subtitle = remember(libraryRow) {
                libraryRow?.let {
                    listOfNotNull(
                        it.category?.replaceFirstChar(Char::uppercase),
                        it.equipment?.replaceFirstChar(Char::uppercase),
                        classifyMovementPattern(it).takeIf { p -> p != MovementPattern.Unclassified }?.name,
                    ).joinToString(" · ").ifBlank { null }
                }
            }
            Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(exercise.name, style = FTType.RowTitle, color = FT.TextPrimary)
                        subtitle?.let { Text(it, style = FTType.Caption, color = FT.TextSecondary) }
                    }
                    e1rm?.let { Text("e1RM %.0f kg".format(it), style = FTType.MonoCaption, color = FT.TextSecondary) }
                }
                exercise.sets.forEachIndexed { i, set ->
                    val extras = listOfNotNull(
                        set.percentOneRm?.let { "%.0f%% 1RM".format(it) },
                        set.rpe?.let { "RPE $it" },
                        set.rir?.let { "RIR $it" },
                    )
                    FTMetricRow("Set ${i + 1}", "${set.reps} × %.0f kg".format(set.weightKg) + extras.joinToString("") { "  ·  $it" })
                }
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
private fun SummaryCard(
    header: ExerciseSessionDetailRow,
    detail: SessionDetail?,
    loadingDetail: Boolean,
    detailError: String?,
    hasHealthConnectRecord: Boolean,
) {
    // Live check: the stored session-level aggregates (avg_hr/avg_speed_kmh/
    // calories_active) are sometimes null even though the on-demand Health
    // Connect read (already fetched for PERFORMANCE, just handed in here too)
    // has real samples for the same session -- fall back to computing them
    // from that live series instead of just omitting the line.
    val avgHr = header.avgHr
        ?: detail?.heartRate?.takeIf { it.isNotEmpty() }?.let { s -> s.map { it.value }.average() }
    val avgPaceMinPerKm = (
        header.avgSpeedKmh
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
                    CircularProgressIndicator(color = FT.Emerald)
                }
                detailError != null -> Text(
                    "Couldn't load time-series detail (${detailError}).",
                    style = FTType.BodySmall,
                    color = FT.TextSecondary,
                )
                detail != null -> PerformanceChartSection(detail)
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

private fun formatDuration(totalMinutes: Double): String {
    val h = (totalMinutes / 60).toInt()
    val m = (totalMinutes % 60).toInt()
    return if (h > 0) "${h}h ${m}m" else "${m}m"
}
