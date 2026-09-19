package com.bioscan.fieldterminal.ui.screens.status

import android.app.Activity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.auth.GoogleAuthorizationManager
import com.bioscan.fieldterminal.data.MapRepository
import com.bioscan.fieldterminal.data.StatusOverview
import com.bioscan.fieldterminal.domain.MapEvent
import com.bioscan.fieldterminal.domain.ReadinessLabel
import com.bioscan.fieldterminal.domain.parseSessionZonedDateTime
import com.bioscan.fieldterminal.ui.nav.TileRoute
import com.bioscan.fieldterminal.ui.theme.FieldColors
import com.bioscan.fieldterminal.ui.theme.FieldTextStyles
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import com.bioscan.fieldterminal.ui.theme.Inter
import com.bioscan.fieldterminal.ui.theme.JetBrainsMono
import com.bioscan.fieldterminal.ui.theme.RobotoMono
import com.bioscan.fieldterminal.ui.theme.SairaCondensed
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

// design/README.md's "3d — Body console" launch screen (user's pick over 3b/
// 3c, see ROADMAP.md P8 Step 5). Real data for readiness/HRV/RHR/sleep/health
// -flag. The Fuel/Water/Supp dial row (DAV-68) was a permanent placeholder --
// never had real targets/tracking behind it and never will here; that content
// lives in the Fuel tile page instead (First feedback fixes project).
//
// DAV-95: the four system tiles now attach directly under the schematic
// (SystemTileRow below) instead of sitting in their own section further down
// the screen -- one body-centered composition per
// design/FIELD_TERMINAL_IA_CONTRACT.md section 4. The schematic drawing
// itself keeps its current amber Field Terminal styling on purpose (the
// issue's own "preserve the current Status shell" direction, and it's
// explicitly slated for a richer anatomical redraw later) -- only the new
// tile row adopts the Futuristic Material tokens.
@Composable
fun BodyConsole(overview: StatusOverview?, isLoading: Boolean, onOpenMap: (String) -> Unit, onOpenTile: (TileRoute) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.fillMaxWidth().height(380.dp)) {
            ConditionFigureField(overview, isLoading)
        }
        SystemTileRow(overview, onOpenTile)
        NextUpSection(onOpenMap)
    }
}

private data class SystemTileSpec(val route: TileRoute, val label: String, val icon: ImageVector)

private val SYSTEM_TILES = listOf(
    SystemTileSpec(TileRoute.Training, "TRAINING", Icons.Filled.FitnessCenter),
    SystemTileSpec(TileRoute.Fuel, "FUEL", Icons.Filled.Restaurant),
    SystemTileSpec(TileRoute.Heart, "HEART", Icons.Filled.Favorite),
    SystemTileSpec(TileRoute.Labs, "LABS", Icons.Filled.Science),
)

// Compact state/metric preview per DAV-95's acceptance criteria -- real data
// only. HEART is the one tile StatusOverview already backs (HRV/RHR, same
// numbers the schematic itself surfaces) so it gets a real Roboto Mono
// readout; TRAINING/FUEL/LABS have no summary data at this overview level
// yet (that's each category's own screen, owned by DAV-98/100/102) so they
// show a plain "OPEN" affordance rather than a fabricated number -- an
// honest sparse state, not an oversight.
private fun previewLine(route: TileRoute, overview: StatusOverview?): String = when (route) {
    TileRoute.Heart -> overview?.let {
        "HRV" + (it.latestHrv?.let { v -> "%.0f".format(v) } ?: "—") + "·RHR" + (it.latestRhr?.let { v -> "%.0f".format(v) } ?: "—")
    } ?: "OPEN"
    else -> "OPEN"
}

@Composable
private fun SystemTileRow(overview: StatusOverview?, onOpenTile: (TileRoute) -> Unit) {
    Row(
        // IntrinsicSize.Min + fillMaxHeight on each tile: without it, a
        // longer preview line (e.g. Heart's real "HRV 64 · RHR 57" wrapping
        // to two lines) makes that one tile taller than its three siblings --
        // caught live on-device, not in the mockup where every tile said
        // "OPEN".
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SYSTEM_TILES.forEach { tile ->
            SystemTile(tile, previewLine(tile.route, overview), modifier = Modifier.weight(1f).fillMaxHeight()) { onOpenTile(tile.route) }
        }
    }
}

