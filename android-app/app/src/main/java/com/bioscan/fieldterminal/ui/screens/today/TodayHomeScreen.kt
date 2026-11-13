@file:UseSerializers(com.bioscan.fieldterminal.domain.LocalDateSerializer::class, com.bioscan.fieldterminal.domain.LocalTimeSerializer::class)

package com.bioscan.fieldterminal.ui.screens.today

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.NutritionOverview
import com.bioscan.fieldterminal.data.ScoresView
import com.bioscan.fieldterminal.data.TodayFuel
import com.bioscan.fieldterminal.data.TodayPreviews
import com.bioscan.fieldterminal.domain.AddEntryStart
import com.bioscan.fieldterminal.domain.EVENING_CHECKIN_TIME
import com.bioscan.fieldterminal.domain.FuelSubType
import com.bioscan.fieldterminal.domain.checkinDateFor
import com.bioscan.fieldterminal.domain.expectedShareAt
import com.bioscan.fieldterminal.domain.fuel.Intensity
import com.bioscan.fieldterminal.domain.fuel.MealAdherence
import com.bioscan.fieldterminal.domain.fuel.MealSlotKind
import com.bioscan.fieldterminal.domain.fuel.mealTargetLine
import com.bioscan.fieldterminal.domain.score.ChargeStatus
import com.bioscan.fieldterminal.domain.score.DomainKey
import com.bioscan.fieldterminal.domain.score.ScoreScale
import com.bioscan.fieldterminal.domain.score.SignalsVerdict
import com.bioscan.fieldterminal.domain.score.SleepTarget
import com.bioscan.fieldterminal.domain.score.trendOf
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.FTChevron
import com.bioscan.fieldterminal.ui.components.GaugePalette
import com.bioscan.fieldterminal.ui.components.GaugeStateWord
import com.bioscan.fieldterminal.ui.components.LoadErrorCard
import com.bioscan.fieldterminal.ui.components.NutrientColor
import com.bioscan.fieldterminal.ui.components.RingState
import com.bioscan.fieldterminal.ui.components.ScoreRing
import com.bioscan.fieldterminal.ui.components.ftChargeWash
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.nav.TopLevelTab
import com.bioscan.fieldterminal.ui.screens.AddEntrySheet
import com.bioscan.fieldterminal.ui.screens.status.EveningCheckInSheet
import com.bioscan.fieldterminal.ui.screens.status.NutritionTodayCard
import com.bioscan.fieldterminal.ui.theme.FTType
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sqrt
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT

// Scoring system (docs/scoring-system/02-page-mockups.md §1): the opening page. Only headlines: the CHARGE ring, the five
// domain rings with their number and direction, today's plan on one timeline, and one line for intake so far. Every
// explanation is on the overlay a tap opens. Nothing here is scored while the day is live.

private val TIME = DateTimeFormatter.ofPattern("HH:mm")

/** What a row of TODAY'S PLAN is, for its marker on the timeline. */
enum class PlanKind { MEAL, SESSION, SUPPLEMENTS, CLOSE }

/** One row of TODAY'S PLAN; [kcal] sizes a meal's marker and [durationMin] a session's. */
@Serializable
data class PlanRow(
    val time: LocalTime?,
    val title: String,
    val detail: String?,
    val done: Boolean,
    val action: PlanAction,
    val kind: PlanKind = PlanKind.CLOSE,
    val kcal: Double? = null,
    val durationMin: Double? = null,
)

/** Intake so far today, for the SO FAR bar: kcal against the target, split by where the kcal came from. */
@Serializable
data class SoFar(val kcal: Double, val target: Double?, val carbsG: Double, val proteinG: Double, val fatG: Double)

@Serializable
sealed interface PlanAction {
    @Serializable
    data object SoFar : PlanAction
    @Serializable
    data class Meal(val kind: MealSlotKind) : PlanAction
    @Serializable
    data class CloseDay(val date: LocalDate) : PlanAction
    /** A supplement block (morning, afternoon, night): the supplement form opens with that block ticked, ready to log. */
    @Serializable
    data class Supplements(val bucket: String) : PlanAction
    @Serializable
    data class Tab(val tab: TopLevelTab) : PlanAction
    /** Tonight's sleep target: opens the SLEEP overlay. */
    @Serializable
    data object Bedtime : PlanAction
}

