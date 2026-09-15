package com.bioscan.fieldterminal.ui.screens

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bioscan.fieldterminal.auth.GoogleAuthorizationManager
import com.bioscan.fieldterminal.data.MapRepository
import com.bioscan.fieldterminal.domain.GpxPoint
import com.bioscan.fieldterminal.domain.NextSession
import com.bioscan.fieldterminal.domain.daysUntilSession
import com.bioscan.fieldterminal.domain.parseSessionZonedDateTime
import com.bioscan.fieldterminal.domain.routeDistanceKm
import com.bioscan.fieldterminal.domain.routeElevationGainM
import com.bioscan.fieldterminal.ui.components.AmberButton
import com.bioscan.fieldterminal.ui.components.Card
import com.bioscan.fieldterminal.ui.components.ScreenHeader
import com.bioscan.fieldterminal.ui.theme.FieldColors
import com.bioscan.fieldterminal.ui.theme.FieldTextStyles
import com.bioscan.fieldterminal.ui.theme.JetBrainsMono
import com.bioscan.fieldterminal.ui.theme.Saira
import com.google.android.gms.common.api.ApiException
import kotlinx.coroutines.launch
import java.time.format.DateTimeFormatter
import kotlin.math.cos
import kotlin.math.max

// Step 14. Real "next training session" from the signed-in user's Google
// Calendar plus its linked GPX route from Drive -- see domain/NextSession.kt
// and data/MapRepository.kt for the porting notes, and
// auth/GoogleAuthorizationManager.kt for why this needs its own on-device
// authorization step distinct from sign-in. Mirrors design/Field Terminal
// Mockups.dc.html's "Map · next session" panel, minus the mockup's invented
// pace-target/weather numbers -- neither has a real data source in this app
// yet (weather is a separate, un-built P2 scope on Android), so they're
// omitted rather than fabricated, same principle as every other Status tab.
private sealed interface MapState {
    data object CheckingAccess : MapState
    data class NeedsConsent(val pendingIntent: android.app.PendingIntent) : MapState
    data class Error(val message: String) : MapState
    data class Ready(val session: NextSession?, val gpxPoints: List<GpxPoint>?, val gpxError: String?) : MapState
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
            is MapState.Ready -> MapContent(s)
        }
    }
}

@Composable
private fun MapContent(state: MapState.Ready) {
    val session = state.session
    if (session == null) {
        Box(Modifier.fillMaxSize().padding(22.dp), contentAlignment = Alignment.Center) {
            Text("No training session in the next 7 days.", style = FieldTextStyles.placeholderBody, color = FieldColors.InkMuted)
        }
        return
    }

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (session.gpxLink != null) {
            RouteArea(points = state.gpxPoints, error = state.gpxError)
        }

        Column(
            modifier = Modifier.padding(horizontal = 22.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Card(title = sessionCardTitle(session.kind)) {
                Text(
                    text = session.title.trim(),
                    style = TextStyle(fontFamily = Saira, fontWeight = FontWeight.SemiBold, fontSize = 18.sp),
                    color = FieldColors.Ink,
                )
                session.description.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = TextStyle(fontFamily = Saira, fontSize = 13.5.sp),
                        color = FieldColors.InkMuted,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                Text(
                    text = formatSessionMeta(session),
                    style = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium, fontSize = 13.sp),
                    color = FieldColors.InkMuted,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }

            if (session.gpxLink == null) {
                Text(
                    text = "Gym and strength sessions carry no location — no route file linked to this session.",
                    style = TextStyle(fontFamily = Saira, fontSize = 12.5.sp),
                    color = FieldColors.InkMuted,
                )
            }
        }
    }
}

@Composable
private fun RouteArea(points: List<GpxPoint>?, error: String?) {
    Box(modifier = Modifier.fillMaxWidth().height(280.dp).background(FieldColors.Panel)) {
        when {
            error != null -> Box(Modifier.fillMaxSize().padding(22.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Couldn't load the route ($error).",
                    style = FieldTextStyles.placeholderBody,
                    color = FieldColors.InkMuted,
                )
            }
            points == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = FieldColors.Amber)
            }
            else -> {
                RouteCanvas(points, modifier = Modifier.fillMaxSize())
                val distanceKm = routeDistanceKm(points)
                val gainM = routeElevationGainM(points)
                val caption = "GPX ROUTE · %.1f KM".format(distanceKm) + (gainM?.let { " · %.0f M GAIN".format(it) } ?: "")
                Text(
                    text = caption,
                    style = FieldTextStyles.syncLabel,
                    color = FieldColors.InkMuted,
                    modifier = Modifier.align(Alignment.BottomStart).padding(14.dp),
                )
            }
        }
    }
}

// Same visual convention as the web dashboard's drawGpxRoute() -- a small
// stylized route line (cyan path, green/magenta start/end dots), not a real
// embedded map, which would need its own tile API/key and would clash with
// the hologram look everywhere else in this app. Same cos(latitude)
// longitude-scaling so the route isn't horizontally stretched at this
// latitude, ported 1:1 from that function's math.
@Composable
private fun RouteCanvas(points: List<GpxPoint>, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        if (points.size < 2) return@Canvas
        val pad = 24f
        val lats = points.map { it.lat }
        val lons = points.map { it.lon }
        val latMin = lats.min()
        val latMax = lats.max()
        val lonMin = lons.min()
        val lonMax = lons.max()
        val avgLatRad = Math.toRadians((latMin + latMax) / 2.0)
        val lonSpan = max((lonMax - lonMin) * cos(avgLatRad), 1e-6)
        val latSpan = max(latMax - latMin, 1e-6)
        val scale = minOf((size.width - pad * 2) / lonSpan, (size.height - pad * 2) / latSpan)

        fun xFor(lon: Double) = (size.width / 2 + (lon - (lonMin + lonMax) / 2) * cos(avgLatRad) * scale).toFloat()
        fun yFor(lat: Double) = (size.height / 2 - (lat - (latMin + latMax) / 2) * scale).toFloat()

        val path = Path().apply {
            points.forEachIndexed { i, p ->
                val x = xFor(p.lon)
                val y = yFor(p.lat)
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        drawPath(path, color = FieldColors.Cyan, style = Stroke(width = 5f, cap = StrokeCap.Round, join = StrokeJoin.Round))

        val first = points.first()
        val last = points.last()
        drawCircle(FieldColors.Green, radius = 9f, center = Offset(xFor(first.lon), yFor(first.lat)))
        drawCircle(FieldColors.Magenta, radius = 9f, center = Offset(xFor(last.lon), yFor(last.lat)))
    }
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
