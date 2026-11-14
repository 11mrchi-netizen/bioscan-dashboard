package com.bioscan.fieldterminal.ui.screens

import android.app.Activity
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.shapes.OvalShape
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.bioscan.fieldterminal.auth.GoogleAuthorizationManager
import com.bioscan.fieldterminal.data.GeocodingRepository
import com.bioscan.fieldterminal.data.MapRepository
import com.bioscan.fieldterminal.data.MapSettingsStore
import com.bioscan.fieldterminal.data.PlannedRouteRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.WeatherRepository
import com.bioscan.fieldterminal.data.model.PlannedRouteRow
import com.bioscan.fieldterminal.domain.GpxPoint
import com.bioscan.fieldterminal.domain.MapEvent
import com.bioscan.fieldterminal.domain.MapEventCategory
import com.bioscan.fieldterminal.domain.MapPin
import com.bioscan.fieldterminal.domain.SessionWeather
import com.bioscan.fieldterminal.domain.TRAINING_COLOR_ID
import com.bioscan.fieldterminal.domain.classifyMapEvent
import com.bioscan.fieldterminal.domain.parseSessionZonedDateTime
import com.bioscan.fieldterminal.domain.routeDistanceKm
import com.bioscan.fieldterminal.domain.routeElevationGainM
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.fitTo
import com.bioscan.fieldterminal.ui.components.stableMapView
import com.bioscan.fieldterminal.ui.theme.FTType
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// Replaces Step 14's single
// "next training session" view with a real multi-pin map of every calendar
// event in the next 24h -- events with a real address are geocoded (see
// data/GeocodingRepository.kt); events with none, or whose address fails to
// geocode, pin at the user's home coordinates (already stored as raw
// lat/lon in Settings, so no geocoding is needed for that fallback).
// Training events (colorId '8') keep their GPX route/weather/directions
// detail; Flamingo events (see domain/MapEvent.kt's classifyMapEvent) are
// Encounter/Social and get a partner match/search/create section instead
// (see PartnerSection below). Logging a real encounter from one of these is
// Phase M3, not built here.
internal sealed interface MapState {
    data object CheckingAccess : MapState
    data class NeedsConsent(val pendingIntent: PendingIntent) : MapState
    data class Error(val message: String) : MapState
    data class Ready(val pins: List<MapPin>, val droppedCount: Int, val accessToken: String) : MapState
}

internal sealed interface WeatherUiState {
    data object Idle : WeatherUiState
    data object Loading : WeatherUiState
    data class Loaded(val weather: SessionWeather) : WeatherUiState
    data class Failed(val message: String) : WeatherUiState
}

@Composable
fun MapScreen(focusEventId: String? = null) {
    val activity = LocalContext.current as Activity
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<MapState>(MapState.CheckingAccess) }

    suspend fun loadWithToken(token: String) {
        try {
            val repo = MapRepository(token)
            val events = repo.fetchUpcomingEvents()
            val geocoder = GeocodingRepository()
            val home = MapSettingsStore.getHome(activity)

            val resolved = coroutineScope {
                events.map { event ->
                    async {
                        val geocoded = event.location?.let { geocoder.geocode(it) }
                        when {
                            geocoded != null -> MapPin(event, geocoded.first, geocoded.second, isHomeFallback = false)
                            home != null -> MapPin(event, home.first, home.second, isHomeFallback = true)
                            else -> null
                        }
                    }
                }.map { it.await() }
            }
            val pins = resolved.filterNotNull()
            state = MapState.Ready(pins, droppedCount = resolved.size - pins.size, accessToken = token)
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

    Column(modifier = Modifier.fillMaxSize().background(FT.Base)) {
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when (val s = state) {
                is MapState.CheckingAccess -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = FT.DomainMap)
                }
                is MapState.NeedsConsent -> Box(Modifier.fillMaxSize().padding(22.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(
                            "Grant Calendar and Drive access to see your upcoming events on the map.",
                            style = FTType.Body,
                            color = FT.TextSecondary,
                        )
                        FTButton(label = "GRANT ACCESS") {
                            consentLauncher.launch(IntentSenderRequest.Builder(s.pendingIntent.intentSender).build())
                        }
                    }
                }
                is MapState.Error -> Box(Modifier.fillMaxSize().padding(22.dp), contentAlignment = Alignment.Center) {
                    Text(s.message, style = FTType.Body, color = FT.Bad)
                }
                is MapState.Ready -> MapReadyContent(s, focusEventId)
            }
        }
    }
}