/** Intake so far today for the SO FAR line, or null before the nutrition overview has loaded. */
fun soFarOf(nut: NutritionOverview?, fuel: TodayFuel?): SoFar? =
    nut?.let { n -> n.today.let { t -> SoFar(t?.calories ?: 0.0, fuel?.today?.kcal, t?.carbsG ?: 0.0, t?.proteinG ?: 0.0, t?.fatG ?: 0.0) } }

/** The demand a planned session's intensity puts on CHARGE: READY when CHARGE reaches it. A documented opinion. */
fun readyThreshold(intensity: Intensity): Double = when (intensity) {
    Intensity.EASY -> 40.0
    Intensity.MODERATE -> 55.0
    Intensity.HARD,
    Intensity.LONG,
    Intensity.RACE -> 70.0
}

private val BUCKET_TIMES = mapOf("morning" to LocalTime.of(8, 0), "afternoon" to LocalTime.of(13, 0), "night" to LocalTime.of(21, 0))
// The evening check-in reminder's own time (22:25), so the plan row and the reminder agree.
private val CLOSE_DAY_TIME: LocalTime = EVENING_CHECKIN_TIME

fun planRows(
    now: LocalDateTime,
    fuel: TodayFuel?,
    previews: TodayPreviews?,
    charge: Double?,
    closedYesterday: Boolean,
    closedToday: Boolean,
    sleep: SleepTarget? = null,
): List<PlanRow> {
    val today = now.toLocalDate()
    val rows = mutableListOf<PlanRow>()
    if (!closedYesterday && now.toLocalTime().isBefore(LocalTime.NOON)) {
        val name = today.minusDays(1).dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        rows += PlanRow(null, "Close $name", "1 min", false, PlanAction.CloseDay(today.minusDays(1)), PlanKind.CLOSE)
    }
    fuel?.todayCoded?.meals?.forEach { meal ->
        val kcal = 4 * ((meal.carbLowG + meal.carbHighG) / 2 + meal.proteinG) + 9 * (meal.fatG ?: 0.0)
        val logged = fuel.adherence[meal.kind.name]?.second?.let { it != MealAdherence.NOT_LOGGED } ?: false
        rows += PlanRow(
            meal.time.toLocalTime(),
            meal.kind.label.lowercase().replaceFirstChar { it.uppercase() },
            "${kcal.roundToInt()} kcal",
            logged,
            PlanAction.Meal(meal.kind),
            PlanKind.MEAL,
            kcal = kcal,
        )
    }
    fuel?.horizon?.firstOrNull()?.day?.items?.forEach { item ->
        val ready = charge?.let { if (it >= readyThreshold(item.intensity)) "READY ✓" else "TAKE IT EASIER" }
        rows += PlanRow(
            item.start?.toLocalTime(),
            item.title,
            listOfNotNull("${item.durationMin.roundToInt()} min", ready).joinToString(" · "),
            item.recorded,
            PlanAction.Tab(TopLevelTab.Plan),
            PlanKind.SESSION,
            durationMin = item.durationMin,
        )
    }
    previews?.supplements?.dueByBucket?.forEach { (bucket, due) ->
        rows += PlanRow(BUCKET_TIMES[bucket], "${bucket.replaceFirstChar { it.uppercase() }} supplements", "$due due", false, PlanAction.Supplements(bucket), PlanKind.SUPPLEMENTS)
    }
    rows += PlanRow(CLOSE_DAY_TIME, "Close the day", null, closedToday, PlanAction.CloseDay(today), PlanKind.CLOSE)
    sleep?.bedBy?.let { rows += PlanRow(it, "Bed by", sleep.words, false, PlanAction.Bedtime, PlanKind.CLOSE) }
    return rows.sortedWith(compareBy({ it.time != null }, { it.time }))
}

