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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.bioscan.fieldterminal.auth.GoogleAuthorizationManager
import com.bioscan.fieldterminal.data.AddEntryRepository
import com.bioscan.fieldterminal.data.GeocodingRepository
import com.bioscan.fieldterminal.data.MapRepository
import com.bioscan.fieldterminal.data.MapSettingsStore
import com.bioscan.fieldterminal.data.PeopleRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.WeatherRepository
import com.bioscan.fieldterminal.data.model.PersonRow
import com.bioscan.fieldterminal.domain.GpxPoint
import com.bioscan.fieldterminal.domain.MapEvent
import com.bioscan.fieldterminal.domain.MapEventCategory
import com.bioscan.fieldterminal.domain.MapPin
import com.bioscan.fieldterminal.domain.SessionWeather
import com.bioscan.fieldterminal.domain.TRAINING_COLOR_ID
import com.bioscan.fieldterminal.domain.classifyMapEvent
import com.bioscan.fieldterminal.domain.classifySessionKind
import com.bioscan.fieldterminal.domain.parseSessionZonedDateTime
import com.bioscan.fieldterminal.domain.routeDistanceKm
import com.bioscan.fieldterminal.domain.routeElevationGainM
import com.bioscan.fieldterminal.domain.weatherCodeLabel
import com.bioscan.fieldterminal.domain.weatherCodeSymbol
import com.bioscan.fieldterminal.ui.components.AmberButton
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FieldTextField
import com.bioscan.fieldterminal.ui.components.ScreenHeader
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import com.bioscan.fieldterminal.ui.theme.Inter
import com.bioscan.fieldterminal.ui.theme.RobotoMono
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

// Shared text styles for this screen's own chrome (sheet titles, compact
// action/status labels) -- DAV-108 follow-up migration off FieldTextStyles'
// legacy JetBrainsMono onto the FT contract's Inter/Roboto Mono split.
private val sheetHeaderTitleStyle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 20.sp)
private val sheetActionLabelStyle = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.SemiBold, fontSize = 11.5.sp, letterSpacing = 0.14f.em)

// Phase M1/M2 (Map tab rework, see ROADMAP.md). Replaces Step 14's single
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
private sealed interface MapState {
    data object CheckingAccess : MapState
    data class NeedsConsent(val pendingIntent: android.app.PendingIntent) : MapState
    data class Error(val message: String) : MapState
    data class Ready(val pins: List<MapPin>, val droppedCount: Int, val accessToken: String) : MapState
}

