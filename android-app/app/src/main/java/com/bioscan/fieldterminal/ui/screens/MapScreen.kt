package com.bioscan.fieldterminal.ui.screens

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.bioscan.fieldterminal.auth.GoogleAuthorizationManager
import com.bioscan.fieldterminal.data.MapRepository
import com.bioscan.fieldterminal.data.MapSettingsStore
import com.bioscan.fieldterminal.data.WeatherRepository
import com.bioscan.fieldterminal.domain.GpxPoint
import com.bioscan.fieldterminal.domain.NextSession
import com.bioscan.fieldterminal.domain.SessionWeather
import com.bioscan.fieldterminal.domain.daysUntilSession
import com.bioscan.fieldterminal.domain.parseSessionZonedDateTime
import com.bioscan.fieldterminal.domain.routeDistanceKm
import com.bioscan.fieldterminal.domain.routeElevationGainM
import com.bioscan.fieldterminal.domain.weatherCodeLabel
import com.bioscan.fieldterminal.domain.weatherCodeSymbol
import com.bioscan.fieldterminal.ui.components.AmberButton
import com.bioscan.fieldterminal.ui.components.ScreenHeader
import com.bioscan.fieldterminal.ui.theme.FieldColors
import com.bioscan.fieldterminal.ui.theme.FieldTextStyles
import com.bioscan.fieldterminal.ui.theme.JetBrainsMono
import com.bioscan.fieldterminal.ui.theme.Saira
import com.bioscan.fieldterminal.ui.theme.SairaCondensed
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.time.format.DateTimeFormatter

// Step 14 follow-up (user request, 2026-09-15): a real map background, a
// directions link to the session location, a weather-at-session-time popup,
// and full-bleed layout with the session details moved into a popup instead
// of an always-visible card. See ROADMAP.md for the full write-up, including
// why this uses osmdroid + CARTO's free Dark Matter tiles rather than the
// Google Maps SDK (which needs a billing-enabled Cloud project just to
// display a map -- confirmed against Google's own docs, not assumed) and why
// "arrive by" is a manual step inside the Google Maps app rather than
// pre-filled (Maps' consumer deep link doesn't accept an arrival time --
// only its separate, also-billed Directions API does).
private sealed interface MapState {
    data object CheckingAccess : MapState
    data class NeedsConsent(val pendingIntent: android.app.PendingIntent) : MapState
    data class Error(val message: String) : MapState
    data class Ready(val session: NextSession?, val gpxPoints: List<GpxPoint>?, val gpxError: String?) : MapState
}

private sealed interface WeatherUiState {
    data object Idle : WeatherUiState
    data object Loading : WeatherUiState
    data class Loaded(val weather: SessionWeather) : WeatherUiState
    data class Failed(val message: String) : WeatherUiState
}

@Composable
fun MapScreen() {
    val activity = LocalContext.current as Activity
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<MapState>(MapState.CheckingAccess) }

    suspend fun loadWithToken(token: String) {
        try {
            val repo = MapRepository(token)
            val session = repo.fetchNextSession()
            var points: List<GpxPoint>? = null
            var gpxError: String? = null
            val gpxLink = session?.gpxLink
            if (gpxLink != null) {
                try {
                    points = repo.fetchGpxPoints(gpxLink)
                } catch (e: Exception) {
                    gpxError = e.message
                }
            }
            state = MapState.Ready(session, points, gpxError)
        } catch (e: Exception) {
            state = MapState.Error(e.message ?: "Failed to load calendar data.")
        }
    }

    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        scope.launch {
            try {
                val authResult = GoogleAuthorizationManager.resultFromIntent(activity, result.data)
                val token = authResult.accessToken
                if (token != null) loadWithToken(token) else state = MapState.Error("Calendar/Drive access was not granted.")
            } catch (e: ApiException) {
                state = MapState.Error("Authorization failed: ${e.message}")
            }
        }
    }

    LaunchedEffect(Unit) {
        state = MapState.CheckingAccess
        try {
            val authResult = GoogleAuthorizationManager.authorize(activity)
            val pendingIntent = authResult.pendingIntent
            if (authResult.hasResolution() && pendingIntent != null) {
                state = MapState.NeedsConsent(pendingIntent)
            } else {
                val token = authResult.accessToken
                if (token != null) loadWithToken(token) else state = MapState.Error("Could not get a Google access token.")
            }
        } catch (e: Exception) {
            state = MapState.Error(e.message ?: "Authorization failed.")
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(FieldColors.Ground)) {
        val readySession = (state as? MapState.Ready)?.session
        ScreenHeader(
            title = "MAP",
            context = readySession?.let { "NEXT SESSION · " + formatHeaderDate(it.startIso) } ?: "—",
        )

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when (val s = state) {
                is MapState.CheckingAccess -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = FieldColors.Amber)
                }
                is MapState.NeedsConsent -> Box(Modifier.fillMaxSize().padding(22.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(
                            "Grant Calendar and Drive access to see your next training session and its route.",
                            style = FieldTextStyles.placeholderBody,
                            color = FieldColors.InkMuted,
                        )
                        AmberButton(label = "GRANT ACCESS") {
                            consentLauncher.launch(IntentSenderRequest.Builder(s.pendingIntent.intentSender).build())
                        }
                    }
                }
                is MapState.Error -> Box(Modifier.fillMaxSize().padding(22.dp), contentAlignment = Alignment.Center) {
                    Text(s.message, style = FieldTextStyles.placeholderBody, color = FieldColors.Alert)
                }
                is MapState.Ready -> MapReadyContent(s)
            }
        }
    }
}

