package com.bioscan.fieldterminal.ui.screens.today

import androidx.compose.foundation.background
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
import com.bioscan.fieldterminal.ui.nav.TileRoute
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

/** One row of TODAY'S PLAN. */
data class PlanRow(val time: LocalTime?, val title: String, val detail: String?, val done: Boolean, val action: PlanAction)

sealed interface PlanAction {
    data object SoFar : PlanAction
    data class Meal(val kind: com.bioscan.fieldterminal.domain.fuel.MealSlotKind) : PlanAction
    data class CloseDay(val date: LocalDate) : PlanAction
    data class Tile(val route: TileRoute) : PlanAction
    data class Tab(val tab: com.bioscan.fieldterminal.ui.nav.TopLevelTab) : PlanAction
}

/** The demand a planned session's intensity puts on CHARGE: READY when CHARGE reaches it. A documented opinion. */
fun readyThreshold(intensity: com.bioscan.fieldterminal.domain.fuel.Intensity): Double = when (intensity) {
    com.bioscan.fieldterminal.domain.fuel.Intensity.EASY -> 40.0
    com.bioscan.fieldterminal.domain.fuel.Intensity.MODERATE -> 55.0
    com.bioscan.fieldterminal.domain.fuel.Intensity.HARD,
    com.bioscan.fieldterminal.domain.fuel.Intensity.LONG,
    com.bioscan.fieldterminal.domain.fuel.Intensity.RACE -> 70.0
}

private val BUCKET_TIMES = mapOf("morning" to LocalTime.of(8, 0), "afternoon" to LocalTime.of(13, 0), "night" to LocalTime.of(21, 0))
private val CLOSE_DAY_TIME: LocalTime = LocalTime.of(21, 30)

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
        rows += PlanRow(null, "Close $name", "1 min", false, PlanAction.CloseDay(today.minusDays(1)))
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
        )
    }
    previews?.supplements?.dueByBucket?.forEach { (bucket, due) ->
        rows += PlanRow(BUCKET_TIMES[bucket], "${bucket.replaceFirstChar { it.uppercase() }} supplements", "$due due", false, PlanAction.Tab(com.bioscan.fieldterminal.ui.nav.TopLevelTab.Lab))
    }
    rows += PlanRow(CLOSE_DAY_TIME, "Close the day", null, closedToday, PlanAction.CloseDay(today))
    return rows.sortedWith(compareBy({ it.time != null }, { it.time }))
}

