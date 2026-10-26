package com.bioscan.fieldterminal.ui.screens.status

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Science
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.auth.GoogleAuthorizationManager
import com.bioscan.fieldterminal.data.MapRepository
import com.bioscan.fieldterminal.data.NutritionGoals
import com.bioscan.fieldterminal.data.NutritionGoalsStore
import com.bioscan.fieldterminal.data.STALE_AFTER_DAYS
import com.bioscan.fieldterminal.data.StatusOverview
import com.bioscan.fieldterminal.data.TodayPreviews
import com.bioscan.fieldterminal.data.hydrationBoundsMl
import com.bioscan.fieldterminal.domain.AddEntryStart
import com.bioscan.fieldterminal.domain.FigurePoint
import com.bioscan.fieldterminal.domain.FuelSubType
import com.bioscan.fieldterminal.domain.MapEvent
import com.bioscan.fieldterminal.domain.MetricState
import com.bioscan.fieldterminal.domain.StateWord
import com.bioscan.fieldterminal.domain.healthHubState
import com.bioscan.fieldterminal.domain.labHubState
import com.bioscan.fieldterminal.domain.nutritionHubState
import com.bioscan.fieldterminal.domain.expectedStateWord
import com.bioscan.fieldterminal.domain.figurePinForBodyPart
import com.bioscan.fieldterminal.domain.freshnessLine
import com.bioscan.fieldterminal.domain.healthFlagDay
import com.bioscan.fieldterminal.domain.parseSessionZonedDateTime
import com.bioscan.fieldterminal.ui.components.FTStatePill
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.components.metricStateColor
import com.bioscan.fieldterminal.ui.components.metricStateLabel
import com.bioscan.fieldterminal.ui.nav.TileRoute
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

// The Status hub: a body-centered composition (design/FIELD_TERMINAL_IA_CONTRACT.md section 4). Entirely Futuristic
// Material tokens/fonts.
//
// Opening-page clean-up (owner, 2026-10): the hero is only this morning's HRV and its state pill (against the band
// expected for it; STALE when the reading is old), and a tap opens Today, where the words, the range and the freshness
// live. The figure's head/chest/legs tint by recovery/cardio/training-load state, with nothing under it. Then Next up,
// the four tiles (icon, title and one coloured dot, the dot ruled by domain/HubTileStates.kt) and the quick-log row.
// The hub scrolls, and the figure is sized from the viewport.
@Composable
fun BodyConsole(
    overview: StatusOverview?,
    previews: TodayPreviews?,
    isLoading: Boolean,
    onOpenTile: (TileRoute) -> Unit,
    onOpenToday: () -> Unit,
    onOpenInjury: () -> Unit = {},
    onQuickLog: (AddEntryStart) -> Unit = {},
    onOpenEvent: (Long) -> Unit = {},
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val figureHeight = (maxHeight * 0.30f).coerceIn(160.dp, 280.dp)
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            ConditionField(overview, isLoading, figureHeight, onOpenToday, onOpenInjury)
            NextUpSection(onOpenEvent = onOpenEvent)
            SystemTileGrid(overview, previews, onOpenTile)
            QuickLogRow(previews, onQuickLog)
        }
    }
}

private class HubInputs(val overview: StatusOverview?, val previews: TodayPreviews?, val goals: NutritionGoals, val now: LocalTime)

private data class SystemTileSpec(
    val route: TileRoute,
    val label: String,
    val icon: ImageVector,
    val accent: Color,
    val state: (HubInputs) -> MetricState,
)

