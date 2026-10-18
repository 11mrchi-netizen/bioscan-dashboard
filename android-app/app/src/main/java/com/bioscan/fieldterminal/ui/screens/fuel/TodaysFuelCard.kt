package com.bioscan.fieldterminal.ui.screens.fuel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.bioscan.fieldterminal.data.FuelPlanRepository
import com.bioscan.fieldterminal.data.NutritionOverview
import com.bioscan.fieldterminal.data.SupabaseClientProvider
import com.bioscan.fieldterminal.data.TodayFuel
import com.bioscan.fieldterminal.data.TodayFuelResult
import com.bioscan.fieldterminal.domain.fuel.CodedDay
import com.bioscan.fieldterminal.domain.fuel.CodedMeal
import com.bioscan.fieldterminal.domain.fuel.MealAdherence
import com.bioscan.fieldterminal.domain.fuel.SessionFuelKind
import com.bioscan.fieldterminal.domain.fuel.SessionWindow
import com.bioscan.fieldterminal.domain.fuel.formatGPerKg
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTCardEmphasis
import com.bioscan.fieldterminal.ui.components.describeLoadFailure
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

// Fuel › Nutrition's lead card (docs/fuel-planning/README.md, phase 6): today's carbohydrate class and target, the meals
// in order with their codes, ranges and reasons, the sessions with what to take during them, logged meals placed in their
// slots, what changed since the plan was last shown, and tomorrow in one line.

private val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

@Composable
fun TodaysFuelCard(overview: NutritionOverview?, modifier: Modifier = Modifier) {
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

    FTCard(
        title = "TODAY'S FUEL",
        modifier = modifier,
        emphasis = FTCardEmphasis.Hero,
        info = "Carbohydrate follows the training of the next 72 hours (\"fuel for the work required\"): more before and after " +
            "hard, long and race sessions, less on rest days. Your calorie goal stays the weekly average; protein stays at the goal.",
        infoDetails = "Day bands from Thomas, Erdman & Burke 2016 (3-5 g/kg light, 5-7 about an hour, 6-10 for 1-3 hours, 8-12 over " +
            "4 hours); carbohydrate loading at 10-12 g/kg for the two days before an A or B race over 90 minutes (Burke 2011); 1-4 g/kg " +
            "in the 1-4 hours before key sessions and 1-1.2 g/kg/h after; during sessions 30-90 g/h by length (Jeukendrup 2014); " +
            "protein 0.3-0.4 g/kg a meal. Fat stays at 20 % of energy or more. Codes are LOW / MEDIUM / HIGH with a ±15 % range.",
    ) {
        when (val r = result) {
            null -> Text(error?.let { "Couldn't plan today: $it" } ?: "Planning today…", style = FTType.BodySmall, color = if (error != null) FT.Critical else FT.TextSecondary)
            TodayFuelResult.NeedsWeight -> Text("Log a body weight (Fuel › Body) to plan carbohydrate per kilogram.", style = FTType.BodySmall, color = FT.TextSecondary)
            is TodayFuelResult.Ready -> FuelContent(r.fuel)
        }
        if (error != null) FTButton("RETRY", emphasis = FTButtonEmphasis.Tertiary) { attempt++ }
        FTButton("FUEL SETTINGS", emphasis = FTButtonEmphasis.Tertiary) { settingsOpen = true }
    }
    if (settingsOpen) FuelSettingsSheet(onDismiss = { settingsOpen = false }, onSaved = { settingsOpen = false; attempt++ })
}

@Composable
private fun FuelContent(f: TodayFuel) {
    val p = f.today
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        DayClassChip(p.dayClass)
        Text("${formatGPerKg(p.carbGPerKg)} · ${p.carbG.roundToInt()} G CARBS", style = FTType.LabelCaps, color = FT.TextPrimary)
    }
    Text(
        listOfNotNull(
            "PROTEIN ${p.proteinG.roundToInt()} G",
            p.fatG?.let { "FAT ${it.roundToInt()} G" },
            p.kcal?.let {
                "${it.roundToInt()} KCAL" + when {
                    f.energyFromGoal -> " (GOAL, AS A WEEKLY AVERAGE)"
                    f.observedMaintenance -> " (MAINTENANCE, FROM YOUR INTAKE AND WEIGHT)"
                    else -> " (MAINTENANCE ESTIMATE)"
                }
            },
        ).joinToString(" · "),
        style = FTType.MonoCaption, color = FT.TextSecondary,
    )
    p.reasons.forEach { Text(it, style = FTType.BodySmall, color = FT.TextSecondary) }
    (p.notes + f.history.notes).distinct().forEach { Text(it, style = FTType.Caption, color = FT.TextSecondary) }
    f.loadingCountdown?.let { (race, start) ->
        if (!p.loading) Text("LOADING FOR ${race.title.uppercase()} STARTS ${start.dayOfWeek.name.take(3)} ${start.dayOfMonth}", style = FTType.LabelCaps, color = FT.DomainFuel)
    }
    if (f.changes.isNotEmpty()) {
        Text("CHANGED SINCE THE PLAN WAS LAST SHOWN", style = FTType.LabelCaps, color = FT.TextSecondary, modifier = Modifier.padding(top = 6.dp))
        f.changes.forEach { Text("· $it", style = FTType.BodySmall, color = FT.TextPrimary) }
    }
    Timeline(f.todayCoded, f.adherence, f.longSessions.associate { it.first.sourceId to it.second })
    f.tomorrow?.let { t ->
        val codes = f.tomorrowCoded?.meals.orEmpty().joinToString(" · ") { "${it.kind.label} ${it.code.label}" }
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("TOMORROW", style = FTType.LabelCaps, color = FT.TextSecondary)
                DayClassChip(t.dayClass)
                Text(formatGPerKg(t.carbGPerKg), style = FTType.MonoCaption, color = FT.TextSecondary)
            }
            if (codes.isNotBlank()) Text(codes, style = FTType.MonoCaption, color = FT.TextSecondary)
        }
    }
}