@Composable
fun TodayHomeScreen(
    onOpenTile: (TileRoute) -> Unit,
    onOpenTab: (com.bioscan.fieldterminal.ui.nav.TopLevelTab) -> Unit = {},
    onOpenDetail: (DetailPage) -> Unit = {},
) {
    val context = LocalContext.current
    val guard = rememberLoadGuard()
    var view by remember { mutableStateOf<ScoresView?>(null) }
    var nut by remember { mutableStateOf<NutritionOverview?>(null) }
    var fuel by remember { mutableStateOf<TodayFuel?>(null) }
    var previews by remember { mutableStateOf<TodayPreviews?>(null) }
    var closed by remember { mutableStateOf<Set<LocalDate>>(emptySet()) }
    var stack by remember { mutableStateOf<List<ScoreOverlay>>(emptyList()) }
    var soFar by remember { mutableStateOf(false) }
    var mealKind by remember { mutableStateOf<com.bioscan.fieldterminal.domain.fuel.MealSlotKind?>(null) }
    var logFood by remember { mutableStateOf(false) }
    var checkInDate by remember { mutableStateOf<LocalDate?>(null) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) { runCatching { EveningCheckinRepository(SupabaseClientProvider.client).recordDailyContext() } }

    LaunchedEffect(guard.attempt) {
        guard.run {
            coroutineScope {
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
                view = ScoresRepository(client).load(context, n, o?.healthFlag)
                previews = previewsD.await()
                fuel = fuelD.await()
                closed = closedD.await()
            }
        }
        loading = false
    }

    Column(modifier = Modifier.fillMaxSize().background(FT.Base)) {
        if (guard.error != null && view == null) {
            LoadErrorCard(guard)
            return@Column
        }
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val today = LocalDate.now()
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
                    modifier = Modifier.ftClickable(role = Role.Button, onClickLabel = "Open Charge", enabled = v != null) { stack = listOf(ScoreOverlay.Charge) },
                )
                Text("CHARGE", style = FTType.LabelCaps, color = FT.TextMuted)
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                for (key in DomainKey.entries) {
                    val d = v?.scores?.domains?.get(key)
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.ftClickable(role = Role.Button, onClickLabel = "Open ${key.label}", enabled = v != null) { stack = listOf(ScoreOverlay.Domain(key)) },
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

            val now = LocalDateTime.now()
            val rows = planRows(now, fuel, previews, charge?.score, today.minusDays(1) in closed, today in closed)
            Section("TODAY'S PLAN") {
                for (row in rows) {
                    PlanRowView(row) {
                        when (val a = row.action) {
                            PlanAction.SoFar -> soFar = true
                            is PlanAction.Meal -> mealKind = a.kind
                            is PlanAction.CloseDay -> checkInDate = a.date
                            is PlanAction.Tile -> onOpenTile(a.route)
                            is PlanAction.Tab -> onOpenTab(a.tab)
                        }
                    }
                }
            }

            nut?.let { n ->
                val kcal = n.today?.calories ?: 0.0
                val target = fuel?.today?.kcal
                Row(
                    modifier = Modifier.fillMaxWidth().ftClickable(role = Role.Button, onClickLabel = "Open intake so far") { soFar = true }
                        .background(FT.GlassFill, RoundedCornerShape(FT.RadiusModule)).padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("SO FAR", style = FTType.LabelCaps, color = FT.TextMuted, modifier = Modifier.width(72.dp))
                    Text(if (target != null) "${kcal.roundToInt()} / ${target.roundToInt()} kcal" else "${kcal.roundToInt()} kcal",
                        style = FTType.Value, color = FT.TextPrimary, modifier = Modifier.weight(1f))
                    Text("›", style = FTType.Telemetry, color = FT.TextMuted)
                }
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    view?.let { ScoreOverlayHost(it, stack, onStack = { s -> stack = s }, onOpenDetail = onOpenDetail) }
    if (soFar) {
        FullScreenOverlay(title = "SO FAR TODAY", onClose = { soFar = false }) {
            val n = nut
            if (n != null) NutritionTodayCard(n, fuel, onOpenPlan = { soFar = false; onOpenTile(TileRoute.Nutrition) })
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
    checkInDate?.let { date ->
        EveningCheckInSheet(date = date, onDismiss = { checkInDate = null }, onSaved = { checkInDate = null; guard.retry() })
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

@Composable
private fun PlanRowView(row: PlanRow, onClick: () -> Unit) {
    val late = !row.done && row.time != null && row.time.isBefore(LocalTime.now())
    Row(
        modifier = Modifier.fillMaxWidth().ftClickable(role = Role.Button, onClickLabel = "Open ${row.title}") { onClick() }.padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(row.time?.format(TIME) ?: "now", style = FTType.Value, color = if (late) FT.TextMuted else FT.TextSecondary, modifier = Modifier.width(48.dp))
        Text(if (row.done) "✓" else "○", style = FTType.Telemetry, color = if (row.done) FT.Emerald else FT.TextMuted)
        Text(row.title, style = FTType.BodySmall, color = if (row.done) FT.TextSecondary else FT.TextPrimary, modifier = Modifier.weight(1f))
        row.detail?.let { Text(it, style = FTType.MonoCaption, color = FT.TextSecondary) }
        Text("›", style = FTType.Telemetry, color = FT.TextMuted)
    }
}
