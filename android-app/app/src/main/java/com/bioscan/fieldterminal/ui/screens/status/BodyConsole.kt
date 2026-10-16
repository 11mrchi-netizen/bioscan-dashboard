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
import com.bioscan.fieldterminal.domain.MorningReadout
import com.bioscan.fieldterminal.domain.expectedStateWord
import com.bioscan.fieldterminal.domain.headline
import com.bioscan.fieldterminal.domain.figurePinForBodyPart
import com.bioscan.fieldterminal.domain.freshnessLine
import com.bioscan.fieldterminal.domain.healthFlagDay
import com.bioscan.fieldterminal.domain.parseSessionZonedDateTime
import com.bioscan.fieldterminal.domain.supplementsTodayLine
import com.bioscan.fieldterminal.ui.components.FTStatePill
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.components.metricStateColor
import com.bioscan.fieldterminal.ui.components.metricStateLabel
import com.bioscan.fieldterminal.ui.nav.TileRoute
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt

// The Status hub: a body-centered composition (design/FIELD_TERMINAL_IA_CONTRACT.md
// section 4). Real data for HRV/RHR/sleep/health-flag. The hero is this
// morning's HRV against the band expected for it (analysis review phase C; no
// composite 0-100 score exists or is invented), paired with a state pill; the figure's
// head/chest/legs tint by recovery/cardio/training-load state, and the zone
// legend under it spells out each zone's state in words so color is never the
// only signal. Entirely Futuristic Material tokens/fonts.
//
// UX review P1 ("today at a glance"): the hero names its basis and freshness and links TODAY ›; the figure is shorter;
// the four tiles are a 2x2 grid of instruments with real previews (FUEL and LABS no longer say OPEN); a quick-log row
// opens the add sheet on the right form. The hub scrolls (F4: large fonts used to squeeze it), and the figure is sized
// from the viewport so the first screen still holds the hero, the figure and all four tiles.
@Composable
fun BodyConsole(
    overview: StatusOverview?,
    previews: TodayPreviews?,
    isLoading: Boolean,
    onOpenTile: (TileRoute) -> Unit,
    onOpenToday: () -> Unit,
    onOpenInjury: () -> Unit = {},
    onQuickLog: (AddEntryStart) -> Unit = {},
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val figureHeight = (maxHeight * 0.30f).coerceIn(160.dp, 280.dp)
        Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            ConditionField(overview, isLoading, figureHeight, onOpenToday, onOpenInjury)
            NextUpSection()
            SystemTileGrid(overview, previews, onOpenTile)
            QuickLogRow(previews, onQuickLog)
        }
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

// Two preview lines per tile, real data only: a missing reading is "—", never a made-up number. Short lines (not one
// "HRV64·RHR57" string) so each reads alone. FUEL uses the same goal and hydration range as the Fuel tile, LABS the same
// supplements-due rule as the reminder (domain/SupplementsToday.kt).
private fun previewLines(route: TileRoute, overview: StatusOverview?, previews: TodayPreviews?, goals: NutritionGoals): List<String> = when (route) {
    TileRoute.Training -> listOf(
        overview?.let { "${it.sessionsLast7Days} SESSION${if (it.sessionsLast7Days == 1) "" else "S"} · 7D" } ?: "SESSIONS —",
        overview?.let { o -> o.trainingLoadBand?.uppercase() ?: "LOAD ${metricStateLabel(o.trainingState)}" } ?: "LOAD —",
    )
    TileRoute.Fuel -> {
        val fuel = previews?.fuel
        val goal = goals.caloriesKcal
        val kcal = fuel?.kcal?.let(::thousands) ?: "—"
        val water = fuel?.waterMl?.let { "%.1f".format(Locale.ENGLISH, it / 1000.0) } ?: "—"
        val bounds = hydrationBoundsMl(goals, fuel?.exerciseDemandMl ?: 0.0)
        listOf(
            if (goal != null) "$kcal / ${thousands(goal)} KCAL" else "$kcal KCAL",
            "WATER $water / " + "%.1f–%.1f L".format(Locale.ENGLISH, bounds.start / 1000.0, bounds.endInclusive / 1000.0),
        )
    }
    TileRoute.Heart -> listOf(
        "HRV " + (overview?.latestHrv?.let { "%.0f".format(it) } ?: "—") + " · RHR " + (overview?.latestRhr?.let { "%.0f".format(it) } ?: "—"),
        "SLEEP " + (overview?.sleepHours?.let { formatHours(it) } ?: "—"),
    )
    TileRoute.Labs -> listOf(
        previews?.supplements?.let { supplementsTodayLine(it) } ?: "SUPPS —",
        previews?.lastLabDraw?.let { "LAST DRAW " + it.format(DRAW_DATE).uppercase() }
            ?: if (previews?.labDrawsLoaded == true) "NO LAB DRAWS YET" else "LAST DRAW —",
    )
}