@Composable
fun TodayHomeScreen(
    onOpenTab: (TopLevelTab) -> Unit = {},
    onOpenDetail: (DetailPage) -> Unit = {},
    onOpenFoodPlan: () -> Unit = {},
    // The 22:25 reminder: open with the evening check-in up.
    openCheckIn: Boolean = false,
    onCheckInHandled: () -> Unit = {},
    // A supplement reminder: open with that block's supplements ready to log.
    requestedSupplements: String? = null,
    onSupplementsHandled: () -> Unit = {},
) {
    val context = LocalContext.current
    // What the page shows and how it stays current is TodayPage's; this screen holds only what is open on it.
    val page = rememberTodayPage()
    val guard = page.guard
    val view = page.view
    val nut = page.nut
    val fuel = page.fuel
    val previews = page.previews
    val closed = page.closed
    val keptRows = page.keptRows
    val keptSoFar = page.keptSoFar
    val loading = page.loading
    var stack by remember { mutableStateOf<List<ScoreOverlay>>(emptyList()) }
    var soFar by remember { mutableStateOf(false) }
    var mealKind by remember { mutableStateOf<MealSlotKind?>(null) }
    var logFood by remember { mutableStateOf(false) }
    var checkInDate by remember { mutableStateOf<LocalDate?>(null) }
    var supplementBucket by remember { mutableStateOf<String?>(null) }
    // A voice already done: tapping it shows a read-only card instead of the log flow.
    var doneRow by remember { mutableStateOf<PlanRow?>(null) }

    LaunchedEffect(openCheckIn) {
        if (openCheckIn) {
            checkInDate = checkinDateFor(LocalDateTime.now())
            onCheckInHandled()
        }
    }
    LaunchedEffect(requestedSupplements) {
        if (requestedSupplements != null) {
            supplementBucket = requestedSupplements.ifBlank { previews?.supplements?.suggestedBucket ?: "" }
            onSupplementsHandled()
        }
    }

    // What a voice's action opens (the log/detail flow); reused by a not-done tap and by the completed card's button.
    val openAction: (PlanAction) -> Unit = { action ->
        when (action) {
            PlanAction.SoFar -> soFar = true
            is PlanAction.Meal -> mealKind = action.kind
            is PlanAction.CloseDay -> checkInDate = action.date
            is PlanAction.Supplements -> supplementBucket = action.bucket
            is PlanAction.Tab -> onOpenTab(action.tab)
            PlanAction.Bedtime -> stack = listOf(ScoreOverlay.Domain(DomainKey.SLEEP))
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(FT.Base)) {
        if (guard.error != null && view == null) {
            LoadErrorCard(guard)
            return@Column
        }
        val now = LocalDateTime.now()
        TodayContent(
            view = view,
            rows = if (fuel == null && previews == null && keptRows != null) keptRows!!
            else planRows(now, fuel, previews, view?.scores?.charge?.score, now.toLocalDate().minusDays(1) in closed, now.toLocalDate() in closed, view?.sleepTarget),
            soFar = soFarOf(nut, fuel) ?: keptSoFar,
            // A kept page is refreshed silently: LOADING shows only while there is nothing to show yet.
            loading = loading && view == null,
            today = now.toLocalDate(),
            onOpenOverlay = { stack = listOf(it) },
            // A completed voice opens its card; one still to do opens its log flow.
            onRow = { row -> if (row.done) doneRow = row else openAction(row.action) },
            onSoFar = { soFar = true },
        )
    }

    view?.let { ScoreOverlayHost(it, stack, onStack = { s -> stack = s }, onOpenDetail = onOpenDetail) }
    if (soFar) {
        FullScreenOverlay(title = "SO FAR TODAY", onClose = { soFar = false }) {
            val n = nut
            if (n != null) NutritionTodayCard(n, fuel, onOpenPlan = { soFar = false; onOpenFoodPlan() })
            else Text("Nothing logged yet today.", style = FTType.Body, color = FT.TextMuted)
        }
    }
    val f = fuel
    val kind = mealKind
    if (f != null && kind != null) {
        MealOverlay(
            fuel = f, kind = kind,
            onClose = { mealKind = null },
            onLogged = { mealKind = null; guard.retry() },
            onLogOther = { mealKind = null; logFood = true },
        )
    }
    if (logFood) {
        AddEntrySheet(
            onDismiss = { logFood = false },
            onSaved = { logFood = false; guard.retry() },
            start = AddEntryStart.Fuel(FuelSubType.Food),
        )
    }
    supplementBucket?.let { bucket ->
        AddEntrySheet(
            onDismiss = { supplementBucket = null },
            onSaved = { supplementBucket = null; guard.retry() },
            start = AddEntryStart.Fuel(FuelSubType.Supplements, supplementBundle = bucket.ifBlank { null }),
        )
    }
    checkInDate?.let { date ->
        EveningCheckInSheet(date = date, onDismiss = { checkInDate = null }, onSaved = { checkInDate = null; guard.retry() })
    }
    doneRow?.let { row ->
        CompletedVoiceCard(row, fuel, onClose = { doneRow = null }) {
            val action = row.action
            doneRow = null
            openAction(action)
        }
    }
}

/**
 * A voice already done: a read-only card of what it was and a short summary of what was logged, with one button to
 * open or change the underlying record. Taps on a voice still to do go straight to its log flow instead.
 */
@Composable
private fun CompletedVoiceCard(row: PlanRow, fuel: TodayFuel?, onClose: () -> Unit, onEdit: () -> Unit) {
    FullScreenOverlay(title = row.title, trailing = row.time?.format(TIME), onClose = onClose) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("DONE ✓", style = FTType.LabelCaps, color = FT.Ok)
            row.detail?.let { Text(it, style = FTType.BodySmall, color = FT.TextSecondary) }
        }
        when (val action = row.action) {
            is PlanAction.Meal -> {
                fuel?.todayCoded?.meals?.firstOrNull { it.kind == action.kind }?.let {
                    Text(mealTargetLine(it), style = FTType.BodySmall, color = FT.TextSecondary)
                }
                fuel?.adherence?.get(action.kind.name)?.let { (g, adh) ->
                    if (adh != MealAdherence.NOT_LOGGED)
                        Text("Logged ${g.roundToInt()} g carbs · ${adh.label}", style = FTType.LabelCaps, color = FT.Ok)
                }
                FTButton(label = "VIEW OR CHANGE WHAT YOU LOGGED",
                    emphasis = FTButtonEmphasis.Secondary, modifier = Modifier.fillMaxWidth()) { onEdit() }
            }
            is PlanAction.CloseDay -> {
                Text("You closed this day. Its morning scores finalise once everything has synced.", style = FTType.BodySmall, color = FT.TextSecondary)
                FTButton(label = "OPEN THE CHECK-IN",
                    emphasis = FTButtonEmphasis.Secondary, modifier = Modifier.fillMaxWidth()) { onEdit() }
            }
            is PlanAction.Tab -> {
                Text("Recorded ✓", style = FTType.LabelCaps, color = FT.Ok)
                FTButton(label = "OPEN IN PLAN",
                    emphasis = FTButtonEmphasis.Secondary, modifier = Modifier.fillMaxWidth()) { onEdit() }
            }
            else -> Unit
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * TODAY's layout from plain data: the CHARGE ring, the five domain rings, today's plan and the SO FAR line. Kept apart
 * from the loading so it can be drawn on its own (and is in the screenshot tour).
 */
@Composable
fun TodayContent(
    view: ScoresView?,
    rows: List<PlanRow>,
    soFar: SoFar?,
    loading: Boolean,
    today: LocalDate,
    now: LocalTime = LocalTime.now(),
    onOpenOverlay: (ScoreOverlay) -> Unit,
    onRow: (PlanRow) -> Unit,
    onSoFar: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().ftChargeWash().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(today.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)).uppercase(), style = FTType.LabelCaps, color = FT.TextSecondary, modifier = Modifier.weight(1f))
            if (loading) Text("LOADING…", style = FTType.Micro, color = FT.TextMuted)
        }

        val v = view
        val charge = v?.scores?.charge
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ScoreRing(
                score = charge?.score,
                trend = trendOf(charge?.change),
                size = 220.dp,
                label = "CHARGE",
                state = when {
                    v == null -> RingState.BUILDING
                    charge?.score == null -> RingState.PARTIAL
                    v.status == ChargeStatus.PROVISIONAL -> RingState.PROVISIONAL
                    else -> RingState.SCORED
                },
                capped = charge?.cap != null || v?.illness != null,
                emptyText = if (v == null) "…" else "—",
                description = "Charge",
                modifier = Modifier.ftClickable(role = Role.Button, onClickLabel = "Open Charge", enabled = v != null) { onOpenOverlay(ScoreOverlay.Charge) },
            )
            charge?.score?.let { sc ->
                val band = ScoreScale.band(sc)
                GaugeStateWord(band.label, band, holo = true)
            }
            // The open illness and what the body's signals say, or an early heads-up (domain/score/IllnessRead.kt).
            v?.illness?.let { Text("⚠ ${it.reason}", style = FTType.Caption, color = FT.Warn, textAlign = TextAlign.Center) }
            v?.signals?.takeIf { it.verdict == SignalsVerdict.HEADS_UP || v.illness != null }?.let {
                Text(it.line, style = FTType.Caption, color = FT.TextSecondary, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp))
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            for (key in DomainKey.entries) {
                val d = v?.scores?.domains?.get(key)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.ftClickable(role = Role.Button, onClickLabel = "Open ${key.label}", enabled = v != null) { onOpenOverlay(ScoreOverlay.Domain(key)) },
                ) {
                    ScoreRing(
                        score = d?.score,
                        trend = trendOf(d?.change),
                        size = 62.dp,
                        palette = GaugePalette.of(key),
                        state = if (d == null || d.partial) RingState.PARTIAL else RingState.SCORED,
                        emptyText = if (v == null) "…" else "—",
                        description = key.label,
                    )
                    Text(key.label, style = FTType.Micro, color = FT.TextSecondary, textAlign = TextAlign.Center)
                }
            }
        }

        Section("TODAY'S PLAN") {
            DayTimeline(rows, now, onRow)
        }

        soFar?.let { s ->
            Column(
                modifier = Modifier.fillMaxWidth().ftClickable(role = Role.Button, onClickLabel = "Open intake so far") { onSoFar() }
                    .background(FT.Glass, RoundedCornerShape(FT.RadiusModule)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("SO FAR", style = FTType.LabelCaps, color = FT.TextMuted, modifier = Modifier.width(72.dp))
                    Text(if (s.target != null) "${s.kcal.roundToInt()} / ${s.target.roundToInt()} kcal" else "${s.kcal.roundToInt()} kcal",
                        style = FTType.Value, color = FT.TextPrimary, modifier = Modifier.weight(1f))
                    FTChevron(tint = FT.TextMuted)
                }
                SoFarBar(s, now)
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(FT.Glass, RoundedCornerShape(FT.RadiusModule))
            .border(FT.BorderWidth, FT.GlassBorder, RoundedCornerShape(FT.RadiusModule))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(title, style = FTType.LabelCaps, color = FT.TextMuted, modifier = Modifier.padding(bottom = 6.dp))
        content()
    }
}