@Composable
private fun SystemTile(tile: SystemTileSpec, preview: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(FT.RadiusModule))
            .background(FT.GlassFill)
            .border(FT.BorderWidth, FT.GlassBorder, RoundedCornerShape(FT.RadiusModule))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(tile.icon, contentDescription = tile.label, tint = FT.Emerald, modifier = Modifier.height(22.dp))
        Text(tile.label, style = tileLabelStyle, color = FT.TextPrimary)
        Text(preview, style = tilePreviewStyle, color = FT.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private val tileLabelStyle = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
private val tilePreviewStyle = TextStyle(fontFamily = RobotoMono, fontWeight = FontWeight.Bold, fontSize = 10.sp)

@Composable
private fun ConditionFigureField(overview: StatusOverview?, isLoading: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FieldColors.Panel)
            .drawBehind {
                val step = 26.dp.toPx()
                var x = 0f
                while (x < size.width) {
                    drawLine(FieldColors.Amber.copy(alpha = 0.07f), Offset(x, 0f), Offset(x, size.height), 1f)
                    x += step
                }
                var y = 0f
                while (y < size.height) {
                    drawLine(FieldColors.Amber.copy(alpha = 0.07f), Offset(0f, y), Offset(size.width, y), 1f)
                    y += step
                }
            },
    ) {
        Text(
            text = "CONDITION",
            style = FieldTextStyles.subTabLabel,
            color = FieldColors.InkMuted,
            modifier = Modifier.align(Alignment.TopStart).padding(14.dp),
        )

        when {
            isLoading -> CircularProgressIndicator(
                color = FieldColors.Amber,
                modifier = Modifier.align(Alignment.Center),
            )
            overview != null -> {
                Text(
                    text = overview.readiness.label.display,
                    style = TextStyle(fontFamily = SairaCondensed, fontWeight = FontWeight.Bold, fontSize = 26.sp),
                    color = if (overview.readiness.label == ReadinessLabel.Unknown) FieldColors.InkMuted else FieldColors.Amber,
                    modifier = Modifier.align(Alignment.TopEnd).padding(14.dp),
                )
                BodySchematic(overview, modifier = Modifier.align(Alignment.Center).size(330.dp, 254.dp))
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(14.dp),
                ) {
                    Text(
                        text = "SLEEP " + (overview.sleepHours?.let { formatHours(it) } ?: "—"),
                        style = FieldTextStyles.syncLabel,
                        color = FieldColors.InkMuted,
                    )
                    Text(
                        text = if (overview.activeHealthEvent) "1 FLAG" else "0 FLAGS",
                        style = FieldTextStyles.syncLabel,
                        color = if (overview.activeHealthEvent) FieldColors.Alert else FieldColors.Green,
                        modifier = Modifier.padding(start = 16.dp),
                    )
                }
            }
        }
    }
}

