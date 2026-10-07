package com.bioscan.fieldterminal.ui.screens.status

import android.app.Activity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import com.bioscan.fieldterminal.domain.MetricState
import com.bioscan.fieldterminal.domain.parseSessionZonedDateTime
import com.bioscan.fieldterminal.ui.nav.TileRoute
import com.bioscan.fieldterminal.ui.components.FTStatePill
import com.bioscan.fieldterminal.ui.components.metricStateColor
import com.bioscan.fieldterminal.ui.components.metricStateLabel
import com.bioscan.fieldterminal.ui.components.readinessState
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

// The Status hub: a body-centered composition (design/FIELD_TERMINAL_IA_CONTRACT.md
// section 4). Real data for readiness/HRV/RHR/sleep/health-flag. The hero is
// the HRV-vs-baseline readiness band (categorical on purpose: no composite
// 0-100 score exists or is invented), paired with a state pill; the figure's
// head/chest/legs tint by recovery/cardio/training-load state, and the zone
// legend under it spells out each zone's state in words so color is never the
// only signal. Entirely Futuristic Material tokens/fonts.
@Composable
fun BodyConsole(overview: StatusOverview?, isLoading: Boolean, onOpenTile: (TileRoute) -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        // 25/9 rework: figure absorbs all leftover height; Next Up band and the
        // tile row sit at the bottom, resting on the Scaffold's bottom menu.
        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            ConditionFigureField(overview, isLoading)
        }
        NextUpSection()
        SystemTileRow(overview, onOpenTile)
    }
}

private data class SystemTileSpec(
    val route: TileRoute,
    val label: String,
    val icon: ImageVector,
    val accent: Color,
    // null = this overview has no state for the tile (Fuel, Labs).
    val state: (StatusOverview) -> MetricState?,
)

// Each tile's icon uses its own domain accent (contract section 4): domain
// accents identify sections/navigation. The small dot beside the icon is the
// separate STATE signal (same color table as the pills), shown only where the
// overview really has a state for that system.
private val SYSTEM_TILES = listOf(
    SystemTileSpec(TileRoute.Training, "TRAINING", Icons.Filled.FitnessCenter, FT.DomainTraining) { it.trainingState },
    SystemTileSpec(TileRoute.Fuel, "FUEL", Icons.Filled.Restaurant, FT.DomainFuel) { null },
    SystemTileSpec(TileRoute.Heart, "HEALTH", Icons.Filled.Favorite, FT.DomainHeart) { it.cardioState },
    SystemTileSpec(TileRoute.Labs, "LABS", Icons.Filled.Science, FT.DomainLabs) { null },
)

// Compact preview lines -- real data only. HEART is backed by HRV/RHR,
// TRAINING by the 7-day session count; FUEL/LABS have no summary data at this
// overview level yet, so they show a plain "OPEN" rather than a fabricated
// number. Two short lines (not one "HRV64·RHR57" string) so each reads alone.
private fun previewLines(route: TileRoute, overview: StatusOverview?): List<String> = when (route) {
    TileRoute.Heart -> overview?.let {
        listOf(
            "HRV " + (it.latestHrv?.let { v -> "%.0f".format(v) } ?: "—"),
            "RHR " + (it.latestRhr?.let { v -> "%.0f".format(v) } ?: "—"),
        )
    } ?: listOf("OPEN")
    TileRoute.Training -> overview?.let {
        listOf(if (it.sessionsLast7Days == 1) "1 SESSION" else "${it.sessionsLast7Days} SESSIONS", "LAST 7 DAYS")
    } ?: listOf("OPEN")
    else -> listOf("OPEN")
}

@Composable
private fun SystemTileRow(overview: StatusOverview?, onOpenTile: (TileRoute) -> Unit) {
    Row(
        // IntrinsicSize.Min + fillMaxHeight on each tile keeps all four the
        // same height even when one has more preview lines than its siblings.
        modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SYSTEM_TILES.forEach { tile ->
            SystemTile(
                tile = tile,
                state = overview?.let { tile.state(it) },
                preview = previewLines(tile.route, overview),
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) { onOpenTile(tile.route) }
        }
    }
}

