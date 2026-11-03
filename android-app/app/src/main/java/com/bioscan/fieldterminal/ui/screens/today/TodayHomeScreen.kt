@file:UseSerializers(com.bioscan.fieldterminal.domain.LocalDateSerializer::class, com.bioscan.fieldterminal.domain.LocalTimeSerializer::class)

package com.bioscan.fieldterminal.ui.screens.today

import androidx.compose.foundation.background
import kotlinx.serialization.Serializable
import kotlinx.serialization.UseSerializers
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.EveningCheckinRepository
import com.bioscan.fieldterminal.data.FuelPlanRepository
import com.bioscan.fieldterminal.data.NutritionOverview
import com.bioscan.fieldterminal.data.NutritionRepository
import com.bioscan.fieldterminal.data.ScoresRepository
import com.bioscan.fieldterminal.data.ScoresView
import com.bioscan.fieldterminal.data.StatusRepository
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.TodayFuel
import com.bioscan.fieldterminal.data.TodayFuelResult
import com.bioscan.fieldterminal.data.TodayPreviews
import com.bioscan.fieldterminal.domain.EveningCheckin
import com.bioscan.fieldterminal.domain.fuel.MealAdherence
import com.bioscan.fieldterminal.domain.score.ChargeStatus
import com.bioscan.fieldterminal.domain.score.DomainKey
import com.bioscan.fieldterminal.domain.score.trendOf
import com.bioscan.fieldterminal.ui.components.LoadErrorCard
import com.bioscan.fieldterminal.ui.components.RingState
import com.bioscan.fieldterminal.ui.components.ScoreRing
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.components.rememberLoadGuard
import com.bioscan.fieldterminal.ui.screens.status.EveningCheckInSheet
import com.bioscan.fieldterminal.ui.screens.status.NutritionTodayCard
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

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
    data class Meal(val kind: com.bioscan.fieldterminal.domain.fuel.MealSlotKind) : PlanAction
    @Serializable
    data class CloseDay(val date: LocalDate) : PlanAction
    /** A supplement block (morning, afternoon, night): the supplement form opens with that block ticked, ready to log. */
    @Serializable
    data class Supplements(val bucket: String) : PlanAction
    @Serializable
    data class Tab(val tab: com.bioscan.fieldterminal.ui.nav.TopLevelTab) : PlanAction
}

/** Intake so far today for the SO FAR line, or null before the nutrition overview has loaded. */
fun soFarOf(nut: NutritionOverview?, fuel: TodayFuel?): SoFar? =
    nut?.let { n -> n.today.let { t -> SoFar(t?.calories ?: 0.0, fuel?.today?.kcal, t?.carbsG ?: 0.0, t?.proteinG ?: 0.0, t?.fatG ?: 0.0) } }

/** The demand a planned session's intensity puts on CHARGE: READY when CHARGE reaches it. A documented opinion. */
fun readyThreshold(intensity: com.bioscan.fieldterminal.domain.fuel.Intensity): Double = when (intensity) {
    com.bioscan.fieldterminal.domain.fuel.Intensity.EASY -> 40.0
    com.bioscan.fieldterminal.domain.fuel.Intensity.MODERATE -> 55.0
    com.bioscan.fieldterminal.domain.fuel.Intensity.HARD,
    com.bioscan.fieldterminal.domain.fuel.Intensity.LONG,
    com.bioscan.fieldterminal.domain.fuel.Intensity.RACE -> 70.0
}

private val BUCKET_TIMES = mapOf("morning" to LocalTime.of(8, 0), "afternoon" to LocalTime.of(13, 0), "night" to LocalTime.of(21, 0))
// The evening check-in reminder's own time (22:25), so the plan row and the reminder agree.
private val CLOSE_DAY_TIME: LocalTime = com.bioscan.fieldterminal.domain.EVENING_CHECKIN_TIME