private sealed interface WeatherUiState {
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
        val readyState = state as? MapState.Ready
        ScreenHeader(
            title = "MAP",
            context = readyState?.let { "${it.pins.size} EVENT" + (if (it.pins.size == 1) "" else "S") + " · NEXT 24H" } ?: "—",
        )

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            when (val s = state) {
                is MapState.CheckingAccess -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = FT.DomainMap)
                }
                is MapState.NeedsConsent -> Box(Modifier.fillMaxSize().padding(22.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Text(
                            "Grant Calendar and Drive access to see your upcoming events on the map.",
                            style = TextStyle(fontFamily = Inter, fontSize = 15.5.sp),
                            color = FT.TextSecondary,
                        )
                        AmberButton(label = "GRANT ACCESS") {
                            consentLauncher.launch(IntentSenderRequest.Builder(s.pendingIntent.intentSender).build())
                        }
                    }
                }
                is MapState.Error -> Box(Modifier.fillMaxSize().padding(22.dp), contentAlignment = Alignment.Center) {
                    Text(s.message, style = TextStyle(fontFamily = Inter, fontSize = 15.5.sp), color = FT.Critical)
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
                style = TextStyle(fontFamily = Inter, fontSize = 15.5.sp),
                color = FT.TextSecondary,
            )
        }
        return
    }

    val context = LocalContext.current
    val cartoKey = remember { MapSettingsStore.getCartoKey(context) }
    val repo = remember(state.accessToken) { MapRepository(state.accessToken) }
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
    var showWeather by remember { mutableStateOf(false) }
    var weatherState by remember { mutableStateOf<WeatherUiState>(WeatherUiState.Idle) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(selectedPin) {
        routePoints = null
        routeError = null
        weatherState = WeatherUiState.Idle
        val pin = selectedPin
        val gpxLink = pin?.event?.gpxLink
        if (pin != null && pin.event.colorId == TRAINING_COLOR_ID && gpxLink != null) {
            try {
                routePoints = repo.fetchGpxPoints(gpxLink)
            } catch (e: Exception) {
                routeError = e.message
            }
        }
    }

    Box(Modifier.fillMaxSize()) {
        if (cartoKey == null) {
            Box(Modifier.fillMaxSize().padding(22.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Add a free CARTO API key in Settings to show the map background (carto.com/basemaps/apikey — no billing).",
                    style = TextStyle(fontFamily = Inter, fontSize = 15.5.sp),
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
                            Text(message, style = TextStyle(fontFamily = Inter, fontSize = 12.sp), color = FT.TextSecondary)
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
        modifier = modifier,
        factory = { ctx ->
            Configuration.getInstance().apply {
                userAgentValue = ctx.packageName
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

                pins.forEach { pin ->
                    val category = classifyMapEvent(pin.event.colorId, pin.event.title)
                    // Training pins use Training's own domain accent (Emerald) --
                    // the same color that section's own screens/nav-tab use, not
                    // an arbitrary map-only hue. Encounter/Social reuse the same
                    // red the Log feed's own TypeChip already uses for that
                    // category, so the two screens read as the same category
                    // consistently. Other/uncategorized defaults to Map's own
                    // domain accent (Blue) -- a generic pin belongs to Map itself.
                    val color = when (category) {
                        MapEventCategory.Training -> FT.DomainTraining.toArgb()
                        MapEventCategory.Encounter, MapEventCategory.Social -> FT.Critical.toArgb()
                        MapEventCategory.Other -> FT.DomainMap.toArgb()
                    }
                    overlays.add(pinMarker(this, GeoPoint(pin.lat, pin.lon), color) { onPinClick(pin) })
                }

                post {
                    val geoPoints = pins.map { GeoPoint(it.lat, it.lon) }
                    val box = BoundingBox.fromGeoPoints(geoPoints)
                    val latPad = (box.latNorth - box.latSouth).coerceAtLeast(0.005) * 0.3
                    val lonPad = (box.lonEast - box.lonWest).coerceAtLeast(0.005) * 0.3
                    zoomToBoundingBox(
                        BoundingBox(box.latNorth + latPad, box.lonEast + lonPad, box.latSouth - latPad, box.lonWest - lonPad),
                        false,
                    )
                }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PinDetailSheet(
    pin: MapPin,
    routePoints: List<GpxPoint>?,
    routeError: String?,
    weatherState: WeatherUiState,
    onRequestWeather: () -> Unit,
    onDismiss: () -> Unit,
) {
    val event = pin.event
    val category = remember(event.id, event.colorId, event.title) { classifyMapEvent(event.colorId, event.title) }
    val context = LocalContext.current
    val home = remember { MapSettingsStore.getHome(context) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        shape = RectangleShape,
        containerColor = FT.Surface,
        contentColor = FT.TextPrimary,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                when (category) {
                    MapEventCategory.Training -> sessionCardTitle(classifySessionKind(event.title))
                    MapEventCategory.Encounter -> "ENCOUNTER"
                    MapEventCategory.Social -> "SOCIAL EVENT"
                    MapEventCategory.Other -> "EVENT"
                },
                style = sheetHeaderTitleStyle,
                color = FT.DomainMap,
            )
            Text(
                text = event.title.trim().ifBlank { "Untitled event" },
                style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
                color = FT.TextPrimary,
            )
            event.description.takeIf { it.isNotBlank() }?.let {
                Text(it, style = TextStyle(fontFamily = Inter, fontSize = 13.5.sp), color = FT.TextSecondary)
            }
            Text(
                text = formatEventMeta(event),
                style = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.Medium, fontSize = 13.sp),
                color = FT.TextSecondary,
            )
            Text(
                text = if (pin.isHomeFallback) "Pinned at home — this event has no location." else (event.location ?: ""),
                style = TextStyle(fontFamily = Inter, fontSize = 12.5.sp),
                color = FT.TextSecondary,
            )

            when (category) {
                MapEventCategory.Training -> {
                    when {
                        routeError != null -> Text(
                            "Couldn't load the route (${routeError}).",
                            style = TextStyle(fontFamily = Inter, fontSize = 13.sp),
                            color = FT.TextSecondary,
                        )
                        routePoints != null -> Text(
                            routeSummary(routePoints),
                            style = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.Medium, fontSize = 13.sp),
                            color = FT.DomainTraining,
                        )
                        event.gpxLink == null -> Text(
                            "No route file linked to this session.",
                            style = TextStyle(fontFamily = Inter, fontSize = 12.5.sp),
                            color = FT.TextSecondary,
                        )
                    }
                    WeatherRow(weatherState, onClick = onRequestWeather)
                }
                MapEventCategory.Encounter, MapEventCategory.Social -> PartnerSection(event)
                MapEventCategory.Other -> {}
            }

            if (!pin.isHomeFallback && home != null) {
                AmberButton(label = "DIRECTIONS") {
                    context.startActivity(directionsIntent(home.first, home.second, pin.lat, pin.lon))
                }
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}

// Phase M2. NoMatch shows a search field over the 168-row `people` table
// plus a create-new action -- per the user's own choice, not create-only,
// since a typo or nickname in the calendar title shouldn't force a
// duplicate partner record. Deliberately no "change match" action once
// Matched -- a wrong auto-match is a real, accepted rough edge for this
// phase's "keep it minimal" scope, not solved here.
private sealed interface PartnerUiState {
    data object Loading : PartnerUiState
    data class Matched(val person: PersonRow) : PartnerUiState
    data object NoMatch : PartnerUiState
    data class Error(val message: String) : PartnerUiState
}

@Composable
private fun PartnerSection(event: MapEvent) {
    val repo = remember { PeopleRepository(SupabaseClientProvider.client) }
    val scope = rememberCoroutineScope()
    var state by remember(event.id) { mutableStateOf<PartnerUiState>(PartnerUiState.Loading) }
    var query by remember(event.id) { mutableStateOf("") }
    var searchResults by remember(event.id) { mutableStateOf<List<PersonRow>>(emptyList()) }
    var creating by remember(event.id) { mutableStateOf(false) }

    LaunchedEffect(event.id, event.title) {
        state = PartnerUiState.Loading
        state = try {
            repo.findByNameInTitle(event.title)?.let { PartnerUiState.Matched(it) } ?: PartnerUiState.NoMatch
        } catch (e: Exception) {
            PartnerUiState.Error(e.message ?: "Couldn't check for a matching partner.")
        }
    }

    LaunchedEffect(query, state) {
        searchResults = if (state is PartnerUiState.NoMatch && query.isNotBlank()) {
            try {
                repo.search(query)
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            emptyList()
        }
    }

    FTCard(title = "PARTNER") {
        when (val s = state) {
            PartnerUiState.Loading -> Text(
                "Checking this event's title for a partner match…",
                style = TextStyle(fontFamily = Inter, fontSize = 13.sp),
                color = FT.TextSecondary,
            )
            is PartnerUiState.Matched -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    s.person.name,
                    style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
                    color = FT.TextPrimary,
                )
                EncounterLogRow(event = event, personId = s.person.id)
            }
            is PartnerUiState.Error -> Text(
                "Couldn't check for a matching partner (${s.message}).",
                style = TextStyle(fontFamily = Inter, fontSize = 13.sp),
                color = FT.TextSecondary,
            )
            PartnerUiState.NoMatch -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "No partner matched in this event's title.",
                    style = TextStyle(fontFamily = Inter, fontSize = 13.sp),
                    color = FT.TextSecondary,
                )
                FieldTextField(value = query, onValueChange = { query = it }, placeholder = "Search partners…")
                searchResults.forEach { person ->
                    Text(
                        person.name,
                        style = TextStyle(fontFamily = Inter, fontSize = 14.sp),
                        color = FT.DomainMap,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                                state = PartnerUiState.Matched(person)
                            }
                            .padding(vertical = 6.dp),
                    )
                }
                if (query.isNotBlank() && !creating) {
                    AmberButton(label = "NEW PARTNER: \"${query.trim()}\"") {
                        creating = true
                        scope.launch {
                            state = try {
                                PartnerUiState.Matched(repo.createPerson(query.trim()))
                            } catch (e: Exception) {
                                PartnerUiState.Error(e.message ?: "Couldn't create this partner.")
                            } finally {
                                creating = false
                            }
                        }
                    }
                }
            }
        }
    }
}

// Phase M3. Minimal by the user's own choice -- date (from the event) +
// optional type/notes, same 3 real fields the Log tab's own EncounterForm
// collects (see AddEntrySheet.kt), just with person_id and the real
// calendar title threaded through too. The encounters table's richer unused
// columns (activities, ratings, location_type, duration) stay untouched, as
// does `status` (left at its 'logged' default -- the 'pending' state is
// explicitly not used by this phase).
@Composable
private fun EncounterLogRow(event: MapEvent, personId: Long) {
    var type by remember(event.id) { mutableStateOf("") }
    var notes by remember(event.id) { mutableStateOf("") }
    var saving by remember(event.id) { mutableStateOf(false) }
    var saved by remember(event.id) { mutableStateOf(false) }
    var error by remember(event.id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    if (saved) {
        Text(
            "Encounter logged.",
            style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp),
            color = FT.Emerald,
        )
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("LOG ENCOUNTER", style = sheetActionLabelStyle, color = FT.TextSecondary)
        FieldTextField(value = type, onValueChange = { type = it }, placeholder = "Type (optional) — e.g. date, call, hangout")
        FieldTextField(value = notes, onValueChange = { notes = it }, placeholder = "Notes (optional)", singleLine = false)
        error?.let {
            Text("Couldn't log this encounter (${it}).", style = TextStyle(fontFamily = Inter, fontSize = 12.5.sp), color = FT.TextSecondary)
        }
        AmberButton(label = if (saving) "SAVING…" else "LOG ENCOUNTER") {
            if (!saving) {
                saving = true
                error = null
                scope.launch {
                    try {
                        val date = parseSessionZonedDateTime(event.startIso).toLocalDate().toString()
                        AddEntryRepository(SupabaseClientProvider.client).addEncounter(
                            date = date,
                            encounterType = type.trim().ifBlank { null },
                            notes = notes.trim().ifBlank { null },
                            personId = personId,
                            calendarEventTitle = event.title,
                        )
                        saved = true
                    } catch (e: Exception) {
                        error = e.message ?: "Unknown error."
                    } finally {
                        saving = false
                    }
                }
            }
        }
    }
}

@Composable
private fun WeatherRow(state: WeatherUiState, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .border(FT.BorderWidth, FT.GlassBorder, RoundedCornerShape(FT.RadiusSmall))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("WEATHER AT SESSION TIME", style = sheetActionLabelStyle, color = FT.TextSecondary)
        when (state) {
            is WeatherUiState.Loaded -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(weatherCodeSymbol(state.weather.code), fontSize = 16.sp)
                Text("%.0f°".format(state.weather.temperatureC), style = sheetActionLabelStyle, color = FT.TextPrimary)
            }
            WeatherUiState.Loading -> Text("···", style = sheetActionLabelStyle, color = FT.TextSecondary)
            is WeatherUiState.Failed -> Text("⚠", style = sheetActionLabelStyle, color = FT.Critical)
            WeatherUiState.Idle -> Text("VIEW", style = sheetActionLabelStyle, color = FT.DomainMap)
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
        containerColor = FT.Surface,
        contentColor = FT.TextPrimary,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("FORECAST AT SESSION TIME", style = sheetHeaderTitleStyle, color = FT.DomainMap)
            when (state) {
                is WeatherUiState.Loaded -> {
                    val w = state.weather
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(weatherCodeSymbol(w.code), fontSize = 32.sp)
                        Column {
                            Text(
                                text = "%.0f°C".format(w.temperatureC),
                                style = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 26.sp),
                                color = FT.TextPrimary,
                            )
                            Text(weatherCodeLabel(w.code), style = TextStyle(fontFamily = Inter, fontSize = 14.sp), color = FT.TextSecondary)
                        }
                    }
                    Text(
                        text = "Wind %.0f km/h · Precip %.1f mm".format(w.windSpeedKmh, w.precipitationMm),
                        style = TextStyle(fontFamily = RobotoMono, fontSize = 13.sp),
                        color = FT.TextSecondary,
                    )
                }
                WeatherUiState.Loading -> Box(Modifier.fillMaxWidth().padding(vertical = 30.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = FT.DomainMap)
                }
                is WeatherUiState.Failed -> Text(
                    "Couldn't load the forecast (${state.message}).",
                    style = TextStyle(fontFamily = Inter, fontSize = 13.5.sp),
                    color = FT.TextSecondary,
                )
                WeatherUiState.Idle -> Text("—", style = TextStyle(fontFamily = Inter, fontSize = 13.5.sp), color = FT.TextSecondary)
            }
            Text(
                "Forecast, not a guarantee — Open-Meteo, no personal weather station.",
                style = TextStyle(fontFamily = Inter, fontSize = 11.5.sp),
                color = FT.TextSecondary,
            )
            Spacer(Modifier.height(12.dp))
        }
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

private fun formatEventMeta(event: MapEvent): String {
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
private fun routeSummary(points: List<GpxPoint>): String {
    val distanceKm = routeDistanceKm(points)
    val gainM = routeElevationGainM(points)
    return "%.1f KM".format(distanceKm) + (gainM?.let { " · %.0f M GAIN".format(it) } ?: "")
}