@Composable
private fun SystemTile(tile: SystemTileSpec, state: MetricState?, preview: List<String>, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(FT.RadiusModule))
            .background(FT.GlassFill)
            .border(FT.BorderWidth, FT.GlassBorder, RoundedCornerShape(FT.RadiusModule))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(tile.icon, contentDescription = tile.label, tint = tile.accent, modifier = Modifier.size(22.dp))
            state?.let {
                Spacer(Modifier.width(6.dp))
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(metricStateColor(it))
                        .semantics { contentDescription = metricStateLabel(it).lowercase() },
                )
            }
        }
        Text(tile.label, style = tileLabelStyle, color = FT.TextPrimary)
        preview.forEach { line ->
            Text(line, style = tilePreviewStyle, color = FT.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private val tileLabelStyle = FTType.CaptionStrong
private val tilePreviewStyle = FTType.Label

@Composable
private fun ConditionFigureField(overview: StatusOverview?, isLoading: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(FT.Base)
            // Contract section 7: an emerald atmospheric wash is allowed behind
            // a biometric focal visualization (large-area, low opacity).
            .drawBehind {
                drawRect(
                    Brush.radialGradient(
                        colors = listOf(FT.EmeraldAtmosphere.copy(alpha = 0.45f), Color.Transparent),
                        center = Offset(size.width / 2f, size.height * 0.55f),
                        radius = size.width * 0.9f,
                    ),
                )
            },
    ) {
        when {
            isLoading -> CircularProgressIndicator(
                color = FT.Emerald,
                modifier = Modifier.align(Alignment.Center),
            )
            overview != null -> {
                val readiness = overview.readiness
                val conditionState = readinessState(readiness.label)
                Row(
                    modifier = Modifier.align(Alignment.TopStart).fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("CONDITION", style = FTType.Label, color = FT.TextMuted)
                        Text(
                            text = readiness.label.display,
                            style = FTType.DisplayMetric,
                            // Neutral/Building/Unavailable stay unaccented: only a
                            // real favorable/attention state earns a signal color.
                            color = when (conditionState) {
                                MetricState.Optimal, MetricState.Warning, MetricState.Critical -> metricStateColor(conditionState)
                                else -> FT.TextPrimary
                            },
                        )
                        Text(
                            text = if (conditionState == MetricState.Building) readiness.note.uppercase() else "HRV VS YOUR RECENT BASELINE",
                            style = FTType.Micro,
                            color = FT.TextMuted,
                        )
                    }
                    FTStatePill(conditionState)
                }
                // Inset clears the hero block above and the legend/footer below.
                BodySchematic(overview, modifier = Modifier.fillMaxSize().padding(top = 104.dp, bottom = 112.dp, start = 8.dp, end = 8.dp))
                Column(
                    modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    ZoneLegend(overview)
                    Row {
                        Text(
                            text = "SLEEP " + (overview.sleepHours?.let { formatHours(it) } ?: "—"),
                            style = FTType.Label,
                            color = FT.TextSecondary,
                        )
                        Text(
                            text = if (overview.activeHealthEvent) "1 FLAG" else "0 FLAGS",
                            style = FTType.Label,
                            color = if (overview.activeHealthEvent) FT.Critical else FT.TextSecondary,
                            modifier = Modifier.padding(start = 16.dp),
                        )
                    }
                }
            }
        }
    }
}

// What each figure zone's color means, in words: head = recovery (sleep),
// chest = cardio (HRV/RHR), legs = training load. Dot color + state word, so
// the tint on the figure is never the only signal.
@Composable
private fun ZoneLegend(overview: StatusOverview) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ZoneLegendCell("RECOVERY", overview.recoveryState, Modifier.weight(1f))
        ZoneLegendCell("CARDIO", overview.cardioState, Modifier.weight(1f))
        ZoneLegendCell("LOAD", overview.trainingState, Modifier.weight(1f))
    }
}