// Each tile's icon uses its own domain accent (contract section 4): domain accents identify sections/navigation. The dot
// is the separate STATE signal (same colour table as the pills) and the tile's only indicator; the rules are in
// domain/HubTileStates.kt. A screen reader hears it as words.
private val SYSTEM_TILES = listOf(
    SystemTileSpec(TileRoute.Training, "TRAINING", Icons.Filled.FitnessCenter, FT.DomainTraining) { it.overview?.trainingState ?: MetricState.Unavailable },
    SystemTileSpec(TileRoute.Nutrition, "NUTRITION", Icons.Filled.Restaurant, FT.DomainFuel) { i ->
        val fuel = i.previews?.fuel
        nutritionHubState(
            loaded = fuel != null,
            kcal = fuel?.kcal,
            kcalGoal = i.goals.caloriesKcal,
            waterMl = fuel?.waterMl,
            waterTargetMl = hydrationBoundsMl(i.goals, fuel?.exerciseDemandMl ?: 0.0).start,
            now = i.now,
        )
    },
    SystemTileSpec(TileRoute.Health, "HEALTH", Icons.Filled.Favorite, FT.DomainHeart) { i ->
        i.overview?.let { o -> healthHubState(o.cardioState, o.recoveryState, o.hrvReadout?.let { expectedStateWord(it).state }) } ?: MetricState.Unavailable
    },
    SystemTileSpec(TileRoute.Lab, "LAB", Icons.Filled.Science, FT.DomainLabs) { labHubState(it.previews?.supplements, it.now) },
)

@Composable
private fun SystemTileGrid(overview: StatusOverview?, previews: TodayPreviews?, onOpenTile: (TileRoute) -> Unit) {
    val context = LocalContext.current
    // Re-read with the previews: they reload after a quick-log save, and goals are edited in Setup. The time of day is
    // taken then too (the Nutrition and Lab dots depend on it).
    val goals = remember(previews) { NutritionGoalsStore.getGoals(context) }
    val now = remember(previews) { LocalTime.now() }
    val inputs = HubInputs(overview, previews, goals, now)
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SYSTEM_TILES.chunked(2).forEach { pair ->
            // IntrinsicSize.Min + fillMaxHeight keeps both tiles of a row the same height.
            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { tile ->
                    SystemTile(
                        tile = tile,
                        state = tile.state(inputs),
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    ) { onOpenTile(tile.route) }
                }
            }
        }
    }
}

@Composable
private fun SystemTile(tile: SystemTileSpec, state: MetricState, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(FT.RadiusModule)
    Column(
        modifier = modifier
            .clip(shape)
            .background(FT.GlassFill)
            .border(FT.BorderWidth, FT.GlassBorder, shape)
            .ftClickable(shape = shape, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // The label next to it names the tile; a description here would be read twice.
            Icon(tile.icon, contentDescription = null, tint = tile.accent, modifier = Modifier.size(20.dp))
            Text(tile.label, style = FTType.LabelCaps, color = FT.TextPrimary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(metricStateColor(state))
                    .semantics { contentDescription = "${tile.label.lowercase()}: ${metricStateLabel(state).lowercase()}" },
            )
        }
    }
}