// ---- 1. today's plan as a timeline ----

/**
 * TODAY'S PLAN on a vertical time rail: meals as dots sized by their kcal, sessions as bars as long as they last,
 * supplements as small pills, closing the day as a diamond; filled once done. A NOW line sits where the clock is.
 */
@Composable
private fun DayTimeline(rows: List<PlanRow>, now: LocalTime, onRow: (PlanRow) -> Unit) {
    val nowIndex = rows.indexOfFirst { it.time != null && it.time.isAfter(now) }.let { if (it < 0) rows.size else it }
    Column {
        rows.forEachIndexed { i, row ->
            if (i == nowIndex && rows.any { it.time != null }) NowLine(now)
            TimelineRow(row, first = i == 0, last = i == rows.lastIndex && nowIndex != rows.size) { onRow(row) }
        }
        if (nowIndex == rows.size && rows.isNotEmpty()) NowLine(now)
    }
}

@Composable
private fun NowLine(now: LocalTime) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        // The present is a position, not a status: neutral primary ink (emerald is "optimal" everywhere else).
        Text("NOW", style = FTType.Label, color = FT.TextPrimary, modifier = Modifier.width(48.dp))
        Canvas(Modifier.weight(1f).height(10.dp)) {
            drawLine(FT.TextPrimary.copy(alpha = 0.7f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 2f)
            drawCircle(FT.TextPrimary, 5f, Offset(14.dp.toPx(), size.height / 2))
        }
        Text(now.format(TIME), style = FTType.Micro, color = FT.TextSecondary)
    }
}