@Composable
private fun ZoneLegendCell(label: String, state: MetricState, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(FT.RadiusSmall)
    Column(
        modifier = modifier
            .clip(shape)
            .background(FT.GlassFill)
            .border(FT.BorderWidth, FT.GlassBorder, shape)
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Text(label, style = FTType.Micro, color = FT.TextMuted)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(metricStateColor(state)))
            Text(metricStateLabel(state), style = FTType.Label, color = FT.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// Inline-SVG figure from design/Field Terminal Mockups.dc.html's `3d` block,
// recreated with Canvas + overlaid Text (not a literal image import, per
// design/README.md's own instruction) -- viewBox 260x200 scaled uniformly to
// this composable's 330x254dp size, same ratio the mockup itself renders at.
// DAV-203 (24/9 fixes): the "richer anatomical redraw" this file's own
// header comment already flagged as future work -- head/chest/legs are now
// tinted by that region's real MetricState (recovery/cardio/training load)
// instead of every line being uniform amber. Arms and rib lines stay amber/
// green decoration since they aren't mapped to any of the three systems.
@Composable
private fun BodySchematic(overview: StatusOverview, modifier: Modifier = Modifier) {
    val headColor = metricStateColor(overview.recoveryState)
    val chestColor = metricStateColor(overview.cardioState)
    val legColor = metricStateColor(overview.trainingState)
    val armColor = FT.TextMuted

    // 25/9 rework: the figure fills whatever height the layout leaves it, so
    // scale (dp per viewBox unit) comes from the tighter of width/height.
    BoxWithConstraints(modifier = modifier, contentAlignment = Alignment.Center) {
    val scale = minOf(maxWidth.value / 260f, maxHeight.value / 200f, 1.5f)
    Box(modifier = Modifier.size((260f * scale).dp, (200f * scale).dp)) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val s = size.width / 260f // actual px-per-viewbox-unit at draw time

            fun p(x: Float, y: Float) = Offset(x * s, y * s)

            // Head/neck -- recovery (sleep duration)
            drawCircle(headColor, radius = 13f * s, center = p(130f, 22f), style = Stroke(2.4f * s))
            drawLine(headColor, p(130f, 35f), p(130f, 51f), 2.4f * s, StrokeCap.Round)
            // Torso -- cardio (HRV/RHR)
            val torso = androidx.compose.ui.graphics.Path().apply {
                moveTo(p(105f, 58f).x, p(105f, 58f).y)
                lineTo(p(130f, 51f).x, p(130f, 51f).y)
                lineTo(p(155f, 58f).x, p(155f, 58f).y)
                lineTo(p(153f, 104f).x, p(153f, 104f).y)
                lineTo(p(107f, 104f).x, p(107f, 104f).y)
                close()
            }
            drawPath(torso, chestColor, style = Stroke(2.4f * s, cap = StrokeCap.Round, join = StrokeJoin.Round))
            // Arms -- decorative (neutral), not mapped to a system
            drawLine(armColor, p(105f, 58f), p(87f, 92f), 2.4f * s, StrokeCap.Round)
            drawLine(armColor, p(87f, 92f), p(83f, 124f), 2.4f * s, StrokeCap.Round)
            drawLine(armColor, p(155f, 58f), p(173f, 92f), 2.4f * s, StrokeCap.Round)
            drawLine(armColor, p(173f, 92f), p(177f, 124f), 2.4f * s, StrokeCap.Round)
            // Legs -- training load
            drawLine(legColor, p(113f, 104f), p(110f, 146f), 2.4f * s, StrokeCap.Round)
            drawLine(legColor, p(110f, 146f), p(107f, 186f), 2.4f * s, StrokeCap.Round)
            drawLine(legColor, p(147f, 104f), p(150f, 146f), 2.4f * s, StrokeCap.Round)
            drawLine(legColor, p(150f, 146f), p(153f, 186f), 2.4f * s, StrokeCap.Round)

            // Rib lines (decorative, neutral)
            val rib = FT.TextMuted.copy(alpha = 0.6f)
            drawLine(rib, p(117f, 70f), p(143f, 70f), 1.2f * s)
            drawLine(rib, p(117f, 80f), p(143f, 80f), 1.2f * s)
            drawLine(rib, p(117f, 90f), p(135f, 90f), 1.2f * s)

            // Cardio pin (HRV/RHR) -- same state color as the chest zone
            drawCircle(chestColor.copy(alpha = 0.14f), radius = 9f * s, center = p(130f, 78f))
            drawCircle(chestColor, radius = 9f * s, center = p(130f, 78f), style = Stroke(1.8f * s))
            drawLine(chestColor.copy(alpha = 0.7f), p(120f, 78f), p(58f, 78f), 1.4f * s)

            // Health-event pin -- only drawn when something is actually active
            if (overview.activeHealthEvent) {
                drawCircle(FT.Critical.copy(alpha = 0.18f), radius = 13f * s, center = p(150f, 146f))
                drawCircle(FT.Critical, radius = 13f * s, center = p(150f, 146f), style = Stroke(2f * s))
                drawCircle(FT.Critical, radius = 4.4f * s, center = p(150f, 146f))
                drawLine(FT.Critical, p(164f, 146f), p(204f, 146f), 1.4f * s)
            }
        }

        // Text overlays -- real Compose Text (own font/theme) rather than
        // Canvas-drawn text, positioned at the same viewBox coordinates.
        LabelAt(x = 54f, y = 62f, scale = scale, align = Alignment.TopEnd) {
            Text("CARDIO", style = smallLabel, color = chestColor)
        }
        LabelAt(x = 54f, y = 75f, scale = scale, align = Alignment.TopEnd) {
            Text("HRV " + (overview.latestHrv?.let { "%.0f".format(it) } ?: "—"), style = smallValue, color = FT.TextPrimary)
        }
        LabelAt(x = 54f, y = 88f, scale = scale, align = Alignment.TopEnd) {
            Text("RHR " + (overview.latestRhr?.let { "%.0f".format(it) } ?: "—"), style = smallValue, color = FT.TextPrimary)
        }

        if (overview.activeHealthEvent) {
            LabelAt(x = 208f, y = 135f, scale = scale, align = Alignment.TopStart) {
                Text("FLAGGED", style = smallLabel, color = FT.Critical)
            }
            LabelAt(x = 208f, y = 161f, scale = scale, align = Alignment.TopStart) {
                Text("SEE HEALTH", style = smallValue, color = FT.TextPrimary)
            }
        }
    }
    }
}

private val smallLabel = FTType.Label
private val smallValue = FTType.Label

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
private fun NextUpSection(modifier: Modifier = Modifier) {
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

    // 25/9 rework: back to a single-line band; the figure above now absorbs
    // the leftover height and the tile row sits below this band.
    // DAV-296: this used to tap through to the Map tab, focused on the
    // found event -- dropped along with the tab (see
    // docs/user-profile-milestone/01-canonical-contracts-audit.md section 10).
    // Still shows the next calendar event as information; just not tappable.
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(FT.Surface)
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Text(nextUpLabel(state), style = FTType.LabelCaps, color = FT.TextSecondary)
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