// Inline-SVG figure from design/Field Terminal Mockups.dc.html's `3d` block,
// recreated with Canvas + overlaid Text (not a literal image import, per
// design/README.md's own instruction) -- viewBox 260x200 scaled uniformly to
// this composable's 330x254dp size, same ratio the mockup itself renders at.
@Composable
private fun BodySchematic(overview: StatusOverview, modifier: Modifier = Modifier) {
    val scale = 330f / 260f // uniform on both axes -- 254/200 is the same ratio

    Box(modifier = modifier) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val s = size.width / 260f // actual px-per-viewbox-unit at draw time

            fun p(x: Float, y: Float) = Offset(x * s, y * s)

            // Base figure -- amber, matches every other "nominal instrument" line
            drawCircle(FieldColors.Amber, radius = 13f * s, center = p(130f, 22f), style = Stroke(2.4f * s))
            drawLine(FieldColors.Amber, p(130f, 35f), p(130f, 51f), 2.4f * s, StrokeCap.Round)
            val torso = androidx.compose.ui.graphics.Path().apply {
                moveTo(p(105f, 58f).x, p(105f, 58f).y)
                lineTo(p(130f, 51f).x, p(130f, 51f).y)
                lineTo(p(155f, 58f).x, p(155f, 58f).y)
                lineTo(p(153f, 104f).x, p(153f, 104f).y)
                lineTo(p(107f, 104f).x, p(107f, 104f).y)
                close()
            }
            drawPath(torso, FieldColors.Amber, style = Stroke(2.4f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawLine(FieldColors.Amber, p(105f, 58f), p(87f, 92f), 2.4f * s, StrokeCap.Round)
            drawLine(FieldColors.Amber, p(87f, 92f), p(83f, 124f), 2.4f * s, StrokeCap.Round)
            drawLine(FieldColors.Amber, p(155f, 58f), p(173f, 92f), 2.4f * s, StrokeCap.Round)
            drawLine(FieldColors.Amber, p(173f, 92f), p(177f, 124f), 2.4f * s, StrokeCap.Round)
            drawLine(FieldColors.Amber, p(113f, 104f), p(110f, 146f), 2.4f * s, StrokeCap.Round)
            drawLine(FieldColors.Amber, p(110f, 146f), p(107f, 186f), 2.4f * s, StrokeCap.Round)
            drawLine(FieldColors.Amber, p(147f, 104f), p(150f, 146f), 2.4f * s, StrokeCap.Round)
            drawLine(FieldColors.Amber, p(150f, 146f), p(153f, 186f), 2.4f * s, StrokeCap.Round)

            // Rib lines
            val rib = FieldColors.Green.copy(alpha = 0.55f)
            drawLine(rib, p(117f, 70f), p(143f, 70f), 1.2f * s)
            drawLine(rib, p(117f, 80f), p(143f, 80f), 1.2f * s)
            drawLine(rib, p(117f, 90f), p(135f, 90f), 1.2f * s)

            // Engine pin (HRV/RHR) -- always shown when any wearable data exists
            drawCircle(FieldColors.Green.copy(alpha = 0.14f), radius = 9f * s, center = p(130f, 78f))
            drawCircle(FieldColors.Green, radius = 9f * s, center = p(130f, 78f), style = Stroke(1.8f * s))
            drawLine(FieldColors.Green.copy(alpha = 0.7f), p(120f, 78f), p(58f, 78f), 1.4f * s)

            // Health-event pin -- only drawn when something is actually active
            if (overview.activeHealthEvent) {
                drawCircle(FieldColors.Alert.copy(alpha = 0.18f), radius = 13f * s, center = p(150f, 146f))
                drawCircle(FieldColors.Alert, radius = 13f * s, center = p(150f, 146f), style = Stroke(2f * s))
                drawCircle(FieldColors.Alert, radius = 4.4f * s, center = p(150f, 146f))
                drawLine(FieldColors.Alert, p(164f, 146f), p(204f, 146f), 1.4f * s)
            }
        }

        // Text overlays -- real Compose Text (own font/theme) rather than
        // Canvas-drawn text, positioned at the same viewBox coordinates.
        LabelAt(x = 54f, y = 62f, scale = scale, align = Alignment.TopEnd) {
            Text("ENGINE", style = smallLabel, color = FieldColors.Green)
        }
        LabelAt(x = 54f, y = 75f, scale = scale, align = Alignment.TopEnd) {
            Text("HRV " + (overview.latestHrv?.let { "%.0f".format(it) } ?: "—"), style = smallValue, color = FieldColors.Ink)
        }
        LabelAt(x = 54f, y = 88f, scale = scale, align = Alignment.TopEnd) {
            Text("RHR " + (overview.latestRhr?.let { "%.0f".format(it) } ?: "—"), style = smallValue, color = FieldColors.Ink)
        }

        if (overview.activeHealthEvent) {
            LabelAt(x = 208f, y = 135f, scale = scale, align = Alignment.TopStart) {
                Text("FLAGGED", style = smallLabel, color = FieldColors.Alert)
            }
            LabelAt(x = 208f, y = 161f, scale = scale, align = Alignment.TopStart) {
                Text("SEE HEALTH", style = smallValue, color = FieldColors.Ink)
            }
        }
    }
}

