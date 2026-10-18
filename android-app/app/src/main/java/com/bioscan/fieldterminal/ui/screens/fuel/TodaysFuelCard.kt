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
import com.bioscan.fieldterminal.domain.fuel.MealSuggestion
import com.bioscan.fieldterminal.domain.fuel.SessionFuelKind
import com.bioscan.fieldterminal.domain.fuel.SessionWindow
import com.bioscan.fieldterminal.domain.fuel.formatGPerKg
import com.bioscan.fieldterminal.ui.components.FTButton
import com.bioscan.fieldterminal.ui.components.FTButtonEmphasis
import com.bioscan.fieldterminal.ui.components.FTCard
import com.bioscan.fieldterminal.ui.components.FTCardEmphasis
import com.bioscan.fieldterminal.ui.theme.FTType
import com.bioscan.fieldterminal.ui.theme.FuturisticMaterialTokens as FT
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

// Fuel › Plan's lead card (docs/fuel-planning/README.md, phases 6 and 10): today's carbohydrate class and target, the
// meals in order with their codes, carbohydrate, protein and fat, reasons and two example plates, the sessions with what
// to take during them, logged meals placed in their slots, and what changed since the plan was last shown. The days
// ahead are on the same page (FuelPlanPage.kt).

private val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH)

/** Today's plan as the plan page's lead card; the page loads it ([FuelPlanPage]) and shares it with the days ahead. */
@Composable
fun TodaysFuelCard(
    result: TodayFuelResult?,
    error: String?,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FTCard(
        title = "TODAY'S FUEL",
        modifier = modifier,
        emphasis = FTCardEmphasis.Hero,
        methodology = "fuel_day_plan",
        info = "Carbohydrate follows the training of the next 72 hours (\"fuel for the work required\"): more before and after " +
            "hard, long and race sessions, less on rest days. Your calorie goal stays the weekly average; protein stays at the goal. " +
            "Each meal shows two example plates, from foods you eat and a list of staples.",
        infoDetails = "Day bands from Thomas, Erdman & Burke 2016 (3-5 g/kg light, 5-7 about an hour, 6-10 for 1-3 hours, 8-12 over " +
            "4 hours); carbohydrate loading at 10-12 g/kg for the two days before an A or B race over 90 minutes (Burke 2011); 1-4 g/kg " +
            "in the 1-4 hours before key sessions and 1-1.2 g/kg/h after; during sessions 30-90 g/h by length (Jeukendrup 2014); " +
            "protein spread over the meals with at least 0.3 g/kg at each main meal; fat at 20 % of energy or more, low before " +
            "sessions. Codes are LOW / MEDIUM / HIGH with a ±15 % range. Plates are solved by fixed rules from food values " +
            "(USDA FoodData Central and your food catalogue), never by a language model.",
    ) {
        when (result) {
            null -> Text(error?.let { "Couldn't plan today: $it" } ?: "Planning today…", style = FTType.BodySmall, color = if (error != null) FT.Critical else FT.TextSecondary)
            TodayFuelResult.NeedsWeight -> Text("Log a body weight (Fuel › Body) to plan carbohydrate per kilogram.", style = FTType.BodySmall, color = FT.TextSecondary)
            is TodayFuelResult.Ready -> FuelContent(result.fuel)
        }
        if (error != null) FTButton("RETRY", emphasis = FTButtonEmphasis.Tertiary, onClick = onRetry)
        FTButton("FUEL SETTINGS", emphasis = FTButtonEmphasis.Tertiary, onClick = onOpenSettings)
    }
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
    f.horizon.firstOrNull()?.summary?.let { Text("CARBS $it", style = FTType.MonoCaption, color = FT.TextSecondary) }
    Timeline(f.todayCoded, f.adherence, f.longSessions.associate { it.first.sourceId to it.second }, f.horizon.firstOrNull()?.suggestions)
}

private sealed interface Row2 {
    val key: Long
    data class Meal(val meal: CodedMeal) : Row2 { override val key get() = meal.time.toEpochSecond() }
    data class Session(val w: SessionWindow) : Row2 { override val key get() = w.item.start?.toEpochSecond() ?: Long.MAX_VALUE }
}

/** A day's meals and sessions in time order; [suggestions] (by meal, in the coded day's order) adds the example plates. */
@Composable
internal fun Timeline(
    day: CodedDay,
    adherence: Map<String, Pair<Double, MealAdherence>>,
    longPlans: Map<String, com.bioscan.fieldterminal.domain.fuel.RaceFuelPlan>,
    suggestions: List<MealSuggestion>? = null,
) {
    val plates = suggestions?.let { s -> day.meals.zip(s).toMap() }.orEmpty()
    val rows = (day.meals.map { Row2.Meal(it) } + day.sessions.map { Row2.Session(it) }).sortedBy { it.key }
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { row ->
            when (row) {
                is Row2.Meal -> MealLine(row.meal, adherence[row.meal.kind.name], plates[row.meal])
                is Row2.Session -> SessionLine(row.w, longPlans[row.w.item.sourceId])
            }
        }
    }
}

@Composable
private fun MealLine(m: CodedMeal, logged: Pair<Double, MealAdherence>?, plates: MealSuggestion? = null) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(m.time.format(HM), style = FTType.MonoCaption, color = FT.TextSecondary, modifier = Modifier.width(44.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(m.kind.label, style = FTType.LabelCaps, color = FT.TextPrimary)
                CodeChip(m.code)
                Text(gRange(m.carbLowG, m.carbHighG) + " CARBS", style = FTType.MonoCaption, color = FT.TextPrimary)
            }
            Text(
                listOfNotNull(
                    "PROTEIN ${gRange(m.proteinLowG, m.proteinHighG)}",
                    m.fatG?.let { "FAT ~${it.roundToInt()} G" },
                    m.kcal?.let { "~${it.roundToInt()} KCAL" },
                ).joinToString(" · "),
                style = FTType.MonoCaption, color = FT.TextSecondary,
            )
            Text(m.reason, style = FTType.Caption, color = FT.TextSecondary)
            logged?.let { (g, a) ->
                if (a != MealAdherence.NOT_LOGGED) Text("LOGGED ${g.roundToInt()} G · ${a.label}", style = FTType.LabelCaps, color = FT.TextSecondary)
            }
            plates?.let { PlateLines(it) }
        }
    }
}

/** The example plates under a meal: the portions, what they add up to, and which foods come from the person's meals. */
@Composable
private fun PlateLines(s: MealSuggestion) {
    s.note?.let { Text(it, style = FTType.Caption, color = FT.TextSecondary) }
    s.plates.forEachIndexed { i, plate ->
        Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(if (i == 0) "ON THE PLATE" else "OR", style = FTType.LabelCaps, color = FT.TextSecondary)
            Text(plate.label, style = FTType.BodySmall, color = FT.TextPrimary)
            Text(
                "C ${plate.carbG.roundToInt()} · P ${plate.proteinG.roundToInt()} · F ${plate.fatG.roundToInt()} G · ${plate.kcal.roundToInt()} KCAL · " +
                    (if (plate.within) "WITHIN" else plate.gaps.joinToString(", ")),
                style = FTType.MonoCaption, color = FT.TextSecondary,
            )
            val usual = plate.portions.filter { it.food.usual }.map { it.food.name }
            if (usual.isNotEmpty()) Text("FROM YOUR MEALS: ${usual.joinToString(", ")}", style = FTType.Caption, color = FT.TextSecondary)
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
// full plan on Fuel › Plan.
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
