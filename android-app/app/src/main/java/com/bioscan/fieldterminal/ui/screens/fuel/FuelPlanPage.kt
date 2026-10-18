package com.bioscan.fieldterminal.ui.screens.fuel

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.FuelPlanRepository
import com.bioscan.fieldterminal.data.HorizonDay
import com.bioscan.fieldterminal.data.NutritionOverview
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.TodayFuelResult
import com.bioscan.fieldterminal.domain.fuel.PlanSource
import com.bioscan.fieldterminal.domain.fuel.formatGPerKg
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.describeLoadFailure
import com.bioscan.fieldterminal.ui.components.ftClickable
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

// Fuel › Plan (docs/fuel-planning/README.md, phase 10): today's full plan, the next ten days as a strip of carbohydrate
// per kilogram against each day's band, and one row per day with its carbohydrate, protein, fat and energy and the day
// in everyday terms; a row opens its meals with their targets, reasons and two example plates. One load feeds the page.

private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)
private val WEEKDAY: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEEE", Locale.ENGLISH)

/** [openDays] and [onToggleDay] are kept by the Fuel tile, so open days survive a tab switch. */
@Composable
fun FuelPlanPage(
    overview: NutritionOverview?,
    openDays: Set<LocalDate>,
    onToggleDay: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var result by remember { mutableStateOf<TodayFuelResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var settingsOpen by remember { mutableStateOf(false) }
    LaunchedEffect(attempt, overview) {
        error = null
        try {
            result = FuelPlanRepository(SupabaseClientProvider.client).loadToday(context, overview)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            error = describeLoadFailure(e)
        }
    }

    Column(modifier.padding(horizontal = 22.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TodaysFuelCard(result, error, onRetry = { attempt++ }, onOpenSettings = { settingsOpen = true })
        val horizon = (result as? TodayFuelResult.Ready)?.fuel?.horizon.orEmpty()
        if (horizon.size > 1) NextDaysCard(horizon, openDays, onToggleDay)
        overview?.let { PeriodizationCard(it) }
    }
    if (settingsOpen) FuelSettingsSheet(onDismiss = { settingsOpen = false }, onSaved = { settingsOpen = false; attempt++ })
}

@Composable
private fun NextDaysCard(horizon: List<HorizonDay>, openDays: Set<LocalDate>, onToggleDay: (LocalDate) -> Unit) {
    val ahead = horizon.drop(1)
    FTCard(
        title = "NEXT ${ahead.size} DAYS",
        info = "Each day's carbohydrate, protein, fat and energy from the training planned for it (planned sessions, calendar " +
            "training and events) and the days around it. Tap a day for its meals and example plates.",
        infoDetails = "The strip shows carbohydrate per kilogram (bar) against the day's band (shaded). The calorie goal holds " +
            "as the average of each seven days from today, so a heavy week eats more on its own days rather than borrowing " +
            "from the week before. Plans change as the training plan changes; a day that is not planned yet reads as rest.",
    ) {
        CarbStrip(horizon)
        Column(Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            ahead.forEach { d -> DayRow(d, d.day.date in openDays) { onToggleDay(d.day.date) } }
        }
    }
}

/** Carbohydrate g/kg per day, today first, with the day's band behind each bar. Tap or drag to read a day. */
@Composable
private fun CarbStrip(days: List<HorizonDay>) {
    var selected by remember(days) { mutableStateOf<Int?>(null) }
    val shown = selected ?: 0
    val top = ceil(days.maxOf { maxOf(it.plan.carbGPerKg, it.plan.band.highGPerKg) } / 2.0) * 2.0
    val d = days[shown]
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            (if (shown == 0) "TODAY" else d.day.date.format(DAY).uppercase()) + " · ${d.plan.dayClass.label}",
            style = FTType.Label, color = FT.TextMuted, modifier = Modifier.weight(1f),
        )
        Text("${formatGPerKg(d.plan.carbGPerKg)} · ${d.plan.carbG.roundToInt()} G", style = FTType.MonoCaption, color = FT.TextPrimary)
    }
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        // The y axis: its top and zero, in g/kg.
        Column(Modifier.width(34.dp).height(110.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text("${top.roundToInt()}", style = FTType.MonoCaption, color = FT.TextMuted)
            Text("G/KG", style = FTType.MonoCaption, color = FT.TextMuted)
            Text("0", style = FTType.MonoCaption, color = FT.TextMuted)
        }
        Column(Modifier.weight(1f)) {
            Canvas(
                Modifier.fillMaxWidth().height(110.dp)
                    .pointerInput(days) { detectTapGestures { selected = indexAt(it.x, size.width.toFloat(), days.size) } }
                    .pointerInput(days) { detectHorizontalDragGestures { c, _ -> selected = indexAt(c.position.x, size.width.toFloat(), days.size) } },
            ) {
                val slot = size.width / days.size
                val barW = (slot * 0.42f).coerceAtMost(14.dp.toPx())
                val bandW = (slot * 0.78f).coerceAtMost(26.dp.toPx())
                fun y(v: Double) = size.height - (v / top).toFloat() * size.height
                // Recessive grid: the halfway line.
                drawLine(FT.TextMuted.copy(alpha = 0.25f), Offset(0f, y(top / 2)), Offset(size.width, y(top / 2)), strokeWidth = 1f)
                days.forEachIndexed { i, h ->
                    val cx = i * slot + slot / 2
                    val band = h.plan.band
                    drawRoundRect(
                        FT.DomainFuel.copy(alpha = 0.12f), Offset(cx - bandW / 2, y(band.highGPerKg)),
                        Size(bandW, y(band.lowGPerKg) - y(band.highGPerKg)), CornerRadius(3.dp.toPx()),
                    )
                    val v = h.plan.carbGPerKg
                    val alpha = if (i == shown) 1f else 0.55f
                    drawRoundRect(FT.DomainFuel.copy(alpha = alpha), Offset(cx - barW / 2, y(v)), Size(barW, size.height - y(v)), CornerRadius(4.dp.toPx()))
                }
                drawLine(FT.TextMuted.copy(alpha = 0.5f), Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = 1f)
            }
            Row(Modifier.fillMaxWidth().padding(top = 2.dp)) {
                days.forEachIndexed { i, h ->
                    Text(
                        if (i == 0) "T" else h.day.date.format(WEEKDAY),
                        style = FTType.MonoCaption, color = if (i == shown) FT.TextPrimary else FT.TextMuted,
                        textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

private fun indexAt(x: Float, width: Float, n: Int): Int = (x / (width / n)).toInt().coerceIn(0, n - 1)

private fun sourceLabel(s: PlanSource): String = when (s) {
    PlanSource.EVENT -> "EVENT"
    PlanSource.PLANNED_SESSION -> "PLANNED SESSION"
    PlanSource.CALENDAR -> "CALENDAR"
    PlanSource.RECORDED -> "RECORDED"
}

@Composable
private fun DayRow(d: HorizonDay, open: Boolean, onToggle: () -> Unit) {
    val p = d.plan
    val main = d.day.items.maxByOrNull { it.durationMin }
    Column(
        Modifier.fillMaxWidth().ftClickable(onClickLabel = if (open) "Close day" else "Open day", onClick = onToggle).padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(d.day.date.format(DAY).uppercase(), style = FTType.MonoCaption, color = FT.DomainFuel, modifier = Modifier.width(92.dp))
            DayClassChip(p.dayClass)
            Text(
                main?.let { "${it.title.uppercase()} · ${it.durationMin.roundToInt()} MIN" } ?: "REST",
                style = FTType.LabelCaps, color = FT.TextPrimary, modifier = Modifier.weight(1f), maxLines = 1,
            )
            Text(if (open) "−" else "+", style = FTType.LabelCaps, color = FT.TextSecondary)
        }
        Text(
            listOfNotNull(
                "C ${p.carbG.roundToInt()} G",
                "P ${p.proteinG.roundToInt()} G",
                p.fatG?.let { "F ${it.roundToInt()} G" },
                p.kcal?.let { "%,d KCAL".format(it.roundToInt()) },
            ).joinToString(" · "),
            style = FTType.MonoCaption, color = FT.TextPrimary,
        )
        Text("CARBS ${d.summary}", style = FTType.Caption, color = FT.TextSecondary)
        if (open) {
            val sources = d.day.items.map { it.source }.distinct()
            Text(
                if (sources.isEmpty()) "NOTHING PLANNED: READ AS A REST DAY" else "FROM: " + sources.joinToString(" · ") { sourceLabel(it) },
                style = FTType.LabelCaps, color = FT.TextSecondary,
            )
            p.reasons.forEach { Text(it, style = FTType.BodySmall, color = FT.TextSecondary) }
            p.notes.forEach { Text(it, style = FTType.Caption, color = FT.TextSecondary) }
            if (d.day.items.any { it.durationMin >= 90 && it.eventId == null }) {
                Text("Fuel steps and weather for the long session appear in TODAY'S FUEL from three days before.", style = FTType.Caption, color = FT.TextSecondary)
            }
            Timeline(d.coded, emptyMap(), emptyMap(), d.suggestions)
        }
    }
}