fun planRows(
    now: LocalDateTime,
    fuel: TodayFuel?,
    previews: TodayPreviews?,
    charge: Double?,
    closedYesterday: Boolean,
    closedToday: Boolean,
): List<PlanRow> {
    val today = now.toLocalDate()
    val rows = mutableListOf<PlanRow>()
    if (!closedYesterday && now.toLocalTime().isBefore(LocalTime.NOON)) {
        val name = today.minusDays(1).dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH)
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
            PlanAction.Tab(com.bioscan.fieldterminal.ui.nav.TopLevelTab.Plan),
            PlanKind.SESSION,
            durationMin = item.durationMin,
        )
    }
    previews?.supplements?.dueByBucket?.forEach { (bucket, due) ->
        rows += PlanRow(BUCKET_TIMES[bucket], "${bucket.replaceFirstChar { it.uppercase() }} supplements", "$due due", false, PlanAction.Supplements(bucket), PlanKind.SUPPLEMENTS)
    }
    rows += PlanRow(CLOSE_DAY_TIME, "Close the day", null, closedToday, PlanAction.CloseDay(today), PlanKind.CLOSE)
    return rows.sortedWith(compareBy({ it.time != null }, { it.time }))
}

@Composable
fun TodayHomeScreen(
    onOpenTab: (com.bioscan.fieldterminal.ui.nav.TopLevelTab) -> Unit = {},
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
    // TODAY keeps its own page and decides when to rebuild it (TodayStore): no reload on every cache refresh.
    val guard = rememberLoadGuard(refreshOnUpdates = false)
    // Shown at once from the kept page (TodayStore), when it is today's; rebuilt below only if it went stale.
    val kept = remember { TodayStore.forDay(LocalDate.now()) }
    var view by remember { mutableStateOf(kept?.view) }
    var nut by remember { mutableStateOf(kept?.nut) }
    var fuel by remember { mutableStateOf(kept?.fuel) }
    var previews by remember { mutableStateOf(kept?.previews) }
    var closed by remember { mutableStateOf(kept?.closed ?: emptySet()) }
    var scoresKey by remember { mutableStateOf(kept?.key) }
    // The plan and SO FAR as last drawn: shown (from disk, after Android closed the app) until the fuel plan has loaded.
    var keptRows by remember { mutableStateOf(kept?.rows) }
    var keptSoFar by remember { mutableStateOf(kept?.soFar) }
    var stack by remember { mutableStateOf<List<ScoreOverlay>>(emptyList()) }
    var soFar by remember { mutableStateOf(false) }
    var mealKind by remember { mutableStateOf<com.bioscan.fieldterminal.domain.fuel.MealSlotKind?>(null) }
    var logFood by remember { mutableStateOf(false) }
    var checkInDate by remember { mutableStateOf<LocalDate?>(null) }
    var supplementBucket by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(kept == null) }

    LaunchedEffect(openCheckIn) {
        if (openCheckIn) {
            checkInDate = com.bioscan.fieldterminal.domain.checkinDateFor(LocalDateTime.now())
            onCheckInHandled()
        }
    }
    LaunchedEffect(requestedSupplements) {
        if (requestedSupplements != null) {
            supplementBucket = requestedSupplements.ifBlank { previews?.supplements?.suggestedBucket ?: "" }
            onSupplementsHandled()
        }
    }

    LaunchedEffect(Unit) { runCatching { EveningCheckinRepository(SupabaseClientProvider.client).recordDailyContext() } }

    // While TODAY is on screen: once a minute, rebuild in the background if the kept page went stale (a sync landed, the
    // day changed, or 15 minutes passed). The page on screen stays until the new one is ready.
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60_000L)
            if (!loading && !TodayStore.isFresh(TodayStore.snapshot, LocalDate.now())) guard.retry()
        }
    }

    LaunchedEffect(guard.attempt) {
        // First open of the tab with a fresh kept page: nothing to load.
        if (guard.attempt == 0 && TodayStore.isFresh(TodayStore.snapshot, LocalDate.now())) {
            loading = false
            return@LaunchedEffect
        }
        val day = LocalDate.now()
        // After Android closed the app: the page kept on disk for today goes on screen at once.
        if (view == null) {
            TodayStore.readDisk(context, day)?.let { k ->
                view = k.view
                scoresKey = k.key
                keptRows = k.rows
                keptSoFar = k.soFar
                closed = k.closed
            }
        }
        loading = true
        guard.run {
            // Its rebuild must see what was just written (a sync, a log): reads wait for the network, never an old copy.
            kotlinx.coroutines.withContext(com.bioscan.fieldterminal.data.ReadCache.RequireFresh) { coroutineScope {
                val client = SupabaseClientProvider.client
                val status = StatusRepository(client)
                // The overview only supplies the open illness / injury that caps CHARGE: without it the scores still load.
                val overviewD = async { runCatching { status.loadOverview() }.getOrNull() }
                val previewsD = async { runCatching { status.loadTodayPreviews() }.getOrNull() }
                val n = runCatching { NutritionRepository(client).loadOverview() }.getOrNull()
                nut = n
                val fuelD = async { runCatching { (FuelPlanRepository(client).loadToday(context, n, ZonedDateTime.now()) as? TodayFuelResult.Ready)?.fuel }.getOrNull() }
                val closedD = async {
                    runCatching { EveningCheckinRepository(client).loadSince(LocalDate.now().minusDays(1)).map(EveningCheckin::date).toSet() }.getOrDefault(emptySet())
                }
                val o = overviewD.await()
                // The score engine only runs again when what it reads changed (equal key: the kept scores stand).
                val keyed = ScoresRepository(client).loadKeyed(context, n, o?.healthFlag, previous = view, previousKey = scoresKey)
                view = keyed.view
                scoresKey = keyed.key
                previews = previewsD.await()
                fuel = fuelD.await()
                closed = closedD.await()
            } }
            // Keep the finished page, stamped after the load so the load's own bookkeeping writes don't count: in memory,
            // and on disk for the next time the app is opened today.
            val drawnAt = LocalDateTime.now()
            val rows = planRows(drawnAt, fuel, previews, view?.scores?.charge?.score, day.minusDays(1) in closed, day in closed)
            val sf = soFarOf(nut, fuel)
            keptRows = rows
            keptSoFar = sf
            TodayStore.snapshot = TodaySnapshot(day, view, nut, fuel, previews, closed, System.currentTimeMillis(), com.bioscan.fieldterminal.data.ReadCache.writeStamp(TodayStore.TABLES), scoresKey, rows, sf)
            val v = view
            val k = scoresKey
            if (v != null && k != null) TodayStore.writeDisk(context, KeptToday(TodayStore.FORMAT, day, System.currentTimeMillis(), k, v, rows, sf, closed))
        }
        loading = false
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
            else planRows(now, fuel, previews, view?.scores?.charge?.score, now.toLocalDate().minusDays(1) in closed, now.toLocalDate() in closed),
            soFar = soFarOf(nut, fuel) ?: keptSoFar,
            // A kept page is refreshed silently: LOADING shows only while there is nothing to show yet.
            loading = loading && view == null,
            today = now.toLocalDate(),
            onOpenOverlay = { stack = listOf(it) },
            onRow = { action ->
                when (action) {
                    PlanAction.SoFar -> soFar = true
                    is PlanAction.Meal -> mealKind = action.kind
                    is PlanAction.CloseDay -> checkInDate = action.date
                    is PlanAction.Supplements -> supplementBucket = action.bucket
                    is PlanAction.Tab -> onOpenTab(action.tab)
                }
            },
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
        com.bioscan.fieldterminal.ui.screens.AddEntrySheet(
            onDismiss = { logFood = false },
            onSaved = { logFood = false; guard.retry() },
            start = com.bioscan.fieldterminal.domain.AddEntryStart.Fuel(com.bioscan.fieldterminal.domain.FuelSubType.Food),
        )
    }
    supplementBucket?.let { bucket ->
        com.bioscan.fieldterminal.ui.screens.AddEntrySheet(
            onDismiss = { supplementBucket = null },
            onSaved = { supplementBucket = null; guard.retry() },
            start = com.bioscan.fieldterminal.domain.AddEntryStart.Fuel(com.bioscan.fieldterminal.domain.FuelSubType.Supplements, supplementBundle = bucket.ifBlank { null }),
        )
    }
    checkInDate?.let { date ->
        EveningCheckInSheet(date = date, onDismiss = { checkInDate = null }, onSaved = { checkInDate = null; guard.retry() })
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
    onRow: (PlanAction) -> Unit,
    onSoFar: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
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
                size = 200.dp,
                state = when {
                    v == null -> RingState.BUILDING
                    charge?.score == null -> RingState.PARTIAL
                    v.status == ChargeStatus.PROVISIONAL -> RingState.PROVISIONAL
                    else -> RingState.SCORED
                },
                capped = charge?.cap != null,
                emptyText = if (v == null) "…" else "—",
                description = "Charge",
                modifier = Modifier.ftClickable(role = Role.Button, onClickLabel = "Open Charge", enabled = v != null) { onOpenOverlay(ScoreOverlay.Charge) },
            )
            Text("CHARGE", style = FTType.LabelCaps, color = FT.TextMuted)
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
                        size = 58.dp,
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
                    .background(FT.GlassFill, RoundedCornerShape(FT.RadiusModule)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("SO FAR", style = FTType.LabelCaps, color = FT.TextMuted, modifier = Modifier.width(72.dp))
                    Text(if (s.target != null) "${s.kcal.roundToInt()} / ${s.target.roundToInt()} kcal" else "${s.kcal.roundToInt()} kcal",
                        style = FTType.Value, color = FT.TextPrimary, modifier = Modifier.weight(1f))
                    Text("›", style = FTType.Telemetry, color = FT.TextMuted)
                }
                SoFarBar(s, now)
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun Section(title: String, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(FT.GlassFill, RoundedCornerShape(FT.RadiusModule))
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
private fun DayTimeline(rows: List<PlanRow>, now: LocalTime, onRow: (PlanAction) -> Unit) {
    val nowIndex = rows.indexOfFirst { it.time != null && it.time.isAfter(now) }.let { if (it < 0) rows.size else it }
    Column {
        rows.forEachIndexed { i, row ->
            if (i == nowIndex && rows.any { it.time != null }) NowLine(now)
            TimelineRow(row, first = i == 0, last = i == rows.lastIndex && nowIndex != rows.size) { onRow(row.action) }
        }
        if (nowIndex == rows.size && rows.isNotEmpty()) NowLine(now)
    }
}

@Composable
private fun NowLine(now: LocalTime) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("NOW", style = FTType.Label, color = FT.EmeraldHighlight, modifier = Modifier.width(48.dp))
        androidx.compose.foundation.Canvas(Modifier.weight(1f).height(10.dp)) {
            drawLine(FT.EmeraldHighlight, androidx.compose.ui.geometry.Offset(0f, size.height / 2), androidx.compose.ui.geometry.Offset(size.width, size.height / 2), strokeWidth = 2f)
            drawCircle(FT.EmeraldHighlight, 5f, androidx.compose.ui.geometry.Offset(14.dp.toPx(), size.height / 2))
        }
        Text(now.format(TIME), style = FTType.Micro, color = FT.EmeraldHighlight)
    }
}