private fun thousands(v: Double): String = "%,d".format(Locale.ENGLISH, v.roundToInt())

private val DRAW_DATE = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH)

@Composable
private fun SystemTileGrid(overview: StatusOverview?, previews: TodayPreviews?, onOpenTile: (TileRoute) -> Unit) {
    val context = LocalContext.current
    // Re-read with the previews: they reload after a quick-log save, and goals are edited in Setup.
    val goals = remember(previews) { NutritionGoalsStore.getGoals(context) }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SYSTEM_TILES.chunked(2).forEach { pair ->
            // IntrinsicSize.Min + fillMaxHeight keeps both tiles of a row the same height when one preview wraps.
            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { tile ->
                    SystemTile(
                        tile = tile,
                        state = overview?.let { tile.state(it) },
                        preview = previewLines(tile.route, overview, previews, goals),
                        modifier = Modifier.weight(1f).fillMaxHeight(),
                    ) { onOpenTile(tile.route) }
                }
            }
        }
    }
}

@Composable
private fun SystemTile(tile: SystemTileSpec, state: MetricState?, preview: List<String>, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(FT.RadiusModule)
    Column(
        modifier = modifier
            .clip(shape)
            .background(FT.GlassFill)
            .border(FT.BorderWidth, FT.GlassBorder, shape)
            .ftClickable(shape = shape, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            // The label next to it names the tile; a description here would be read twice.
            Icon(tile.icon, contentDescription = null, tint = tile.accent, modifier = Modifier.size(18.dp))
            Text(tile.label, style = FTType.LabelCaps, color = FT.TextPrimary, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            state?.let {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(metricStateColor(it))
                        .semantics { contentDescription = metricStateLabel(it).lowercase() },
                )
            }
            Text("›", style = FTType.LabelCaps, color = FT.TextSecondary)
        }
        preview.forEachIndexed { i, line ->
            Text(line, style = FTType.Label, color = if (i == 0) FT.TextPrimary else FT.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
            // Same footprint as the loaded hero + figure + legend, so the tiles below do not jump when it arrives.
            Box(Modifier.fillMaxWidth().height(figureHeight + 190.dp), contentAlignment = Alignment.Center) {
                if (isLoading) CircularProgressIndicator(color = FT.Emerald)
            }
            return@Column
        }
        ConditionHero(overview, onOpenToday)
        BodySchematic(overview, onOpenInjury, modifier = Modifier.fillMaxWidth().height(figureHeight).padding(horizontal = 8.dp))
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ZoneLegend(overview)
            Row {
                Text(
                    text = "SLEEP " + (overview.sleepHours?.let { formatHours(it) } ?: "—"),
                    style = FTType.Label,
                    color = FT.TextSecondary,
                )
                // UX review F1: the count opens the same place the pin does (Training › Injury).
                val flags = overview.openHealthFlags.takeIf { it > 0 } ?: if (overview.activeHealthEvent) 1 else 0
                Text(
                    text = if (flags == 1) "1 FLAG ›" else if (flags > 1) "$flags FLAGS ›" else "0 FLAGS",
                    style = FTType.Label,
                    color = if (flags > 0) FT.Critical else FT.TextSecondary,
                    modifier = Modifier
                        .padding(start = 16.dp)
                        .then(
                            if (flags > 0) {
                                Modifier.ftClickable(onClickLabel = "Open Training, Injury", onClick = onOpenInjury)
                            } else {
                                Modifier
                            },
                        ),
                )
            }
        }
    }
}