private sealed interface Row2 {
    val key: Long
    data class Meal(val meal: CodedMeal) : Row2 { override val key get() = meal.time.toEpochSecond() }
    data class Session(val w: SessionWindow) : Row2 { override val key get() = w.item.start?.toEpochSecond() ?: Long.MAX_VALUE }
}

@Composable
private fun Timeline(day: CodedDay, adherence: Map<String, Pair<Double, MealAdherence>>, longPlans: Map<String, com.bioscan.fieldterminal.domain.fuel.RaceFuelPlan>) {
    val rows = (day.meals.map { Row2.Meal(it) } + day.sessions.map { Row2.Session(it) }).sortedBy { it.key }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { row ->
            when (row) {
                is Row2.Meal -> MealLine(row.meal, adherence[row.meal.kind.name])
                is Row2.Session -> SessionLine(row.w, longPlans[row.w.item.sourceId])
            }
        }
    }
}

@Composable
private fun MealLine(m: CodedMeal, logged: Pair<Double, MealAdherence>?) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(m.time.format(HM), style = FTType.MonoCaption, color = FT.TextSecondary, modifier = Modifier.width(44.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(m.kind.label, style = FTType.LabelCaps, color = FT.TextPrimary)
                CodeChip(m.code)
                Text(gRange(m.carbLowG, m.carbHighG) + " CARBS", style = FTType.MonoCaption, color = FT.TextPrimary)
            }
            m.proteinLowG?.let { Text("PROTEIN ${gRange(it, m.proteinHighG ?: it)}", style = FTType.MonoCaption, color = FT.TextSecondary) }
            Text(m.reason, style = FTType.Caption, color = FT.TextSecondary)
            logged?.let { (g, a) ->
                if (a != MealAdherence.NOT_LOGGED) Text("LOGGED ${g.roundToInt()} G · ${a.label}", style = FTType.LabelCaps, color = FT.TextSecondary)
            }
        }
    }
}

@Composable
private fun SessionLine(w: SessionWindow, long: com.bioscan.fieldterminal.domain.fuel.RaceFuelPlan?) {
    val item = w.item
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(item.start?.format(HM) ?: "--:--", style = FTType.MonoCaption, color = FT.DomainTraining, modifier = Modifier.width(44.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("${item.title.uppercase()} · ${item.durationMin.roundToInt()} MIN · ${item.intensity.label}${if (item.recorded) " · DONE" else ""}", style = FTType.LabelCaps, color = FT.TextPrimary)
            Text(item.intensityReason, style = FTType.Caption, color = FT.TextSecondary)
            val fuelLine = when (w.fuel.kind) {
                SessionFuelKind.NONE -> "DURING: NOTHING NEEDED"
                SessionFuelKind.RINSE -> "DURING: A LITTLE, OR A MOUTH RINSE"
                else -> "DURING: ${w.fuel.gPerHourLow.roundToInt()}-${w.fuel.gPerHourHigh.roundToInt()} G/H (${w.fuel.totalLowG.roundToInt()}-${w.fuel.totalHighG.roundToInt()} G)"
            }
            Text(fuelLine, style = FTType.MonoCaption, color = FT.TextPrimary)
            long?.let { plan ->
                Text(
                    "FLUID ${plan.fluidMlPerHourLow.roundToInt()}-${plan.fluidMlPerHourHigh.roundToInt()} ML/H · SODIUM ${plan.sodiumMgPerL.roundToInt()} MG/L" +
                        (plan.heat?.let { " · ${it.band.label}" } ?: ""),
                    style = FTType.MonoCaption, color = FT.TextSecondary,
                )
                plan.steps.take(4).forEach { s -> Text("${hm(s.elapsedMin)}  ${s.items.joinToString(", ")}", style = FTType.Caption, color = FT.TextSecondary) }
                if (plan.steps.size > 4) Text("… then the same every 20 minutes.", style = FTType.Caption, color = FT.TextSecondary)
            }
        }
    }
}

// Today's answer-first line (Today page): the day's class and target and the session it is built around, opening the
// full plan on Fuel › Nutrition.
@Composable
fun TodayFuelLine(overview: NutritionOverview?, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var result by remember { mutableStateOf<TodayFuelResult?>(null) }
    LaunchedEffect(overview) {
        result = runCatching { FuelPlanRepository(SupabaseClientProvider.client).loadToday(context, overview) }.getOrNull()
    }
    val fuel = (result as? TodayFuelResult.Ready)?.fuel ?: return
    val p = fuel.today
    val key = fuel.todayCoded.sessions.map { it.item }.filter { it.intensity.isKey }.maxByOrNull { it.durationMin }
    FTCard(title = "FUEL", modifier = modifier, onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            DayClassChip(p.dayClass)
            Text(
                "${formatGPerKg(p.carbGPerKg)}" + (key?.let { " · ${it.title.uppercase()} ${it.start?.format(HM) ?: ""}".trimEnd() } ?: "") + " ›",
                style = FTType.LabelCaps, color = FT.TextPrimary,
            )
        }
        fuel.todayCoded.meals.firstOrNull { it.time.isAfter(java.time.ZonedDateTime.now()) }?.let { next ->
            Text("NEXT: ${next.kind.label} ${next.code.label} · ${gRange(next.carbLowG, next.carbHighG)} CARBS", style = FTType.MonoCaption, color = FT.TextSecondary)
        }
    }
}