@Composable
private fun TimelineRow(row: PlanRow, first: Boolean, last: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(44.dp).ftClickable(role = Role.Button, onClickLabel = "Open ${row.title}") { onClick() },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(row.time?.format(TIME) ?: "now", style = FTType.Value, color = if (row.done) FT.TextMuted else FT.TextSecondary, modifier = Modifier.width(48.dp))
        androidx.compose.foundation.Canvas(Modifier.width(28.dp).fillMaxSize()) {
            val cx = size.width / 2
            val cy = size.height / 2
            drawLine(FT.GlassBorder, androidx.compose.ui.geometry.Offset(cx, if (first) cy else 0f), androidx.compose.ui.geometry.Offset(cx, if (last) cy else size.height), strokeWidth = 2f)
            val color = when (row.kind) {
                PlanKind.MEAL -> com.bioscan.fieldterminal.ui.components.NutrientColor.Calories
                PlanKind.SESSION -> FT.Emerald
                PlanKind.SUPPLEMENTS -> FT.DomainLabs
                PlanKind.CLOSE -> FT.DomainLog
            }
            val style = if (row.done) androidx.compose.ui.graphics.drawscope.Fill else androidx.compose.ui.graphics.drawscope.Stroke(width = 2.5f)
            when (row.kind) {
                PlanKind.MEAL -> {
                    val r = (4f + 7f * kotlin.math.sqrt(((row.kcal ?: 400.0) / 900.0).toFloat().coerceIn(0f, 1.4f))).coerceAtMost(12f)
                    drawCircle(FT.Base, r + 2f, androidx.compose.ui.geometry.Offset(cx, cy))
                    drawCircle(color, r, androidx.compose.ui.geometry.Offset(cx, cy), style = style)
                }
                PlanKind.SESSION -> {
                    val h = (size.height * ((row.durationMin ?: 45.0) / 120.0).toFloat().coerceIn(0.3f, 1f))
                    drawRoundRect(color, androidx.compose.ui.geometry.Offset(cx - 5f, cy - h / 2), androidx.compose.ui.geometry.Size(10f, h), androidx.compose.ui.geometry.CornerRadius(5f), style = style)
                }
                PlanKind.SUPPLEMENTS -> drawRoundRect(color, androidx.compose.ui.geometry.Offset(cx - 7f, cy - 4f), androidx.compose.ui.geometry.Size(14f, 8f), androidx.compose.ui.geometry.CornerRadius(4f), style = style)
                PlanKind.CLOSE -> {
                    val path = androidx.compose.ui.graphics.Path().apply { moveTo(cx, cy - 7f); lineTo(cx + 7f, cy); lineTo(cx, cy + 7f); lineTo(cx - 7f, cy); close() }
                    drawPath(path, color, style = style)
                }
            }
        }
        // Title on top, detail under it, so a long session name is never cut by its detail.
        Column(Modifier.weight(1f)) {
            Text(row.title, style = FTType.BodySmall, color = if (row.done) FT.TextSecondary else FT.TextPrimary, maxLines = 1)
            row.detail?.let { Text(it, style = FTType.MonoCaption, color = FT.TextSecondary, maxLines = 1) }
        }
        Text("›", style = FTType.Telemetry, color = FT.TextMuted)
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
        "Carbs" to (4 * s.carbsG) to com.bioscan.fieldterminal.ui.components.NutrientColor.Carbs,
        "Protein" to (4 * s.proteinG) to com.bioscan.fieldterminal.ui.components.NutrientColor.Protein,
        "Fat" to (9 * s.fatG) to com.bioscan.fieldterminal.ui.components.NutrientColor.Fat,
    )
    val expected = com.bioscan.fieldterminal.domain.expectedShareAt(now)
    androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(16.dp)) {
        val full = target * 1.2
        fun x(kcal: Double) = (size.width * (kcal / full)).toFloat().coerceAtMost(size.width)
        drawRoundRect(FT.GlassTrack, size = size, cornerRadius = androidx.compose.ui.geometry.CornerRadius(6f))
        var at = 0.0
        for ((part, color) in parts) {
            val (_, kcal) = part
            drawRect(color, androidx.compose.ui.geometry.Offset(x(at), 0f), androidx.compose.ui.geometry.Size(x(at + kcal) - x(at), size.height))
            at += kcal
        }
        drawLine(FT.TextPrimary, androidx.compose.ui.geometry.Offset(x(target), -2f), androidx.compose.ui.geometry.Offset(x(target), size.height + 2f), strokeWidth = 2.5f)
        if (expected > 0) drawLine(FT.TextSecondary, androidx.compose.ui.geometry.Offset(x(target * expected), 0f), androidx.compose.ui.geometry.Offset(x(target * expected), size.height), strokeWidth = 2f,
            pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(4f, 4f)))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        for ((part, color) in parts) {
            val (label, kcal) = part
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                androidx.compose.foundation.Canvas(Modifier.width(8.dp).height(8.dp)) { drawRect(color) }
                Text("$label ${kcal.roundToInt()}", style = FTType.Micro, color = FT.TextSecondary)
            }
        }
        Text("┊ by now", style = FTType.Micro, color = FT.TextMuted)
    }
}