// UX review P1 / P6: the hero says what it is, how fresh the reading is (contract A5), and where a tap goes (TODAY ›).
// Analysis review phase C: this morning's HRV against the band expected for it (value, words, size). Phase G retired the
// fallback that judged HRV against a 7-day z-score (LOW / REDUCED / NORMAL / PRIMED): while the band builds, the hero
// shows the reading and how many mornings the band still needs, without a verdict.
@Composable
private fun ConditionHero(overview: StatusOverview, onOpenToday: () -> Unit) {
    overview.hrvReadout?.let { readout ->
        BandHero(overview, readout, onOpenToday)
        return
    }
    val freshness = freshnessLine(
        readingDate = overview.hrvReadingDate,
        source = "WEARABLE",
        lastSync = overview.lastSyncAt?.atZone(ZoneId.systemDefault())?.toLocalDateTime(),
        today = LocalDate.now(),
        staleAfterDays = STALE_AFTER_DAYS,
    )
    val progress = overview.hrvBandProgress
    val building = when {
        progress == null -> "THE EXPECTED BAND APPEARS AFTER THE FIRST NIGHTLY ANALYSIS"
        progress.second > progress.first -> "THE EXPECTED BAND NEEDS ${progress.second - progress.first} MORE MORNINGS"
        else -> "NO HRV READING THIS MORNING YET"
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .ftClickable(role = Role.Button, onClickLabel = "Open Today", onClick = onOpenToday)
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "THIS MORNING · HRV",
                style = FTType.LabelCaps,
                color = FT.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text("TODAY ›", style = FTType.LabelCaps, color = FT.EmeraldHighlight, modifier = Modifier.padding(start = 8.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = overview.latestHrv?.let { "%.0f MS".format(it) } ?: "—",
                style = FTType.DisplayMetric,
                color = FT.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            FTStatePill(MetricState.Building)
        }
        Text(building, style = FTType.Micro, color = FT.TextMuted)
        Text(freshness.text, style = FTType.Micro, color = if (freshness.stale) FT.Warning else FT.TextMuted, maxLines = 2)
    }
}

@Composable
private fun BandHero(overview: StatusOverview, readout: MorningReadout, onOpenToday: () -> Unit) {
    val word = expectedStateWord(readout)
    val freshness = freshnessLine(
        readingDate = overview.hrvReadingDate,
        source = "WEARABLE",
        lastSync = overview.lastSyncAt?.atZone(ZoneId.systemDefault())?.toLocalDateTime(),
        today = LocalDate.now(),
        staleAfterDays = STALE_AFTER_DAYS,
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .ftClickable(role = Role.Button, onClickLabel = "Open Today", onClick = onOpenToday)
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "THIS MORNING · HRV VS EXPECTED",
                style = FTType.LabelCaps,
                color = FT.TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text("TODAY ›", style = FTType.LabelCaps, color = FT.EmeraldHighlight, modifier = Modifier.padding(start = 8.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "%.0f ms".format(readout.value),
                style = FTType.DisplayMetric,
                color = if (word.state == MetricState.Warning) metricStateColor(word.state) else FT.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            FTStatePill(word)
        }
        Text(readout.headline(), style = FTType.BodySmall, color = FT.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(freshness.text, style = FTType.Micro, color = if (freshness.stale) FT.Warning else FT.TextMuted, maxLines = 2)
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
    data object None : NextUpState
    data class NeedsConsent(val pendingIntent: android.app.PendingIntent) : NextUpState
    data class Error(val message: String) : NextUpState
}

private suspend fun loadNextUp(token: String): NextUpState =
    MapRepository(token).fetchUpcomingEvents().firstOrNull()?.let { NextUpState.Found(it) } ?: NextUpState.None

@Composable
private fun NextUpSection(modifier: Modifier = Modifier) {
    val activity = LocalContext.current as Activity
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<NextUpState>(NextUpState.Loading) }
    var attempt by remember { mutableIntStateOf(0) }

    LaunchedEffect(attempt) {
        state = NextUpState.Loading
        state = try {
            val authResult = GoogleAuthorizationManager.authorize(activity)
            val token = authResult.accessToken
            val pendingIntent = authResult.pendingIntent
            when {
                authResult.hasResolution() && pendingIntent != null -> NextUpState.NeedsConsent(pendingIntent)
                token == null -> NextUpState.Error("Could not get a Google access token.")
                else -> loadNextUp(token)
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
                if (token == null) NextUpState.Error("Calendar access was not granted.") else loadNextUp(token)
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

private fun formatHours(hours: Double): String {
    val h = hours.toInt()
    val m = ((hours - h) * 60).toInt()
    return "%d:%02d".format(h, m)
}