@Composable
private fun MapReadyContent(state: MapState.Ready) {
    val session = state.session
    if (session == null) {
        Box(Modifier.fillMaxSize().padding(22.dp), contentAlignment = Alignment.Center) {
            Text("No training session in the next 7 days.", style = FieldTextStyles.placeholderBody, color = FieldColors.InkMuted)
        }
        return
    }
    if (session.gpxLink == null) {
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp)) {
            NoLocationCard(session)
        }
        return
    }

    val context = LocalContext.current
    val cartoKey = remember { MapSettingsStore.getCartoKey(context) }
    val home = remember { MapSettingsStore.getHome(context) }
    var showDetails by remember { mutableStateOf(false) }
    var showWeather by remember { mutableStateOf(false) }
    var weatherState by remember { mutableStateOf<WeatherUiState>(WeatherUiState.Idle) }
    val scope = rememberCoroutineScope()

    Box(Modifier.fillMaxSize()) {
        when {
            cartoKey == null -> Box(Modifier.fillMaxSize().padding(22.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Add a free CARTO API key in Settings to show the map background (carto.com/basemaps/apikey — no billing).",
                    style = FieldTextStyles.placeholderBody,
                    color = FieldColors.InkMuted,
                )
            }
            state.gpxError != null -> Box(Modifier.fillMaxSize().padding(22.dp), contentAlignment = Alignment.Center) {
                Text("Couldn't load the route (${state.gpxError}).", style = FieldTextStyles.placeholderBody, color = FieldColors.InkMuted)
            }
            state.gpxPoints == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = FieldColors.Amber)
            }
            else -> {
                val points = state.gpxPoints
                OsmMapView(points = points, cartoKey = cartoKey, modifier = Modifier.fillMaxSize())

                WeatherChip(
                    state = weatherState,
                    modifier = Modifier.align(Alignment.TopEnd).padding(16.dp),
                    onClick = {
                        showWeather = true
                        if (weatherState !is WeatherUiState.Loaded) {
                            val start = points.first()
                            val at = parseSessionZonedDateTime(session.startIso)
                            weatherState = WeatherUiState.Loading
                            scope.launch {
                                weatherState = try {
                                    WeatherUiState.Loaded(WeatherRepository().fetchAt(start.lat, start.lon, at))
                                } catch (e: Exception) {
                                    WeatherUiState.Failed(e.message ?: "Weather request failed.")
                                }
                            }
                        }
                    },
                )

                Row(
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MapActionChip(label = "DETAILS", modifier = Modifier.weight(1f)) { showDetails = true }
                    if (home != null) {
                        MapActionChip(label = "DIRECTIONS", modifier = Modifier.weight(1f)) {
                            val dest = points.first()
                            context.startActivity(directionsIntent(home.first, home.second, dest.lat, dest.lon))
                        }
                    }
                }
            }
        }
    }

    if (showDetails) {
        SessionDetailsSheet(session, gpxPoints = state.gpxPoints, onDismiss = { showDetails = false })
    }
    if (showWeather) {
        WeatherSheet(weatherState, onDismiss = { showWeather = false })
    }
}