@Composable
private fun MapReadyContent(state: MapState.Ready, focusEventId: String? = null) {
    if (state.pins.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(22.dp), contentAlignment = Alignment.Center) {
            Text(
                if (state.droppedCount > 0) {
                    "${state.droppedCount} event(s) in the next 24 hours have no location and no home location is set — add one in Settings to see them here."
                } else {
                    "No calendar events in the next 24 hours."
                },
                style = FTType.Body,
                color = FT.TextSecondary,
            )
        }
        return
    }

    val context = LocalContext.current
    val cartoKey = remember { MapSettingsStore.getCartoKey(context) }
    val repo = remember(state.accessToken) { MapRepository(state.accessToken) }
    // DAV-149. Read-only lookup of whatever DAV-148's pipeline already
    // computed for this event, if anything -- this screen never triggers a
    // download/recompute itself.
    val plannedRouteRepo = remember(state.accessToken) { PlannedRouteRepository(state.accessToken, SupabaseClientProvider.client) }
    // DAV-69: Status's NEXT UP band passes the tapped event's real Calendar
    // event ID here (via FieldTerminalNavHost's SavedStateHandle relay) so
    // Map can open straight to that event's detail sheet. This fetch's own
    // pins (this 24h window, geocoded/home-fallback/dropped) aren't
    // guaranteed to contain a match -- Status's clock and Map's could
    // disagree by the time the user taps, or the event never resolved to a
    // pin at all. A silent no-match was rejected in favor of a real, visible
    // "couldn't find that event" notice (see the banner below) -- landing on
    // a blank map with no explanation would read as broken, not empty.
    var selectedPin by remember {
        mutableStateOf(focusEventId?.let { id -> state.pins.find { it.event.id == id } })
    }
    val focusNotFound = remember { focusEventId != null && state.pins.none { it.event.id == focusEventId } }
    var routePoints by remember { mutableStateOf<List<GpxPoint>?>(null) }
    var routeError by remember { mutableStateOf<String?>(null) }
    var plannedPreview by remember { mutableStateOf<PlannedRouteRow?>(null) }
    var showWeather by remember { mutableStateOf(false) }
    var weatherState by remember { mutableStateOf<WeatherUiState>(WeatherUiState.Idle) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(selectedPin) {
        routePoints = null
        routeError = null
        plannedPreview = null
        weatherState = WeatherUiState.Idle
        val pin = selectedPin
        val gpxLink = pin?.event?.gpxLink
        if (pin != null && pin.event.colorId == TRAINING_COLOR_ID) {
            val eventId = pin.event.id
            if (eventId != null) {
                plannedPreview = try {
                    plannedRouteRepo.loadPreview(eventId)
                } catch (e: Exception) {
                    null
                }
            }
            if (gpxLink != null) {
                try {
                    routePoints = repo.fetchGpxPoints(gpxLink)
                } catch (e: Exception) {
                    routeError = e.message
                }
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (cartoKey == null) {
            Box(Modifier.fillMaxSize().padding(22.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Add a free CARTO API key in Settings to show the map background (carto.com/basemaps/apikey — no billing).",
                    style = FTType.Body,
                    color = FT.TextSecondary,
                )
            }
        } else {
            MultiPinMapView(
                pins = state.pins,
                routePoints = routePoints,
                cartoKey = cartoKey,
                onPinClick = { selectedPin = it },
                modifier = Modifier.fillMaxSize(),
            )
            val banners = buildList {
                if (state.droppedCount > 0) {
                    add("${state.droppedCount} event(s) couldn't be placed — set a home location in Settings.")
                }
                if (focusNotFound) {
                    add("Couldn't find that event on the map — it may have no location, or fell outside this 24h window.")
                }
            }
            if (banners.isNotEmpty()) {
                Column(
                    modifier = Modifier.align(Alignment.TopStart).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    banners.forEach { message ->
                        Box(
                            modifier = Modifier
                                .background(FT.Surface.copy(alpha = 0.92f), RoundedCornerShape(FT.RadiusSmall))
                                .border(FT.BorderWidth, FT.GlassBorder, RoundedCornerShape(FT.RadiusSmall))
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(message, style = FTType.Caption, color = FT.TextSecondary)
                        }
                    }
                }
            }
        }
    }

    selectedPin?.let { pin ->
        PinDetailSheet(
            pin = pin,
            routePoints = routePoints,
            routeError = routeError,
            plannedPreview = plannedPreview,
            weatherState = weatherState,
            onRequestWeather = {
                showWeather = true
                if (weatherState !is WeatherUiState.Loaded) {
                    val at = parseSessionZonedDateTime(pin.event.startIso)
                    weatherState = WeatherUiState.Loading
                    scope.launch {
                        weatherState = try {
                            WeatherUiState.Loaded(WeatherRepository().fetchAt(pin.lat, pin.lon, at))
                        } catch (e: Exception) {
                            WeatherUiState.Failed(e.message ?: "Weather request failed.")
                        }
                    }
                }
            },
            onDismiss = { selectedPin = null },
        )
    }
    if (showWeather) {
        WeatherSheet(weatherState, onDismiss = { showWeather = false })
    }
}

// osmdroid MapView wrapped for Compose -- same CARTO Dark Matter tile setup
// as Step 14, but one Marker per MapPin instead of a single route's start/end
// dots, and the currently-selected training pin's route (if any) drawn as a
// Polyline via AndroidView's update callback, so selecting a different pin
// swaps the route without recreating the whole map (which would otherwise
// reset the user's own pan/zoom).
@Composable
private fun MultiPinMapView(
    pins: List<MapPin>,
    routePoints: List<GpxPoint>?,
    cartoKey: String,
    onPinClick: (MapPin) -> Unit,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        modifier = modifier.clipToBounds(),
        onRelease = { it.onDetach() },
        factory = { ctx ->
            stableMapView(ctx).apply {
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
                pins.forEach { pin ->
                    val category = classifyMapEvent(pin.event.colorId, pin.event.title)
                    // A pin's hue is its kind, from the same category palette the Log's type chips use
                    // (FT.Category): training is activity, an encounter or a social event is arousal. Red is a
                    // status ("critical") and never a kind. Other/uncategorized takes Map's own domain accent.
                    val color = when (category) {
                        MapEventCategory.Training -> FT.Category.Activity.c500.toArgb()
                        MapEventCategory.Encounter, MapEventCategory.Social -> FT.Category.Arousal.c500.toArgb()
                        MapEventCategory.Other -> FT.DomainMap.toArgb()
                    }
                    overlays.add(pinMarker(this, GeoPoint(pin.lat, pin.lon), color) { onPinClick(pin) })
                }

                fitTo(pins.map { GeoPoint(it.lat, it.lon) }, pad = 0.3, minSpanDeg = 0.005)
            }
        },
        update = { mapView ->
            mapView.overlays.removeAll { it is Polyline }
            if (routePoints != null && routePoints.size >= 2) {
                mapView.overlays.add(
                    Polyline(mapView).apply {
                        setPoints(routePoints.map { GeoPoint(it.lat, it.lon) })
                        outlinePaint.color = FT.DomainTraining.toArgb() // matches the Training pin's own color above
                        outlinePaint.strokeWidth = 9f
                    },
                )
            }
            mapView.invalidate()
        },
    )
}

private fun pinMarker(mapView: MapView, point: GeoPoint, color: Int, onClick: () -> Unit): Marker =
    Marker(mapView).apply {
        position = point
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        icon = ShapeDrawable(OvalShape()).apply {
            paint.color = color
            intrinsicWidth = 40
            intrinsicHeight = 40
            setBounds(0, 0, 40, 40)
        }
        setOnMarkerClickListener { _, _ -> onClick(); true }
    }

// Consumer Google Maps deep link -- no API key, no billing. Doesn't accept a
// pre-filled arrival time (that parameter only exists in Google's separate,
// also-billed Directions API) -- the person sets "Arrive by" themselves once
// Maps opens with the route ready, per the user's own chosen trade-off.
internal fun directionsIntent(homeLat: Double, homeLon: Double, destLat: Double, destLon: Double): Intent {
    val uri = Uri.parse(
        "https://www.google.com/maps/dir/?api=1&origin=$homeLat,$homeLon&destination=$destLat,$destLon&travelmode=driving",
    )
    return Intent(Intent.ACTION_VIEW, uri)
}

internal fun sessionCardTitle(kind: String): String = when (kind) {
    "strength" -> "STRENGTH SESSION"
    "hill" -> "ENDURANCE · HILL LOOP"
    "trail run" -> "ENDURANCE · TRAIL RUN"
    "run" -> "ENDURANCE · RUN"
    else -> "TRAINING SESSION"
}

internal fun formatEventMeta(event: MapEvent): String {
    val start = parseSessionZonedDateTime(event.startIso)
    val dateText = start.format(DateTimeFormatter.ofPattern("EEE d MMM HH:mm")).uppercase()
    val minutesUntil = ChronoUnit.MINUTES.between(ZonedDateTime.now(start.zone), start)
    val whenLabel = when {
        minutesUntil <= 0 -> "NOW / IN PROGRESS"
        minutesUntil < 60 -> "IN $minutesUntil MIN"
        else -> "IN ${minutesUntil / 60}H ${minutesUntil % 60}M"
    }
    return "$dateText — $whenLabel"
}

// Real distance/elevation-gain, computed from the actual GPX points (not
// fabricated).
internal fun routeSummary(points: List<GpxPoint>): String {
    val distanceKm = routeDistanceKm(points)
    val gainM = routeElevationGainM(points)
    return "%.1f KM".format(distanceKm) + (gainM?.let { " · %.0f M GAIN".format(it) } ?: "")
}

// DAV-149. The richer cached preview -- doc 03/04's real terrain engine,
// not this file's own naive haversine/elevation-delta math above. Only
// non-null figures render; a preview with just distance (no real elevation
// data in the GPX) still shows something real rather than nothing.
internal fun plannedRouteSummary(preview: PlannedRouteRow): String {
    val parts = mutableListOf<String>()
    preview.distanceM?.let { parts += "%.1f KM".format(it / 1000.0) }
    preview.elevationGainM?.let { parts += "%.0f M GAIN".format(it) }
    preview.mountainIndex?.let { parts += "MI %.0f".format(it) }
    preview.kmEffort?.let { parts += "KE %.1f".format(it) }
    return parts.joinToString(" · ").ifBlank { "Preview available, no course-demand figures yet." }
}