@Composable
internal fun TimelineRow(row: PlanRow, first: Boolean, last: Boolean, onClick: () -> Unit) {
    Row(
        // 48 dp: the whole row is the touch target (contract: interactive targets 48 dp).
        modifier = Modifier.fillMaxWidth().height(48.dp).ftClickable(role = Role.Button, onClickLabel = "Open ${row.title}") { onClick() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(row.time?.format(TIME) ?: "now", style = FTType.Value, color = if (row.done) FT.TextMuted else FT.TextSecondary, modifier = Modifier.width(48.dp))
        Canvas(Modifier.width(28.dp).fillMaxSize()) {
            val cx = size.width / 2
            val cy = size.height / 2
            drawLine(FT.GlassBorder, Offset(cx, if (first) cy else 0f), Offset(cx, if (last) cy else size.height), strokeWidth = 2f)
            // The marker's hue names its kind by the Log's category palette (FT.Category: a meal is intake, a session activity); its shape says it too,
            // and filled/outlined says done/planned. Status colours (emerald, amber, red) are never a kind.
            val color = when (row.kind) {
                PlanKind.MEAL -> FT.Category.Intake.c500
                PlanKind.SESSION -> FT.Category.Activity.c500
                PlanKind.SUPPLEMENTS -> FT.DomainLabs
                PlanKind.CLOSE -> FT.DomainLog
            }
            val style = if (row.done) Fill else Stroke(width = 2.5f)
            when (row.kind) {
                PlanKind.MEAL -> {
                    val r = (4f + 7f * sqrt(((row.kcal ?: 400.0) / 900.0).toFloat().coerceIn(0f, 1.4f))).coerceAtMost(12f)
                    drawCircle(FT.Base, r + 2f, Offset(cx, cy))
                    drawCircle(color, r, Offset(cx, cy), style = style)
                }
                PlanKind.SESSION -> {
                    val h = (size.height * ((row.durationMin ?: 45.0) / 120.0).toFloat().coerceIn(0.3f, 1f))
                    drawRoundRect(color, Offset(cx - 5f, cy - h / 2), Size(10f, h), CornerRadius(5f), style = style)
                }
                PlanKind.SUPPLEMENTS -> drawRoundRect(color, Offset(cx - 7f, cy - 4f), Size(14f, 8f), CornerRadius(4f), style = style)
                PlanKind.CLOSE -> {
                    val path = Path().apply { moveTo(cx, cy - 7f); lineTo(cx + 7f, cy); lineTo(cx, cy + 7f); lineTo(cx - 7f, cy); close() }
                    drawPath(path, color, style = style)
                }
            }
        }
        // Title on top, detail under it, so a long session name is never cut by its detail.
        Column(Modifier.weight(1f)) {
            Text(row.title, style = FTType.BodySmall, color = if (row.done) FT.TextSecondary else FT.TextPrimary, maxLines = 1)
            row.detail?.let { Text(it, style = FTType.MonoCaption, color = FT.TextSecondary, maxLines = 1) }
        }
        FTChevron()
    }
}

// ---- 2. intake so far as one bar, split by macro ----

/**
 * One bar for today's kcal against the target, its fill split into the kcal from carbs, protein and fat (4, 4 and 9 kcal
 * a gram), with a tick where the hour expects the day to be.
 */
@Composable
private fun SoFarBar(s: SoFar, now: LocalTime) {
    val target = s.target ?: return
    val parts = listOf(
        "Carbs" to (4 * s.carbsG) to NutrientColor.Carbs,
        "Protein" to (4 * s.proteinG) to NutrientColor.Protein,
        "Fat" to (9 * s.fatG) to NutrientColor.Fat,
    )
    val expected = expectedShareAt(now)
    Canvas(Modifier.fillMaxWidth().height(16.dp)) {
        val full = target * 1.2
        fun x(kcal: Double) = (size.width * (kcal / full)).toFloat().coerceAtMost(size.width)
        drawRoundRect(FT.GlassTrack, size = size, cornerRadius = CornerRadius(6f))
        var at = 0.0
        for ((part, color) in parts) {
            val (_, kcal) = part
            drawRect(color, Offset(x(at), 0f), Size(x(at + kcal) - x(at), size.height))
            at += kcal
        }
        drawLine(FT.TextPrimary, Offset(x(target), -2f), Offset(x(target), size.height + 2f), strokeWidth = 2.5f)
        if (expected > 0) drawLine(FT.TextSecondary, Offset(x(target * expected), 0f), Offset(x(target * expected), size.height), strokeWidth = 2f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f, 4f)))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        for ((part, color) in parts) {
            val (label, kcal) = part
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Canvas(Modifier.width(8.dp).height(8.dp)) { drawRect(color) }
                Text("$label ${kcal.roundToInt()}", style = FTType.Micro, color = FT.TextSecondary)
            }
        }
        Text("┊ by now", style = FTType.Micro, color = FT.TextMuted)
    }
}