// osmdroid MapView wrapped for Compose -- CARTO's free Dark Matter raster
// tiles (basemaps.cartocdn.com), not the Google Maps SDK, so this needs no
// billing-enabled Cloud project (see the file header note). The route and
// its start/end markers are real lat/lon overlays on the actual map, not the
// stylized projected line the previous version of this screen drew on a
// bare Canvas.
@Composable
private fun OsmMapView(points: List<GpxPoint>, cartoKey: String, modifier: Modifier = Modifier) {
    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            Configuration.getInstance().apply {
                userAgentValue = ctx.packageName
                // App-specific directories -- no WRITE_EXTERNAL_STORAGE needed
                // on any supported API level, unlike osmdroid's older default.
                osmdroidBasePath = ctx.filesDir
                osmdroidTileCache = ctx.cacheDir
            }
            MapView(ctx).apply {
                setTileSource(
                    XYTileSource(
                        "CartoDarkMatter",
                        0,
                        20,
                        256,
                        ".png?key=$cartoKey",
                        arrayOf("https://basemaps.cartocdn.com/rastertiles/dark_all/"),
                    ),
                )
                setMultiTouchControls(true)
                zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)

                val geoPoints = points.map { GeoPoint(it.lat, it.lon) }
                overlays.add(
                    Polyline(this).apply {
                        setPoints(geoPoints)
                        outlinePaint.color = FieldColors.Cyan.toArgb()
                        outlinePaint.strokeWidth = 9f
                    },
                )
                overlays.add(dotMarker(this, geoPoints.first(), FieldColors.Green.toArgb()))
                overlays.add(dotMarker(this, geoPoints.last(), FieldColors.Magenta.toArgb()))

                // zoomToBoundingBox needs a laid-out view to compute a real
                // zoom level -- post() defers until after the first layout pass.
                post {
                    val box = BoundingBox.fromGeoPoints(geoPoints)
                    val latPad = (box.latNorth - box.latSouth).coerceAtLeast(0.001) * 0.2
                    val lonPad = (box.lonEast - box.lonWest).coerceAtLeast(0.001) * 0.2
                    zoomToBoundingBox(
                        BoundingBox(box.latNorth + latPad, box.lonEast + lonPad, box.latSouth - latPad, box.lonWest - lonPad),
                        false,
                    )
                }
            }
        },
    )
}

private fun dotMarker(mapView: MapView, point: GeoPoint, color: Int): Marker =
    Marker(mapView).apply {
        position = point
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        icon = ShapeDrawable(OvalShape()).apply {
            paint.color = color
            intrinsicWidth = 34
            intrinsicHeight = 34
            setBounds(0, 0, 34, 34)
        }
        setOnMarkerClickListener { _, _ -> true } // consume tap, suppress default info-window popup
    }

@Composable
private fun WeatherChip(state: WeatherUiState, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .background(FieldColors.Panel.copy(alpha = 0.92f))
            .border(1.dp, FieldColors.Hairline)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        when (state) {
            is WeatherUiState.Loaded -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(weatherCodeSymbol(state.weather.code), fontSize = 16.sp)
                Text("%.0f°".format(state.weather.temperatureC), style = FieldTextStyles.syncLabel, color = FieldColors.Ink)
            }
            WeatherUiState.Loading -> Text("···", style = FieldTextStyles.syncLabel, color = FieldColors.InkMuted)
            is WeatherUiState.Failed -> Text("WEATHER ⚠", style = FieldTextStyles.syncLabel, color = FieldColors.Alert)
            WeatherUiState.Idle -> Text("WEATHER", style = FieldTextStyles.syncLabel, color = FieldColors.InkMuted)
        }
    }
}