// UX review P1 / F3: the most frequent entries one tap from the hub, each opening the add sheet already on its form.
// + SUPPS ticks the bundle that is due now (domain/SupplementsToday.kt). Labels are actions, so Inter (contract A7).
// Four across; two by two when large text or a narrow screen would not fit four labels.
@Composable
private fun QuickLogRow(previews: TodayPreviews?, onQuickLog: (AddEntryStart) -> Unit) {
    val actions = listOf(
        "+ WATER" to AddEntryStart.Fuel(FuelSubType.Drink),
        "+ FOOD" to AddEntryStart.Fuel(FuelSubType.Food),
        "+ SUPPS" to AddEntryStart.Fuel(FuelSubType.Supplements, supplementBundle = previews?.supplements?.suggestedBucket),
        "SAY IT" to AddEntryStart.SayIt,
    )
    val shape = RoundedCornerShape(FT.RadiusSmall)
    BoxWithConstraints(modifier = Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, bottom = 18.dp)) {
        // "+ WATER" needs ~60dp of text at 1x; four chips leave (width - gaps) / 4 - padding each.
        val perRow = if (maxWidth / LocalDensity.current.fontScale < 300.dp) 2 else 4
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            actions.chunked(perRow).forEach { row ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (label, start) ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .defaultMinSize(minHeight = 48.dp)
                                .border(FT.BorderWidth, FT.DomainLog.copy(alpha = 0.6f), shape)
                                .ftClickable(shape = shape, role = Role.Button) { onQuickLog(start) }
                                .padding(horizontal = 6.dp, vertical = 10.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(label, style = FTType.ButtonLabel, color = FT.DomainLog, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConditionField(overview: StatusOverview?, isLoading: Boolean, figureHeight: Dp, onOpenToday: () -> Unit, onOpenInjury: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
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
        if (overview == null) {
            // Same footprint as the loaded hero + figure, so the tiles below do not jump when it arrives.
            Box(Modifier.fillMaxWidth().height(figureHeight + 80.dp), contentAlignment = Alignment.Center) {
                if (isLoading) CircularProgressIndicator(color = FT.Emerald)
            }
            return@Column
        }
        ConditionHero(overview, onOpenToday)
        BodySchematic(overview, onOpenInjury, modifier = Modifier.fillMaxWidth().height(figureHeight).padding(horizontal = 8.dp).padding(bottom = 8.dp))
    }
}

// The hero: this morning's HRV and one pill (analysis review phase C: against the band expected for it; phase G: while
// the band builds, BUILDING and no verdict). A reading older than the freshness rule allows (contract A5) shows STALE
// instead, since it is not this morning's. Everything else (the words, the range, the source and sync) is on Today.
@Composable
private fun ConditionHero(overview: StatusOverview, onOpenToday: () -> Unit) {
    val readout = overview.hrvReadout
    val stale = freshnessLine(
        readingDate = overview.hrvReadingDate,
        source = "WEARABLE",
        lastSync = overview.lastSyncAt?.atZone(ZoneId.systemDefault())?.toLocalDateTime(),
        today = LocalDate.now(),
        staleAfterDays = STALE_AFTER_DAYS,
    ).stale
    val word = when {
        stale -> StateWord(MetricState.Warning, "STALE")
        readout != null -> expectedStateWord(readout)
        else -> StateWord(MetricState.Building)
    }
    val value = readout?.value ?: overview.latestHrv
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .ftClickable(role = Role.Button, onClickLabel = "Open Today", onClick = onOpenToday)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = value?.let { "%.0f ms".format(it) } ?: "—",
            style = FTType.DisplayMetric,
            color = if (word.state == MetricState.Warning && !stale) metricStateColor(word.state) else FT.TextPrimary,
            modifier = Modifier.weight(1f),
        )
        FTStatePill(word)
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
private fun BodySchematic(overview: StatusOverview, onOpenInjury: () -> Unit, modifier: Modifier = Modifier) {
    val headColor = metricStateColor(overview.recoveryState)
    val chestColor = metricStateColor(overview.cardioState)
    val legColor = metricStateColor(overview.trainingState)
    val armColor = FT.TextMuted
    // UX review F1: the pin sits on the injured region and names it (domain/HealthFlag.kt); an illness has no region, so
    // it is shown by the flag count under the figure only.
    val flag = overview.healthFlag?.takeIf { it.isInjury }
    val pin = flag?.let { figurePinForBodyPart(it.label) }
    val flagDay = flag?.let { healthFlagDay(it, java.time.LocalDate.now()) }

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

            // Injury pin -- only drawn when an injury is actually open, on its region, with a leader to its label.
            if (pin != null) {
                val at = pin.point
                drawCircle(FT.Critical.copy(alpha = 0.18f), radius = 13f * s, center = p(at.x, at.y))
                drawCircle(FT.Critical, radius = 13f * s, center = p(at.x, at.y), style = Stroke(2f * s))
                drawCircle(FT.Critical, radius = 4.4f * s, center = p(at.x, at.y))
                if (flagLabelsLeft(at)) {
                    drawLine(FT.Critical, p(at.x - 14f, at.y), p(58f, at.y), 1.4f * s)
                } else {
                    drawLine(FT.Critical, p(at.x + 14f, at.y), p(204f, at.y), 1.4f * s)
                }
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

        if (pin != null) {
            val at = pin.point
            val left = flagLabelsLeft(at)
            val description = "Open injury: " + flag.label + (flagDay?.let { ", day $it" } ?: "") + ". Opens Training, Injury."
            // One tap target for both lines (padded toward the 48dp minimum), opening Training › Injury.
            LabelAt(x = if (left) 58f else 202f, y = at.y - 17f, scale = scale, align = if (left) Alignment.TopEnd else Alignment.TopStart) {
                Column(
                    modifier = Modifier
                        .semantics(mergeDescendants = true) { contentDescription = description }
                        .ftClickable(onClick = onOpenInjury)
                        .padding(horizontal = 6.dp, vertical = 6.dp),
                    horizontalAlignment = if (left) Alignment.End else Alignment.Start,
                ) {
                    Text(pin.label, style = smallLabel, color = FT.Critical, maxLines = 1)
                    Text(
                        (flagDay?.let { "DAY $it " } ?: "") + "›",
                        style = smallValue,
                        color = FT.TextPrimary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
    }
}

// Labels go on the pin's side, except for left-side pins level with the cardio labels (HRV/RHR sit on the left down to
// y ~100; the flag label starts 17 units above its pin, so only pins below y 112 have room there).
private fun flagLabelsLeft(at: FigurePoint): Boolean = at.x < 130f && at.y > 112f

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
// path MapScreen.kt used. Loading stays silent: if Calendar access isn't
// granted yet, the row says so and a TAP asks for it (UX review F1: with the
// Map tab gone there was nowhere left to grant it). An explicit tap is the
// one place a consent dialog is expected, so the passive load never opens it.
private sealed interface NextUpState {
    data object Loading : NextUpState
    data class Found(val event: MapEvent) : NextUpState
    // An event outside the training plan (domain/events): first within the next 24 hours, or the calendar has nothing.
    data class EventNext(val event: com.bioscan.fieldterminal.domain.events.AthleteEvent) : NextUpState
    data object None : NextUpState
    data class NeedsConsent(val pendingIntent: android.app.PendingIntent) : NextUpState
    data class Error(val message: String) : NextUpState
}

private suspend fun loadNextUp(token: String, event: com.bioscan.fieldterminal.domain.events.AthleteEvent?): NextUpState {
    val next = MapRepository(token).fetchUpcomingEvents().firstOrNull()
    val calendarStart = next?.let { runCatching { parseSessionZonedDateTime(it.startIso) }.getOrNull() }
    val now = ZonedDateTime.now()
    return when {
        com.bioscan.fieldterminal.domain.events.eventTakesNextUp(event, calendarStart, now) -> NextUpState.EventNext(event!!)
        next != null -> NextUpState.Found(next)
        else -> NextUpState.None
    }
}

private suspend fun loadNextEvent(): com.bioscan.fieldterminal.domain.events.AthleteEvent? = runCatching {
    val events = com.bioscan.fieldterminal.data.AthleteEventsRepository(com.bioscan.fieldterminal.data.SupabaseClientProvider.client)
        .loadUpcoming(java.time.LocalDate.now(), days = 15)
    com.bioscan.fieldterminal.domain.events.nextUpcomingEvent(events, ZonedDateTime.now())
}.getOrNull()

@Composable
private fun NextUpSection(modifier: Modifier = Modifier, onOpenEvent: (Long) -> Unit = {}) {
    val activity = LocalContext.current as Activity
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<NextUpState>(NextUpState.Loading) }
    var attempt by remember { mutableIntStateOf(0) }
    var nextEvent by remember { mutableStateOf<com.bioscan.fieldterminal.domain.events.AthleteEvent?>(null) }

    LaunchedEffect(attempt) {
        state = NextUpState.Loading
        nextEvent = loadNextEvent()
        state = try {
            val authResult = GoogleAuthorizationManager.authorize(activity)
            val token = authResult.accessToken
            val pendingIntent = authResult.pendingIntent
            when {
                authResult.hasResolution() && pendingIntent != null -> NextUpState.NeedsConsent(pendingIntent)
                token == null -> NextUpState.Error("Could not get a Google access token.")
                else -> {
                    // Keeps the calendar cache and its GPX course previews current (both had no caller before).
                    scope.launch { runCatching { com.bioscan.fieldterminal.data.CalendarBackgroundSync.runIfDue(activity, token) } }
                    loadNextUp(token, nextEvent)
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            NextUpState.Error(e.message ?: "Couldn't load calendar.")
        }
    }

    val consentLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        scope.launch {
            state = try {
                val token = GoogleAuthorizationManager.resultFromIntent(activity, result.data).accessToken
                if (token == null) NextUpState.Error("Calendar access was not granted.") else loadNextUp(token, nextEvent)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                NextUpState.Error(e.message ?: "Couldn't connect Calendar.")
            }
        }
    }

    // Found: opens the calendar app at the event's time. NeedsConsent: asks for Calendar access. Error: retries.
    val onTap: (() -> Unit)? = when (val s = state) {
        is NextUpState.Found -> { { openCalendarAt(activity, s.event) } }
        is NextUpState.EventNext -> { { s.event.id?.let(onOpenEvent) } }
        is NextUpState.NeedsConsent -> { { consentLauncher.launch(IntentSenderRequest.Builder(s.pendingIntent.intentSender).build()) } }
        is NextUpState.Error -> { { attempt++ } }
        else -> null
    }

    // 25/9 rework: back to a single-line band; the figure above now absorbs
    // the leftover height and the tile row sits below this band.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(FT.Surface)
            .then(
                if (onTap != null) {
                    Modifier.ftClickable(onClick = onTap)
                } else {
                    Modifier
                },
            )
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            nextUpLabel(state),
            style = FTType.LabelCaps,
            color = if (state is NextUpState.NeedsConsent) FT.EmeraldHighlight else FT.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (onTap != null) Text("›", style = FTType.LabelCaps, color = FT.TextSecondary, modifier = Modifier.padding(start = 8.dp))
    }
}

private fun nextUpLabel(state: NextUpState): String = when (state) {
    NextUpState.Loading -> "NEXT UP — loading…"
    is NextUpState.Found -> "NEXT UP — ${state.event.title.trim().ifBlank { "Untitled event" }} · ${relativeTimeLabel(state.event)}"
    is NextUpState.EventNext -> "NEXT UP — ${state.event.name} · ${eventRelativeLabel(state.event)}"
    NextUpState.None -> "NEXT UP — nothing in the next 24h"
    is NextUpState.NeedsConsent -> "NEXT UP — tap to connect Google Calendar"
    is NextUpState.Error -> "NEXT UP — couldn't load · tap to retry"
}

// Opens the device's calendar app at the event's start time (the standard calendar time intent, no event id needed).
private fun openCalendarAt(activity: Activity, event: MapEvent) {
    val startMillis = runCatching { parseSessionZonedDateTime(event.startIso).toInstant().toEpochMilli() }.getOrNull() ?: return
    val uri = android.provider.CalendarContract.CONTENT_URI.buildUpon().appendPath("time").appendPath(startMillis.toString()).build()
    runCatching { activity.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri)) }
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

private fun eventRelativeLabel(event: com.bioscan.fieldterminal.domain.events.AthleteEvent): String {
    val now = ZonedDateTime.now()
    val start = event.start?.withZoneSameInstant(now.zone)
    val days = ChronoUnit.DAYS.between(now.toLocalDate(), event.date)
    return when {
        start != null && ChronoUnit.MINUTES.between(now, start) in 0..(24 * 60) -> {
            val m = ChronoUnit.MINUTES.between(now, start)
            if (m < 60) "IN $m MIN" else "IN ${m / 60}H ${m % 60}M"
        }
        days == 0L -> "TODAY"
        days == 1L -> "TOMORROW"
        else -> "IN $days DAYS"
    }
}