private val smallLabel = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Bold, fontSize = 10.5.sp)
private val smallValue = TextStyle(fontFamily = JetBrainsMono, fontWeight = FontWeight.Medium, fontSize = 11.sp)

// x/y are viewBox coordinates (0-260, 0-200), same space the Canvas above
// draws in. TopEnd anchors the text so it ends at x (grows leftward),
// matching the mockup SVG's text-anchor="end" labels (ENGINE/HRV/RHR);
// TopStart grows rightward from x, matching its unanchored labels.
@Composable
private fun LabelAt(x: Float, y: Float, scale: Float, align: Alignment, content: @Composable () -> Unit) {
    val padding = if (align == Alignment.TopEnd) {
        Modifier.padding(end = ((260f - x) * scale).dp, top = (y * scale).dp)
    } else {
        Modifier.padding(start = (x * scale).dp, top = (y * scale).dp)
    }
    Box(
        modifier = Modifier.fillMaxSize().then(padding),
        contentAlignment = align,
    ) {
        content()
    }
}

// DAV-69: real Calendar data, same silent-authorize + fetchUpcomingEvents()
// path MapScreen.kt already uses. Deliberately never launches the OAuth
// consent screen from this passive Status band -- if scopes aren't already
// granted, authorize() reports a resolution and this just shows a hint to
// visit Map (which does prompt), rather than a Status-tab surprise dialog.
private sealed interface NextUpState {
    data object Loading : NextUpState
    data class Found(val event: MapEvent) : NextUpState
    data object None : NextUpState
    data object NeedsMapConsent : NextUpState
    data class Error(val message: String) : NextUpState
}

@Composable
private fun NextUpSection(onOpenMap: (String) -> Unit) {
    val activity = LocalContext.current as Activity
    var state by remember { mutableStateOf<NextUpState>(NextUpState.Loading) }

    LaunchedEffect(Unit) {
        state = try {
            val authResult = GoogleAuthorizationManager.authorize(activity)
            val token = authResult.accessToken
            when {
                authResult.hasResolution() -> NextUpState.NeedsMapConsent
                token == null -> NextUpState.Error("Could not get a Google access token.")
                else -> MapRepository(token).fetchUpcomingEvents().firstOrNull()
                    ?.let { NextUpState.Found(it) } ?: NextUpState.None
            }
        } catch (e: Exception) {
            NextUpState.Error(e.message ?: "Couldn't load calendar.")
        }
    }

    val found = state as? NextUpState.Found
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(FieldColors.RaisedSurface)
            .then(
                if (found != null) {
                    Modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        found.event.id?.let(onOpenMap)
                    }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Text(nextUpLabel(state), style = FieldTextStyles.subTabLabel, color = FieldColors.InkMuted)
    }
}

private fun nextUpLabel(state: NextUpState): String = when (state) {
    NextUpState.Loading -> "NEXT UP — loading…"
    is NextUpState.Found -> "NEXT UP — ${state.event.title.trim().ifBlank { "Untitled event" }} · ${relativeTimeLabel(state.event)}"
    NextUpState.None -> "NEXT UP — nothing in the next 24h"
    NextUpState.NeedsMapConsent -> "NEXT UP — connect Calendar in the Map tab"
    is NextUpState.Error -> "NEXT UP — unavailable"
}

private fun relativeTimeLabel(event: MapEvent): String {
    val start = parseSessionZonedDateTime(event.startIso)
    val minutesUntil = ChronoUnit.MINUTES.between(ZonedDateTime.now(start.zone), start)
    return when {
        minutesUntil <= 0 -> "NOW"
        minutesUntil < 60 -> "IN $minutesUntil MIN"
        else -> "IN ${minutesUntil / 60}H ${minutesUntil % 60}M"
    }
}

private fun formatHours(hours: Double): String {
    val h = hours.toInt()
    val m = ((hours - h) * 60).toInt()
    return "%d:%02d".format(h, m)
}