@Composable
private fun MapActionChip(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .background(FieldColors.Amber.copy(alpha = 0.14f))
            .border(1.dp, FieldColors.Amber)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = FieldTextStyles.subTabLabel, color = FieldColors.Amber)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionDetailsSheet(session: NextSession, gpxPoints: List<GpxPoint>?, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RectangleShape,
        containerColor = FieldColors.Panel,
        contentColor = FieldColors.Ink,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(sessionCardTitle(session.kind), style = FieldTextStyles.headerTitle, color = FieldColors.Amber)
            Text(
                text = session.title.trim(),
                style = TextStyle(fontFamily = Saira, fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
                color = FieldColors.Ink,
            )
            session.description.takeIf { it.isNotBlank() }?.let {
                Text(it, style = TextStyle(fontFamily = Saira, fontSize = 13.5.sp), color = FieldColors.InkMuted)
            }
            Text(
                text = formatSessionMeta(session),
                style = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium, fontSize = 13.sp),
                color = FieldColors.InkMuted,
            )
            if (gpxPoints != null) {
                Text(
                    text = routeSummary(gpxPoints),
                    style = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium, fontSize = 13.sp),
                    color = FieldColors.Cyan,
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WeatherSheet(state: WeatherUiState, onDismiss: () -> Unit) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RectangleShape,
        containerColor = FieldColors.Panel,
        contentColor = FieldColors.Ink,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("FORECAST AT SESSION TIME", style = FieldTextStyles.headerTitle, color = FieldColors.Amber)
            when (state) {
                is WeatherUiState.Loaded -> {
                    val w = state.weather
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(weatherCodeSymbol(w.code), fontSize = 32.sp)
                        Column {
                            Text(
                                text = "%.0f°C".format(w.temperatureC),
                                style = TextStyle(fontFamily = SairaCondensed, fontWeight = FontWeight.Bold, fontSize = 26.sp),
                                color = FieldColors.Ink,
                            )
                            Text(weatherCodeLabel(w.code), style = TextStyle(fontFamily = Saira, fontSize = 14.sp), color = FieldColors.InkMuted)
                        }
                    }
                    Text(
                        text = "Wind %.0f km/h · Precip %.1f mm".format(w.windSpeedKmh, w.precipitationMm),
                        style = TextStyle(fontFamily = JetBrainsMono, fontSize = 13.sp),
                        color = FieldColors.InkMuted,
                    )
                }
                WeatherUiState.Loading -> Box(Modifier.fillMaxWidth().padding(vertical = 30.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = FieldColors.Amber)
                }
                is WeatherUiState.Failed -> Text(
                    "Couldn't load the forecast (${state.message}).",
                    style = TextStyle(fontFamily = Saira, fontSize = 13.5.sp),
                    color = FieldColors.InkMuted,
                )
                WeatherUiState.Idle -> Text("—", style = TextStyle(fontFamily = Saira, fontSize = 13.5.sp), color = FieldColors.InkMuted)
            }
            Text(
                "Forecast, not a guarantee — Open-Meteo, no personal weather station.",
                style = TextStyle(fontFamily = Saira, fontSize = 11.5.sp),
                color = FieldColors.InkMuted,
            )
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun NoLocationCard(session: NextSession) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(sessionCardTitle(session.kind), style = FieldTextStyles.headerTitle, color = FieldColors.Amber)
        Text(
            text = session.title.trim(),
            style = TextStyle(fontFamily = Saira, fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
            color = FieldColors.Ink,
        )
        session.description.takeIf { it.isNotBlank() }?.let {
            Text(it, style = TextStyle(fontFamily = Saira, fontSize = 13.5.sp), color = FieldColors.InkMuted)
        }
        Text(
            text = formatSessionMeta(session),
            style = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium, fontSize = 13.sp),
            color = FieldColors.InkMuted,
        )
        Text(
            "Gym and strength sessions carry no location — no route file linked to this session.",
            style = TextStyle(fontFamily = Saira, fontSize = 12.5.sp),
            color = FieldColors.InkMuted,
        )
    }
}

// Consumer Google Maps deep link -- no API key, no billing. Doesn't accept a
// pre-filled arrival time (that parameter only exists in Google's separate,
// also-billed Directions API) -- the person sets "Arrive by" themselves once
// Maps opens with the route ready, per the user's own chosen trade-off.
private fun directionsIntent(homeLat: Double, homeLon: Double, destLat: Double, destLon: Double): Intent {
    val uri = Uri.parse(
        "https://www.google.com/maps/dir/?api=1&origin=$homeLat,$homeLon&destination=$destLat,$destLon&travelmode=driving",
    )
    return Intent(Intent.ACTION_VIEW, uri)
}

private fun sessionCardTitle(kind: String): String = when (kind) {
    "strength" -> "STRENGTH SESSION"
    "hill" -> "ENDURANCE · HILL LOOP"
    "trail run" -> "ENDURANCE · TRAIL RUN"
    "run" -> "ENDURANCE · RUN"
    else -> "TRAINING SESSION"
}

private fun formatHeaderDate(startIso: String): String =
    parseSessionZonedDateTime(startIso).format(DateTimeFormatter.ofPattern("EEE d MMM HH:mm")).uppercase()

private fun formatSessionMeta(session: NextSession): String {
    val dateText = formatHeaderDate(session.startIso)
    val whenLabel = when (val days = daysUntilSession(session.startIso)) {
        0L -> "TODAY"
        1L -> "TOMORROW"
        else -> if (days > 0) "IN $days DAYS" else "OVERDUE"
    }
    return "$dateText — $whenLabel"
}

// Real distance/elevation-gain, computed from the actual GPX points (not
// fabricated) -- now shown inside the details popup rather than as an
// always-visible caption over the map, since the map itself shows the route.
private fun routeSummary(points: List<GpxPoint>): String {
    val distanceKm = routeDistanceKm(points)
    val gainM = routeElevationGainM(points)
    return "%.1f KM".format(distanceKm) + (gainM?.let { " · %.0f M GAIN".format(it) } ?: "")
}
